package com.rudysooo.messages

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Réglages de l'appli, enregistrés sur le téléphone. Utilisable aussi hors interface (récepteurs). */
class Settings(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 0 = suivre le système, 1 = clair, 2 = sombre */
    var themeMode by mutableStateOf(sp.getInt("theme", 0)); private set
    /** 0 = couleurs du système (Material You), sinon couleur ARGB */
    var accent by mutableStateOf(sp.getInt("accent", 0xFF2B6CF6.toInt())); private set
    /** 0 = petit, 1 = normal, 2 = grand */
    var textSize by mutableStateOf(sp.getInt("textSize", 1)); private set
    var notifications by mutableStateOf(sp.getBoolean("notifications", true)); private set
    var preview by mutableStateOf(sp.getBoolean("preview", true)); private set
    var deliveryReports by mutableStateOf(sp.getBoolean("delivery", false)); private set
    var pinned by mutableStateOf(sp.getStringSet("pinned", emptySet())!!.toSet()); private set
    var muted by mutableStateOf(sp.getStringSet("muted", emptySet())!!.toSet()); private set
    var archived by mutableStateOf(sp.getStringSet("archived", emptySet())!!.toSet()); private set
    var blocked by mutableStateOf(sp.getStringSet("blocked", emptySet())!!.toSet()); private set

    val textScale get() = when (textSize) { 0 -> 0.9f; 2 -> 1.15f; else -> 1f }

    fun setTheme(v: Int) { themeMode = v; sp.edit().putInt("theme", v).apply() }
    fun changeAccent(v: Int) { accent = v; sp.edit().putInt("accent", v).apply() }
    fun changeTextSize(v: Int) { textSize = v; sp.edit().putInt("textSize", v).apply() }
    fun changeNotifications(v: Boolean) { notifications = v; sp.edit().putBoolean("notifications", v).apply() }
    fun changePreview(v: Boolean) { preview = v; sp.edit().putBoolean("preview", v).apply() }
    fun changeDeliveryReports(v: Boolean) { deliveryReports = v; sp.edit().putBoolean("delivery", v).apply() }

    fun isPinned(threadId: Long) = threadId.toString() in pinned
    fun isMuted(threadId: Long) = threadId.toString() in muted

    fun setPinned(ids: Collection<Long>, on: Boolean) {
        pinned = if (on) pinned + ids.map { it.toString() } else pinned - ids.map { it.toString() }.toSet()
        sp.edit().putStringSet("pinned", pinned).apply()
    }

    fun setMuted(ids: Collection<Long>, on: Boolean) {
        muted = if (on) muted + ids.map { it.toString() } else muted - ids.map { it.toString() }.toSet()
        sp.edit().putStringSet("muted", muted).apply()
    }

    fun isArchived(threadId: Long) = threadId.toString() in archived

    fun setArchived(ids: Collection<Long>, on: Boolean) {
        archived = if (on) archived + ids.map { it.toString() } else archived - ids.map { it.toString() }.toSet()
        sp.edit().putStringSet("archived", archived).apply()
    }

    private fun norm(n: String) = n.filter { it.isDigit() }.takeLast(9)

    fun isBlocked(address: String) = address.isNotBlank() && blocked.any { norm(it) == norm(address) }

    fun block(address: String) {
        if (address.isBlank() || isBlocked(address)) return
        blocked = blocked + address
        sp.edit().putStringSet("blocked", blocked).apply()
    }

    fun unblock(address: String) {
        blocked = blocked.filterNot { norm(it) == norm(address) }.toSet()
        sp.edit().putStringSet("blocked", blocked).apply()
    }
}
