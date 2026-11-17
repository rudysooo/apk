package com.rudysooo.messages

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

object Notifier {
    const val CHANNEL = "messages"
    const val KEY_REPLY = "reply"
    const val ACTION_REPLY = "com.rudysooo.messages.REPLY"
    const val ACTION_READ = "com.rudysooo.messages.MARK_READ"

    fun show(ctx: Context, address: String, threadId: Long, body: String) {
        val settings = Settings(ctx)
        if (!settings.notifications || settings.isMuted(threadId)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ADDRESS, address)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, threadId.toInt(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val shown = if (settings.preview) body else "Nouveau message"
        val title = Sms.displayName(ctx, address) ?: address

        val replyIntent = Intent(ctx, NotificationActionReceiver::class.java).setAction(ACTION_REPLY)
            .putExtra("address", address).putExtra("thread", threadId)
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
            Notifier.show(context, addr, Sms.threadFor(context, addr), body)
        }
    }
}

/** Obligatoire pour être appli SMS par défaut. Les MMS ne sont pas gérés dans cette version. */
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
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
                val address = intent.getStringExtra("address")
                if (!text.isNullOrBlank() && !address.isNullOrBlank()) {
                    Sms.send(context, address, text)
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
        if (!address.isNullOrBlank() && !text.isNullOrBlank()) Sms.send(this, address, text)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
