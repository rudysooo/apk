package com.rudysooo.messages

import android.Manifest
import android.app.role.RoleManager
import androidx.compose.runtime.Immutable
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.klinker.android.send_message.Message
import com.klinker.android.send_message.Transaction
import com.klinker.android.send_message.Settings as MmsSettings

@Immutable
data class Conv(
    val threadId: Long,
    val addresses: List<String>,
    val title: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
    val hasAttachment: Boolean,
) {
    val address get() = addresses.firstOrNull() ?: ""
    val isGroup get() = addresses.size > 1
}

@Immutable
class MsgPart(val id: Long, val mime: String, val name: String) {
    val uri: Uri get() = Uri.parse("content://mms/part/$id")
    val isImage get() = mime.startsWith("image/")
}

@Immutable
/** type : 1 reçu, 2 envoyé, 4 en cours d'envoi, 5 échec */
class Msg(
    val uid: String,
    val id: Long,
    val body: String,
    val date: Long,
    val type: Int,
    val status: Int = -1,
    val mms: Boolean = false,
    val parts: List<MsgPart> = emptyList(),
    val sender: String? = null,
) {
    val incoming get() = type == 1
    val failed get() = type == 5
    val pending get() = type == 4 || type == 6
    val delivered get() = status == 0
}

data class Hit(val threadId: Long, val address: String, val name: String?, val body: String, val date: Long)

data class Contact(val name: String, val number: String)

object Sms {
    private val names = HashMap<String, String?>()

