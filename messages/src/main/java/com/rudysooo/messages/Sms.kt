package com.rudysooo.messages

import android.Manifest
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat

data class Conv(
    val threadId: Long,
    val address: String,
    val name: String?,
    val snippet: String,
    val date: Long,
    val unread: Int,
) {
    val title get() = name ?: address.ifBlank { "Inconnu" }
}

/** type : 1 reçu, 2 envoyé, 4 en cours d'envoi, 5 échec */
data class Msg(val id: Long, val body: String, val date: Long, val type: Int, val status: Int = -1) {
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

    fun isDefault(ctx: Context) = Telephony.Sms.getDefaultSmsPackage(ctx) == ctx.packageName

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

    fun conversations(ctx: Context): List<Conv> {
        val latest = LinkedHashMap<Long, Conv>()
        val unread = HashMap<Long, Int>()
        runCatching {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
                ),
                null, null, "date DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val tid = c.getLong(0)
                    if (c.getInt(4) == 1 && c.getInt(5) == 0) unread[tid] = (unread[tid] ?: 0) + 1
                    if (!latest.containsKey(tid)) {
                        latest[tid] = Conv(tid, c.getString(1) ?: "", null, c.getString(2) ?: "", c.getLong(3), 0)
                    }
                }
            }
        }
        return latest.values.map { it.copy(name = displayName(ctx, it.address), unread = unread[it.threadId] ?: 0) }
    }

    fun messages(ctx: Context, threadId: Long): List<Msg> = runCatching {
        val out = ArrayList<Msg>()
        ctx.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.STATUS),
            "thread_id=?", arrayOf(threadId.toString()), "date ASC",
        )?.use { c ->
            while (c.moveToNext()) out += Msg(c.getLong(0), c.getString(1) ?: "", c.getLong(2), c.getInt(3), c.getInt(4))
        }
        out
    }.getOrDefault(emptyList())

    fun threadFor(ctx: Context, address: String): Long =
        runCatching { Telephony.Threads.getOrCreateThreadId(ctx, address) }.getOrDefault(0L)

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

    fun send(ctx: Context, address: String, text: String): Result<Unit> = runCatching {
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

    fun deleteMessages(ctx: Context, ids: Collection<Long>): Boolean {
        if (!isDefault(ctx) || ids.isEmpty()) return false
        return runCatching {
            ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "_id IN (${ids.joinToString(",")})", null)
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

    fun markRead(ctx: Context, threadId: Long) {
        if (!isDefault(ctx)) return
        runCatching {
            ctx.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                ContentValues().apply { put(Telephony.Sms.READ, 1); put(Telephony.Sms.SEEN, 1) },
                "thread_id=? AND read=0", arrayOf(threadId.toString()),
            )
        }
    }

    fun deleteThread(ctx: Context, threadId: Long): Boolean {
        if (!isDefault(ctx)) return false
        return runCatching {
            ctx.contentResolver.delete(Telephony.Sms.CONTENT_URI, "thread_id=?", arrayOf(threadId.toString()))
        }.isSuccess
    }
}
