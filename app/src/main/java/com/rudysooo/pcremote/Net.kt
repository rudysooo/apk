package com.rudysooo.pcremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

data class Found(val ip: String, val host: String?, val mac: String?)

object Net {

    /** "70-85-C2-9B-A0-CD", "70:85:c2:9b:a0:cd" ou "7085C29BA0CD" -> 6 octets, ou null. */
    fun parseMac(input: String): ByteArray? {
        val hex = input.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun formatMac(input: String): String =
        input.filter { it.isLetterOrDigit() }.uppercase().chunked(2).joinToString(":")

    fun isIpv4(s: String): Boolean {
        val p = s.trim().split(".")
        return p.size == 4 && p.all { it.toIntOrNull() in 0..255 }
    }

    /** Envoie le paquet magique (6x FF + 16x MAC) en UDP broadcast, port 9 et 7. */
    suspend fun wake(mac: String, broadcast: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val macBytes = parseMac(mac) ?: error("Adresse MAC invalide")
            val payload = ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { macBytes[it % 6] }
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val targets = listOf(broadcast, "255.255.255.255").distinct()
                for (target in targets) for (port in intArrayOf(9, 7)) {
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(target), port))
                    }
                }
            }
        }
    }

    /** Le PC répond-il ? (ping ou, à défaut, quelques ports Windows courants) */
    suspend fun isOnline(ip: String, agentPort: Int = 8765): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (InetAddress.getByName(ip).isReachable(800)) return@runCatching true
            listOf(agentPort, 445, 135, 3389).any { port ->
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(ip, port), 400); true }
                }.getOrDefault(false)
            }
        }.getOrDefault(false)
    }

    /** Appelle l'agent installé sur le PC (ex: action = "shutdown"). */
    suspend fun agent(pc: Pc, action: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("http://${pc.ip}:${pc.agentPort}/$action?token=" + URLEncoder.encode(pc.token, "UTF-8"))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 5000
            try {
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                when (code) {
                    in 200..299 -> body.ifBlank { "OK" }
                    401, 403 -> error("Jeton refusé par le PC")
                    else -> error("Erreur $code")
                }
            } finally {
                c.disconnect()
            }
        }
    }

    data class Lan(val myIp: String, val prefix: Int, val broadcast: String)

    fun currentLan(context: Context): Lan? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network = cm.activeNetwork ?: return null
        val lp = cm.getLinkProperties(network) ?: return null
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val ip = la.address.hostAddress ?: return null
        val prefix = la.prefixLength
        val ipInt = ip.split(".").fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val bc = (ipInt and mask) or (mask.inv() and 0xFFFFFFFFL)
        val bcStr = (3 downTo 0).joinToString(".") { ((bc shr (it * 8)) and 0xFF).toString() }
        return Lan(ip, prefix, bcStr)
    }

    /** Balayage du /24 : renvoie les appareils qui répondent. */
    suspend fun scan(context: Context, onProgress: (Float) -> Unit = {}): List<Found> {
        val lan = currentLan(context) ?: return emptyList()
        val base = lan.myIp.substringBeforeLast(".")
        var done = 0
        val found = coroutineScope {
            (1..254).map { i ->
                async(Dispatchers.IO) {
                    val ip = "$base.$i"
                    val ok = runCatching {
                        InetAddress.getByName(ip).isReachable(600) ||
                            listOf(445, 135, 3389, 8765, 80).any { port ->
                                runCatching { Socket().use { it.connect(InetSocketAddress(ip, port), 250); true } }
                                    .getOrDefault(false)
                            }
                    }.getOrDefault(false)
                    synchronized(this@Net) { done++; onProgress(done / 254f) }
                    if (ok) ip else null
                }
            }.awaitAll().filterNotNull()
        }
        val arp = readArp()
        return withContext(Dispatchers.IO) {
            found.filter { it != lan.myIp }.map { ip ->
                val host = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
                    ?.takeIf { it != ip }
                Found(ip, host, arp[ip])
            }
        }
    }

    /** Android 10+ bloque souvent ce fichier : on essaie, sans garantie. */
    private fun readArp(): Map<String, String> = runCatching {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size >= 4 && p[3] != "00:00:00:00:00:00") p[0] to p[3].uppercase() else null
        }.toMap()
    }.getOrDefault(emptyMap())
}
