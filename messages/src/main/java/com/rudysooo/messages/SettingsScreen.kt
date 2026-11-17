package com.rudysooo.messages

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title.uppercase(), fontWeight = FontWeight.Medium, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp, 20.dp, 24.dp, 8.dp),
    )
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) { Column(Modifier.padding(vertical = 4.dp), content = content) }
}

@Composable
private fun Row3(
    title: String, subtitle: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(20.dp, 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row3(title, subtitle, onClick = { onChange(!checked) }) { Switch(checked, onChange) }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    isDefault: Boolean,
    onRequestDefault: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var addBlock by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Réglages", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Section("Appli SMS") {
                Row3(
                    "Appli SMS par défaut",
                    if (isDefault) "Messages est ton appli SMS par défaut" else "Messages n'est pas encore l'appli par défaut",
                    onClick = if (isDefault) null else onRequestDefault,
                ) {
                    if (isDefault) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    else TextButton(onRequestDefault) { Text("Définir") }
                }
                SwitchRow("Accusés de réception", "Savoir quand un SMS est remis (peut être facturé par certains opérateurs)",
                    settings.deliveryReports, settings::changeDeliveryReports)
            }

            Section("Apparence") {
                Column(Modifier.padding(20.dp, 12.dp)) {
                    Text("Thème", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Système", "Clair", "Sombre").forEachIndexed { i, label ->
                            FilterChip(settings.themeMode == i, { settings.setTheme(i) }, { Text(label) })
                        }
                    }
                }
                Column(Modifier.padding(20.dp, 12.dp)) {
                    Text("Couleur d'accent", fontSize = 16.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Accents.forEach { c ->
                            if (c == 0 && Build.VERSION.SDK_INT < 31) return@forEach
                            val sel = settings.accent == c
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(
                                        if (c == 0) MaterialTheme.colorScheme.surfaceContainerHigh else Color(c)
                                    )
                                    .border(if (sel) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    .clickable { settings.changeAccent(c) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (c == 0) Icon(Icons.Default.Palette, "Material You", Modifier.size(22.dp))
                                else if (sel) Icon(Icons.Default.Check, null, tint = Color.White)
                            }
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        Text("La première pastille reprend les couleurs de ton fond d'écran (Material You).",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Column(Modifier.padding(20.dp, 12.dp)) {
                    Text("Taille du texte", fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Petite", "Normale", "Grande").forEachIndexed { i, label ->
                            FilterChip(settings.textSize == i, { settings.changeTextSize(i) }, { Text(label) })
                        }
                    }
                }
            }

            Section("Notifications") {
                SwitchRow("Notifications", "Prévenir à l'arrivée d'un message", settings.notifications, settings::changeNotifications)
                SwitchRow("Aperçu du message", "Afficher le texte dans la notification", settings.preview, settings::changePreview)
                Row3("Son et vibration", "Réglages du canal dans Android", onClick = {
                    val i = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Notifier.CHANNEL)
                    ctx.startActivity(i)
                })
            }

            Section("Numéros bloqués") {
                if (settings.blocked.isEmpty()) Row3("Aucun numéro bloqué", "Les SMS de ces numéros seront ignorés")
                settings.blocked.sorted().forEach { n ->
                    Row3(n, onClick = null) { IconButton({ settings.unblock(n) }) { Icon(Icons.Default.Close, "Débloquer") } }
                }
                Row3("Bloquer un numéro", onClick = { addBlock = true }) { Icon(Icons.Default.Add, null) }
            }

            Section("À propos") {
                Row3(
                    "Messages 1.1",
                    "SMS et MMS (photos, vidéos, groupes), archivage et envoi programmé. L'envoi des MMS dépend de ton opérateur " +
                        "et du réglage « données mobiles ». Le RCS n'est pas disponible : Google ne l'autorise que pour sa propre appli et les opérateurs.",
                )
            }
        }
    }

    if (addBlock) {
        AlertDialog(
            onDismissRequest = { addBlock = false },
            title = { Text("Bloquer un numéro") },
            text = {
                OutlinedTextField(number, { number = it }, singleLine = true, label = { Text("Numéro de téléphone") })
            },
            confirmButton = {
                TextButton({ settings.block(number.trim()); number = ""; addBlock = false }, enabled = number.isNotBlank()) { Text("Bloquer") }
            },
            dismissButton = { TextButton({ addBlock = false }) { Text("Annuler") } },
        )
    }
}
