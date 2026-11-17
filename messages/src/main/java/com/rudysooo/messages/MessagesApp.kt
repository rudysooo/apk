package com.rudysooo.messages

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.klinker.android.send_message.Transaction

class MessagesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Réglage utilisé par la bibliothèque MMS, aussi à la réception (process lancé par un MMS entrant)
        Transaction.settings = com.klinker.android.send_message.Settings().apply { setUseSystemSending(true) }
        // Créer le canal tout de suite : Android demande alors l'autorisation de notifier
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(Notifier.CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        }
    }
}
