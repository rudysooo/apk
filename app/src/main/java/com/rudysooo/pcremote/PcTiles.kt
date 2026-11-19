package com.rudysooo.pcremote

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tuiles du panneau rapide (centre de contrôle). Elles agissent sur le premier PC de la liste. */
abstract class BasePcTile : TileService() {
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    protected abstract val title: String

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    protected fun firstPc(): Pc? = PcStore(this).load().firstOrNull()

    protected fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    protected fun setTile(state: Int, subtitle: String?) {
        val t = qsTile ?: return
        t.state = state
        t.label = title
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = subtitle
        t.updateTile()
    }
}

class WakeTile : BasePcTile() {
    override val title = "Allumer PC"

    override fun onStartListening() {
        val pc = firstPc()
        if (pc == null) { setTile(Tile.STATE_INACTIVE, "Aucun PC"); return }
        setTile(Tile.STATE_INACTIVE, pc.name)
        scope.launch {
            val on = Net.isOnline(pc.ip, pc.agentPort)
            setTile(if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, if (on) "${pc.name} en ligne" else pc.name)
        }
    }

    override fun onClick() {
        val pc = firstPc() ?: run { toast("Ajoute d'abord un PC dans l'appli"); return }
        scope.launch {
            setTile(Tile.STATE_ACTIVE, "Envoi…")
            val r = Net.wake(pc.mac, pc.broadcast)
            toast(if (r.isSuccess) "${pc.name} : démarrage…" else "Échec : ${r.exceptionOrNull()?.message}")
            delay(3000)
            onStartListening()
        }
    }
}

class ShutdownTile : BasePcTile() {
    override val title = "Éteindre PC"

    override fun onStartListening() {
        val pc = firstPc()
        setTile(Tile.STATE_INACTIVE, pc?.name ?: "Aucun PC")
    }

    override fun onClick() {
        val pc = firstPc() ?: run { toast("Ajoute d'abord un PC dans l'appli"); return }
        if (pc.token.isBlank()) { toast("Configure l'extinction dans l'appli"); return }
        // Demande de déverrouiller le téléphone avant d'éteindre (évite les faux gestes)
        unlockAndRun {
            scope.launch {
                setTile(Tile.STATE_ACTIVE, "Envoi…")
                val r = Net.agent(pc, "shutdown")
                toast(r.fold({ "${pc.name} : extinction…" }, { "Échec : ${it.message}" }))
                delay(3000)
                onStartListening()
            }
        }
    }
}
