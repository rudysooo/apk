package com.rudysooo.messages

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

object Notifier {
    const val CHANNEL = "messages"
    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "com.rudysooo.messages.REPLY"
    const val ACTION_READ = "com.rudysooo.messages.MARK_READ"

    fun show(ctx: Context, title: String, addresses: List<String>, threadId: Long, body: String) {
        val settings = Settings(ctx)
        if (!settings.notifications || settings.isMuted(threadId)) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ADDRESS, addresses.firstOrNull() ?: "")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, threadId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shown = if (settings.preview) body else "Nouveau message"

        val replyIntent = Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_REPLY)
            .putExtra("addresses", addresses.toTypedArray()).putExtra("thread", threadId)
        val replyPi = PendingIntent.getBroadcast(
            ctx, threadId.toInt(), replyIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_stat, "Répondre", replyPi)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Répondre").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val readIntent = Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_READ)
            .putExtra("thread", threadId)
        val readPi = PendingIntent.getBroadcast(
            ctx, threadId.toInt(), readIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(shown)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(reply)
            .addAction(R.drawable.ic_stat, "Marquer comme lu", readPi)
            .build()
        nm.notify(threadId.toInt(), n)
    }

    fun cancel(ctx: Context, threadId: Long) {
        ctx.getSystemService(NotificationManager::class.java).cancel(threadId.toInt())
    }
}

/** Reçoit les SMS quand l'appli est l'appli SMS par défaut : les enregistre et notifie. */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val settings = Settings(context)
        for ((addr, list) in msgs.groupBy { it.originatingAddress ?: "" }) {
            if (settings.isBlocked(addr)) continue // numéro bloqué : le message est ignoré
            val body = list.joinToString("") { it.messageBody ?: "" }
            runCatching {
                context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, addr)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.DATE_SENT, list[0].timestampMillis)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                })
            }
            val tid = Sms.threadFor(context, addr)
            settings.setArchived(listOf(tid), false) // un nouveau message désarchive la conversation
            Notifier.show(context, Sms.displayName(context, addr) ?: addr, listOf(addr), tid, body)
        }
    }
}

/** Réception des MMS : la bibliothèque télécharge le MMS puis l'enregistre dans la base. */
class MmsReceiver : com.android.mms.transaction.PushReceiver()

/** Appelé quand un MMS est entièrement téléchargé et enregistré. */
class MmsReceivedReceiverImpl : com.klinker.android.send_message.MmsReceivedReceiver() {
    override fun onMessageReceived(context: Context, messageUri: android.net.Uri) {
        val id = messageUri.lastPathSegment?.toLongOrNull() ?: return
        val settings = Settings(context)
        var thread = 0L
        runCatching {
            context.contentResolver.query(messageUri, arrayOf("thread_id"), null, null, null)
                ?.use { if (it.moveToFirst()) thread = it.getLong(0) }
        }
        val sender = Sms.mmsSender(context, id) ?: ""
        if (settings.isBlocked(sender)) {
            runCatching { context.contentResolver.delete(messageUri, null, null) }
            return
        }
        val msg = Sms.messages(context, thread).firstOrNull { it.uid == "m$id" }
        val body = msg?.body?.takeIf { it.isNotBlank() } ?: if (msg?.parts?.any { it.isImage } == true) "📷 Photo" else "Message multimédia"
        settings.setArchived(listOf(thread), false)
        Notifier.show(context, Sms.displayName(context, sender) ?: sender.ifBlank { "MMS" }, listOf(sender), thread, body)
    }

    override fun onError(context: Context, error: String) {}
}

/** Résultat d'envoi et accusé de réception d'un SMS. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        if (id < 0) return
        when (intent.action) {
            Sms.ACTION_SENT ->
                if (resultCode == Activity.RESULT_OK) Sms.setType(context, id, Telephony.Sms.MESSAGE_TYPE_SENT)
                else Sms.setType(context, id, Telephony.Sms.MESSAGE_TYPE_FAILED)
            Sms.ACTION_DELIVERED -> Sms.setStatus(context, id, Telephony.Sms.STATUS_COMPLETE)
        }
    }
}

/** Boutons « Répondre » et « Marquer comme lu » de la notification. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra("thread", 0)
        when (intent.action) {
            Notifier.ACTION_READ -> { Sms.markRead(context, thread); Notifier.cancel(context, thread) }
            Notifier.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                val addresses = intent.getStringArrayExtra("addresses")?.toList().orEmpty().filter { it.isNotBlank() }
                if (!text.isNullOrBlank() && addresses.isNotEmpty()) {
                    Sms.sendMessage(context, addresses, text)
                    Sms.markRead(context, thread)
                }
                Notifier.cancel(context, thread)
            }
        }
    }
}

/** Réponse rapide depuis l'écran d'appel (« Répondre par message »). */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!address.isNullOrBlank() && !text.isNullOrBlank()) Sms.sendMessage(this, listOf(address), text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
