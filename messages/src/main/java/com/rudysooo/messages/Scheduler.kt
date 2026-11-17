package com.rudysooo.messages

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject

data class Scheduled(val id: Long, val threadId: Long, val addresses: List<String>, val body: String, val at: Long)

/** Envoi programmé : la liste est enregistrée sur le téléphone et réarmée au redémarrage. */
object Scheduler {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("scheduled", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Scheduled> = runCatching {
        val arr = JSONArray(prefs(ctx).getString("list", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val a = o.getJSONArray("addresses")
            Scheduled(o.getLong("id"), o.getLong("thread"), (0 until a.length()).map { i -> a.getString(i) }, o.getString("body"), o.getLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Scheduled>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("thread", it.threadId).put("body", it.body).put("at", it.at)
                .put("addresses", JSONArray(it.addresses)))
        }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
    }

    fun forThread(ctx: Context, threadId: Long) = all(ctx).filter { it.threadId == threadId }.sortedBy { it.at }

    private fun pending(ctx: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, ScheduledSendReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun arm(ctx: Context, s: Scheduled) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.at, pending(ctx, s.id))
    }

    fun add(ctx: Context, threadId: Long, addresses: List<String>, body: String, at: Long) {
        val s = Scheduled(System.currentTimeMillis() % 1_000_000_000L, threadId, addresses, body, at)
        save(ctx, all(ctx) + s)
        arm(ctx, s)
    }

    fun cancel(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, id))
        save(ctx, all(ctx).filter { it.id != id })
    }

    fun rearmAll(ctx: Context) = all(ctx).forEach { arm(ctx, it.copy(at = maxOf(it.at, System.currentTimeMillis() + 1000))) }
}

class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1)
        val item = Scheduler.all(context).firstOrNull { it.id == id } ?: return
        val pending = goAsync()
        Thread {
            try {
                Sms.sendMessage(context, item.addresses, item.body)
                Scheduler.cancel(context, id)
            } finally { pending.finish() }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Scheduler.rearmAll(context)
    }
}