    fun hasSmsPermissions(ctx: Context) =
        listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS).all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }

    fun isDefault(ctx: Context): Boolean {
        if (Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName) return true
        return Build.VERSION.SDK_INT >= 29 &&
            runCatching { ctx.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_SMS) }.getOrDefault(false)
    }

    fun displayName(ctx: Context, address: String): String? {
        if (address.isBlank()) return null
        synchronized(names) { if (names.containsKey(address)) return names[address] }
        val n = runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        synchronized(names) { names[address] = n }
        return n
    }

    private fun canonicalAddresses(ctx: Context): Map<Long, String> = runCatching {
        val out = HashMap<Long, String>()
        ctx.contentResolver.query(Uri.parse("content://mms-sms/canonical-addresses"), arrayOf("_id", "address"), null, null, null)
            ?.use { while (it.moveToNext()) out[it.getLong(0)] = it.getString(1) ?: "" }
        out
    }.getOrDefault(emptyMap())

    private fun unreadCounts(ctx: Context): Map<Long, Int> {
        val unread = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("thread_id"), "read=0 AND type=1", null, null)
                ?.use { while (it.moveToNext()) unread.merge(it.getLong(0), 1, Int::plus) }
        }
        runCatching {
            ctx.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("thread_id"), "read=0 AND msg_box=1", null, null)
                ?.use { while (it.moveToNext()) unread.merge(it.getLong(0), 1, Int::plus) }
        }
        return unread
    }

    /** Conversations SMS + MMS, groupes compris. */
    fun conversations(ctx: Context): List<Conv> {
        val canon = canonicalAddresses(ctx)
        val unread = unreadCounts(ctx)
        val out = ArrayList<Conv>()
        runCatching {
            val uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()
            ctx.contentResolver.query(
                uri, arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "has_attachment"),
                null, null, "date DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getInt(2) <= 0) continue
                    val addrs = (c.getString(3) ?: "").split(' ').mapNotNull { it.toLongOrNull() }
                        .mapNotNull { canon[it] }.filter { it.isNotBlank() }
                    val title = addrs.joinToString(", ") { displayName(ctx, it) ?: it }.ifBlank { "Inconnu" }
                    val hasAtt = c.getInt(5) == 1
                    val snippet = (c.getString(4) ?: "").ifBlank { if (hasAtt) "📎 Pièce jointe" else "" }
                    out += Conv(c.getLong(0), addrs, title, snippet, c.getLong(1), unread[c.getLong(0)] ?: 0, hasAtt)
                }
            }
        }
        return out
    }

    fun messages(ctx: Context, threadId: Long): List<Msg> {
        val out = ArrayList<Msg>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS),
                "thread_id=?", arrayOf(threadId.toString()), "date ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Msg("s${c.getLong(0)}", c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getInt(4))
                }
            }
        }
        out += mmsMessages(ctx, threadId)
        return out.sortedBy { it.date }
    }

    private fun mmsMessages(ctx: Context, threadId: Long): List<Msg> = runCatching {
        data class Row(val id: Long, val date: Long, val box: Int, val mType: Int, val subject: String)
        val rows = ArrayList<Row>()
        ctx.contentResolver.query(
            Telephony.Mms.CONTENT_URI, arrayOf("_id", "date", "msg_box", "m_type", "sub"),
            "thread_id=?", arrayOf(threadId.toString()), "date ASC",
        )?.use { c ->
            while (c.moveToNext()) rows += Row(c.getLong(0), c.getLong(1) * 1000, c.getInt(2), c.getInt(3), c.getString(4) ?: "")
        }
        if (rows.isEmpty()) return@runCatching emptyList<Msg>()
        val texts = HashMap<Long, StringBuilder>()
        val parts = HashMap<Long, MutableList<MsgPart>>()
        ctx.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "mid", "ct", "text", "name"),
            "mid IN (${rows.joinToString(",") { it.id.toString() }})", null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mid = c.getLong(1); val ct = c.getString(2) ?: ""
                when {
                    ct == "text/plain" -> texts.getOrPut(mid) { StringBuilder() }.append(c.getString(3) ?: "")
                    ct == "application/smil" -> {}
                    else -> parts.getOrPut(mid) { ArrayList() } += MsgPart(c.getLong(0), ct, c.getString(4) ?: "")
                }
            }
        }
        rows.map { r ->
            val type = when (r.box) { 1 -> 1; 4 -> 4; 5 -> 5; else -> 2 }
            val sender = if (type == 1) mmsSender(ctx, r.id) else null
            var body = texts[r.id]?.toString() ?: ""
            if (r.subject.isNotBlank() && body.isBlank()) body = r.subject
            if (r.mType == 130) body = "Message multimédia en attente de téléchargement…"
            Msg("m${r.id}", r.id, body, r.date, type, mms = true, parts = parts[r.id] ?: emptyList(), sender = sender)
        }
    }.getOrDefault(emptyList())

    fun mmsSender(ctx: Context, mmsId: Long): String? = runCatching {
        ctx.contentResolver.query(Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"), "type=137", null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun threadFor(ctx: Context, addresses: List<String>): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, addresses.toSet()) }.getOrDefault(0L)

    fun threadFor(ctx: Context, address: String): Long = threadFor(ctx, listOf(address))

    const val ACTION_SENT = "com.rudysooo.messages.SENT"
    const val ACTION_DELIVERED = "com.rudysooo.messages.DELIVERED"

    private fun smsManager(ctx: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()

    private fun statusIntent(ctx: Context, action: String, id: Long, part: Int): PendingIntent {
        val i = Intent(ctx, SmsStatusReceiver::class.java).setAction(action).putExtra("id", id)
        val code = (id * 16 + part).toInt() * 2 + if (action == ACTION_SENT) 0 else 1
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Envoie un message : SMS simple, ou MMS s'il y a des pièces jointes ou plusieurs destinataires. */
    fun sendMessage(ctx: Context, addresses: List<String>, text: String, attachments: List<Attachment> = emptyList()): Result<Unit> {
        if (addresses.size == 1 && attachments.isEmpty()) return sendSms(ctx, addresses[0], text)
        return runCatching {
            require(addresses.isNotEmpty()) { "Aucun destinataire" }
            require(isDefault(ctx)) { "Définis Messages comme appli SMS par défaut pour envoyer des MMS" }
            val s = MmsSettings().apply {
                setUseSystemSending(true)
                setGroup(addresses.size > 1)
                setDeliveryReports(Settings(ctx).deliveryReports)
            }
            Transaction.settings = s
            val msg = Message(text.ifBlank { " " }, addresses.toTypedArray())
            attachments.forEach { msg.addMedia(it.bytes, it.mime, it.name) }
            Transaction(ctx, s).sendNewMessage(msg, threadFor(ctx, addresses))
        }
    }

    fun sendSms(ctx: Context, address: String, text: String): Result<Unit> = runCatching {
        require(address.isNotBlank()) { "Numéro vide" }
        val sm = smsManager(ctx)
        val reports = Settings(ctx).deliveryReports
        val parts = sm.divideMessage(text)
        // L'appli par défaut enregistre elle-même le message ; sinon Android s'en charge.
        val id = if (isDefault(ctx)) {
            ctx.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, text)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
            })?.lastPathSegment?.toLongOrNull()
        } else null
        val sent = id?.let { i -> ArrayList(parts.indices.map { statusIntent(ctx, ACTION_SENT, i, it) }) }
        val deliv = if (id != null && reports) ArrayList(parts.indices.map { statusIntent(ctx, ACTION_DELIVERED, id, it) }) else null
        if (parts.size > 1) sm.sendMultipartTextMessage(address, null, parts, sent, deliv)
        else sm.sendTextMessage(address, null, text, sent?.firstOrNull(), deliv?.firstOrNull())
    }

    fun setType(ctx: Context, id: Long, type: Int) {
        runCatching {
            ctx.contentResolver.update(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null,
            )
        }
    }

    fun setStatus(ctx: Context, id: Long, status: Int) {
        runCatching {
            ctx.contentResolver.update(
                ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
                ContentValues().apply { put(Telephony.Sms.STATUS, status) }, null, null,
            )
        }
    }

    fun markRead(ctx: Context, threadId: Long) {
        if (!isDefault(ctx)) return
        val v = ContentValues().apply { put("read", 1); put("seen", 1) }
        runCatching { ctx.contentResolver.update(Telephony.Sms.CONTENT_URI, v, "thread_id=? AND read=0", arrayOf(threadId.toString())) }
        runCatching {
            ctx.contentResolver.update(
                Telephony.Mms.CONTENT_URI, ContentValues().apply { put("read", 1); put("seen", 1) },
                "thread_id=? AND read=0", arrayOf(threadId.toString()),
            )
        }
    }

    fun deleteThread(ctx: Context, threadId: Long): Boolean {
        if (!isDefault(ctx)) return false
        return runCatching {
            ctx.contentResolver.delete(ContentUris.withAppendedId(Telephony.Threads.CONTENT_URI, threadId), null, null)
        }.isSuccess
    }

    fun deleteMessages(ctx: Context, msgs: Collection<Msg>): Boolean {
        if (!isDefault(ctx) || msgs.isEmpty()) return false
        return runCatching {
            val sms = msgs.filter { !it.mms }.map { it.id }
            val mms = msgs.filter { it.mms }.map { it.id }
            if (sms.isNotEmpty()) ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id IN (${sms.joinToString(",")})", null)
            if (mms.isNotEmpty()) ctx.contentResolver.delete(Telephony.Mms.CONTENT_URI, "_id IN (${mms.joinToString(",")})", null)
        }.isSuccess
    }

    fun search(ctx: Context, q: String): List<Hit> = runCatching {
        val out = ArrayList<Hit>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "body LIKE ?", arrayOf("%$q%"), "date DESC LIMIT 100",
        )?.use { c ->
            while (c.moveToNext()) {
                val a = c.getString(1) ?: ""
                out += Hit(c.getLong(0), a, displayName(ctx, a), c.getString(2) ?: "", c.getLong(3))
            }
        }
        out
    }.getOrDefault(emptyList())

    fun contacts(ctx: Context): List<Contact> = runCatching {
        val out = LinkedHashMap<String, Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null, null, "display_name COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(1) ?: continue
                val key = n.filter { it.isDigit() }.takeLast(9)
                if (key.isNotEmpty() && !out.containsKey(key)) out[key] = Contact(c.getString(0) ?: n, n)
            }
        }
        out.values.toList()
    }.getOrDefault(emptyList())
}
