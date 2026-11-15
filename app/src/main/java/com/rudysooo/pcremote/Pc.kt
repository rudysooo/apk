package com.rudysooo.pcremote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Pc(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val mac: String,
    val ip: String,
    val broadcast: String,
    val agentPort: Int = 8765,
    val token: String = "",
)

class PcStore(context: Context) {
    private val prefs = context.getSharedPreferences("pcs", Context.MODE_PRIVATE)

    fun load(): List<Pc> {
        val raw = prefs.getString("list", "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Pc(
                id = o.getString("id"),
                name = o.getString("name"),
                mac = o.getString("mac"),
                ip = o.getString("ip"),
                broadcast = o.getString("broadcast"),
                agentPort = o.optInt("agentPort", 8765),
                token = o.optString("token", ""),
            )
        }
    }

    fun save(list: List<Pc>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("name", it.name); put("mac", it.mac)
                put("ip", it.ip); put("broadcast", it.broadcast)
                put("agentPort", it.agentPort); put("token", it.token)
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
