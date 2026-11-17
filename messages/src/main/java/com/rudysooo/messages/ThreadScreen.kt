package com.rudysooo.messages

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Row2 {
    data class Day(val label: String) : Row2
    data class M(val msg: Msg, val first: Boolean, val last: Boolean) : Row2
}

private fun buildRows(msgs: List<Msg>): List<Row2> {
    val out = ArrayList<Row2>()
    msgs.forEachIndexed { i, m ->
        val prev = msgs.getOrNull(i - 1); val next = msgs.getOrNull(i + 1)
        if (prev == null || dayKey(prev.date) != dayKey(m.date)) out += Row2.Day(dayLabel(m.date))
        val sameAsPrev = prev != null && dayKey(prev.date) == dayKey(m.date) && prev.incoming == m.incoming && m.date - prev.date < 5 * 60_000
        val sameAsNext = next != null && dayKey(next.date) == dayKey(m.date) && next.incoming == m.incoming && next.date - m.date < 5 * 60_000
        out += Row2.M(m, !sameAsPrev, !sameAsNext)
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    settings: Settings,
    version: Int,
    threadId: Long,
    address: String,
    title: String,
    draft: String,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var text by remember { mutableStateOf(draft) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rows = remember(msgs) { buildRows(msgs) }
    val isNumber = title == address

    LaunchedEffect(version, threadId) {
        msgs = withContext(Dispatchers.IO) {
            Sms.markRead(ctx, threadId)
            Sms.messages(ctx, threadId)
        }
        Notifier.cancel(ctx, threadId)
    }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex) }

    fun send(body: String, then: () -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { Sms.send(ctx, address, body) }
            r.onFailure { Toast.makeText(ctx, "Échec : ${it.message}", Toast.LENGTH_LONG).show() }
            then(); onChanged()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(title, 38.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                            if (!isNumber) Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                actions = {
                    IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))) }) {
                        Icon(Icons.Default.Call, "Appeler")
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
                        DropdownMenu(menu, { menu = false }) {
                            val pinned = settings.isPinned(threadId); val muted = settings.isMuted(threadId)
                            val blocked = settings.isBlocked(address)
                            DropdownMenuItem(
                                text = { Text(if (pinned) "Désépingler" else "Épingler") },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = { settings.setPinned(listOf(threadId), !pinned); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (muted) "Réactiver les notifications" else "Mettre en sourdine") },
                                leadingIcon = { Icon(if (muted) Icons.Default.Notifications else Icons.Default.NotificationsOff, null) },
                                onClick = { settings.setMuted(listOf(threadId), !muted); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (blocked) "Débloquer le numéro" else "Bloquer le numéro") },
                                leadingIcon = { Icon(Icons.Default.Block, null) },
                                onClick = {
                                    if (blocked) settings.unblock(address) else settings.block(address)
                                    menu = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Supprimer la conversation", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(12.dp, 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    TextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("Message texte") },
                        maxLines = 6, shape = RoundedCornerShape(26.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (text.length > 120) {
                        val parts = if (text.length <= 160) 1 else (text.length + 152) / 153
                        Text(
                            "${text.length} caractères · $parts SMS", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = { val b = text.trim(); if (b.isNotEmpty()) send(b) { text = "" } },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.Default.Send, "Envoyer") }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp, 8.dp)) {
            items(rows, key = { when (it) { is Row2.Day -> "d${it.label}"; is Row2.M -> "m${it.msg.id}" } }) { r ->
                when (r) {
                    is Row2.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            r.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(12.dp, 4.dp),
                        )
                    }
                    is Row2.M -> Bubble(
                        r.msg, r.first, r.last,
                        isLastOutgoing = r.msg.id == msgs.lastOrNull { !it.incoming }?.id,
                        canDelete = Sms.isDefault(ctx),
                        onRetry = {
                            send(r.msg.body) { Sms.deleteMessages(ctx, listOf(r.msg.id)) }
                        },
                        onDelete = { Sms.deleteMessages(ctx, listOf(r.msg.id)); onChanged() },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la conversation ?") },
            text = { Text("Tous les messages avec $title seront supprimés.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (Sms.deleteThread(ctx, threadId)) { onChanged(); onBack() }
                    else Toast.makeText(ctx, "Définis d'abord Messages comme appli SMS par défaut", Toast.LENGTH_LONG).show()
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Msg, first: Boolean, last: Boolean, isLastOutgoing: Boolean, canDelete: Boolean,
    onRetry: () -> Unit, onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val out = !m.incoming
    val big = 22.dp; val small = 6.dp
    val shape = if (out) RoundedCornerShape(big, big, if (last) small else big, big)
    else RoundedCornerShape(big, big, big, if (last) small else big)
    val bg = when {
        m.failed -> MaterialTheme.colorScheme.errorContainer
        out -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        m.failed -> MaterialTheme.colorScheme.onErrorContainer
        out -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.fillMaxWidth().padding(top = if (first) 8.dp else 2.dp),
        horizontalAlignment = if (out) Alignment.End else Alignment.Start,
    ) {
        Box {
            Text(
                m.body, color = fg, fontSize = 16.sp,
                modifier = Modifier.widthIn(max = 300.dp).background(bg, shape)
                    .combinedClickable(onClick = { if (m.failed) onRetry() else details = !details }, onLongClick = { open = true })
                    .padding(14.dp, 9.dp),
            )
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(
                    text = { Text("Copier") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { clip.setText(AnnotatedString(m.body)); open = false; Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show() },
                )
                DropdownMenuItem(
                    text = { Text("Transférer") }, leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = {
                        open = false
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, m.body), null))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = {
                        open = false
                        if (canDelete) onDelete()
                        else Toast.makeText(ctx, "Définis d'abord Messages comme appli SMS par défaut", Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
        val caption = when {
            m.failed -> "Échec d'envoi · appuie pour réessayer"
            out && m.pending -> "Envoi…"
            details -> fullDate(m.date)
            last && out && isLastOutgoing -> fmtTime(m.date) + if (m.delivered) " · Remis" else ""
            last -> fmtTime(m.date)
            else -> null
        }
        if (caption != null) {
            Text(
                caption, fontSize = 11.sp,
                color = if (m.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}
