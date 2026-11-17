package com.rudysooo.messages

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

private sealed interface Row2 {
    data class Day(val label: String) : Row2
    data class M(val msg: Msg, val first: Boolean, val last: Boolean) : Row2
}

private fun buildRows(msgs: List<Msg>): List<Row2> {
    val out = ArrayList<Row2>()
    msgs.forEachIndexed { i, m ->
        val prev = msgs.getOrNull(i - 1); val next = msgs.getOrNull(i + 1)
        if (prev == null || dayKey(prev.date) != dayKey(m.date)) out += Row2.Day(dayLabel(m.date))
        fun same(a: Msg, b: Msg) = dayKey(a.date) == dayKey(b.date) && a.incoming == b.incoming && a.sender == b.sender &&
            kotlin.math.abs(b.date - a.date) < 5 * 60_000
        out += Row2.M(m, !(prev != null && same(prev, m)), !(next != null && same(m, next)))
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ThreadScreen(
    settings: Settings,
    version: Int,
    threadId: Long,
    addresses: List<String>,
    title: String,
    draft: String,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val isGroup = addresses.size > 1
    val address = addresses.firstOrNull() ?: ""
    var msgs by remember { mutableStateOf<List<Msg>>(emptyList()) }
    var text by remember { mutableStateOf(draft) }
    var attachments by remember { mutableStateOf<List<Attachment>>(emptyList()) }
    var schedTick by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<MsgPart?>(null) }
    val listState = rememberLazyListState()
    val rows = remember(msgs) { buildRows(msgs) }
    val scheduled = remember(schedTick, threadId) { Scheduler.forThread(ctx, threadId) }

    LaunchedEffect(version, threadId) {
        msgs = withContext(Dispatchers.IO) {
            Sms.markRead(ctx, threadId)
            Sms.messages(ctx, threadId)
        }
        Notifier.cancel(ctx, threadId)
    }
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        scope.launch {
            for (u in uris) {
                val r = withContext(Dispatchers.IO) { Attachments.load(ctx, u) }
                r.onSuccess { attachments = attachments + it }
                    .onFailure { Toast.makeText(ctx, it.message ?: "Fichier refusé", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()

    fun send(body: String, atts: List<Attachment>, then: () -> Unit = {}) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { Sms.sendMessage(ctx, addresses, body, atts) }
            r.onFailure { toast("Échec : ${it.message}") }
            then(); onChanged()
        }
    }

    fun doSend() {
        val body = text.trim()
        if (body.isEmpty() && attachments.isEmpty()) return
        if ((attachments.isNotEmpty() || isGroup) && !Sms.isDefault(ctx)) {
            toast("Définis Messages comme appli SMS par défaut pour envoyer des MMS"); return
        }
        val atts = attachments
        text = ""; attachments = emptyList()
        send(body, atts)
    }

    fun schedule() {
        val body = text.trim()
        if (body.isEmpty()) { toast("Écris d'abord le message à programmer"); return }
        if (attachments.isNotEmpty()) { toast("Les pièces jointes ne peuvent pas être programmées"); return }
        val now = Calendar.getInstance()
        DatePickerDialog(ctx, { _, y, m, d ->
            TimePickerDialog(ctx, { _, h, min ->
                val at = Calendar.getInstance().apply { set(y, m, d, h, min, 0) }.timeInMillis
                if (at <= System.currentTimeMillis()) { toast("Choisis une heure dans le futur"); return@TimePickerDialog }
                Scheduler.add(ctx, threadId, addresses, body, at)
                text = ""; schedTick++
                toast("Message programmé pour ${dayLabel(at)} à ${fmtTime(at)}")
            }, now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show()
        }, now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show()
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
                            if (isGroup) Text("${addresses.size} participants", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else if (title != address) Text(address, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    }
                },
                actions = {
                    if (!isGroup) IconButton({ ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$address"))) }) {
                        Icon(Icons.Default.Call, "Appeler")
                    }
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "Plus") }
                        DropdownMenu(menu, { menu = false }) {
                            val pinned = settings.isPinned(threadId); val muted = settings.isMuted(threadId)
                            val archived = settings.isArchived(threadId); val blocked = settings.isBlocked(address)
                            DropdownMenuItem(
                                text = { Text("Programmer l'envoi") },
                                leadingIcon = { Icon(Icons.Default.Schedule, null) },
                                onClick = { menu = false; schedule() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (pinned) "Désépingler" else "Épingler") },
                                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                                onClick = { settings.setPinned(listOf(threadId), !pinned); menu = false },
                            )
                            DropdownMenuItem(
                                text = { Text(if (archived) "Désarchiver" else "Archiver") },
                                leadingIcon = { Icon(if (archived) Icons.Default.Unarchive else Icons.Default.Archive, null) },
                                onClick = { settings.setArchived(listOf(threadId), !archived); menu = false; if (!archived) onBack() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (muted) "Réactiver les notifications" else "Mettre en sourdine") },
                                leadingIcon = { Icon(if (muted) Icons.Default.Notifications else Icons.Default.NotificationsOff, null) },
                                onClick = { settings.setMuted(listOf(threadId), !muted); menu = false },
                            )
                            if (!isGroup) DropdownMenuItem(
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
            Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
                scheduled.forEach { s ->
                    AssistChip(
                        onClick = {},
                        modifier = Modifier.padding(horizontal = 12.dp),
                        leadingIcon = { Icon(Icons.Default.Schedule, null, Modifier.size(18.dp)) },
                        label = { Text("${dayLabel(s.at)} ${fmtTime(s.at)} · ${s.body.take(30)}", maxLines = 1) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, "Annuler", Modifier.size(18.dp).clickable { Scheduler.cancel(ctx, s.id); schedTick++ })
                        },
                    )
                }
                if (attachments.isNotEmpty()) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        attachments.forEach { a ->
                            Box {
                                if (a.thumb != null) Image(
                                    a.thumb.asImageBitmap(), null, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop,
                                ) else Box(
                                    Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Default.Movie, null) }
                                Icon(
                                    Icons.Default.Cancel, "Retirer",
                                    Modifier.align(Alignment.TopEnd).size(22.dp).clickable { attachments = attachments - a },
                                    tint = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(
                        { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                        Modifier.size(48.dp),
                    ) { Icon(Icons.Default.AddPhotoAlternate, "Joindre", tint = MaterialTheme.colorScheme.primary) }
                    Column(Modifier.weight(1f)) {
                        TextField(
                            value = text, onValueChange = { text = it },
                            placeholder = { Text(if (isGroup || attachments.isNotEmpty()) "Message MMS" else "Message texte") },
                            maxLines = 6, shape = RoundedCornerShape(26.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (text.length > 120 && !isGroup && attachments.isEmpty()) {
                            val parts = if (text.length <= 160) 1 else (text.length + 152) / 153
                            Text(
                                "${text.length} caractères · $parts SMS", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val canSend = text.isNotBlank() || attachments.isNotEmpty()
                    // Appui court : envoyer. Appui long : programmer l'envoi.
                    Box(
                        Modifier.size(52.dp).clip(CircleShape)
                            .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(enabled = canSend, onClick = ::doSend, onLongClick = ::schedule),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Send, "Envoyer",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp, 8.dp)) {
            items(rows, key = { when (it) { is Row2.Day -> "d${it.label}"; is Row2.M -> it.msg.uid } }) { r ->
                when (r) {
                    is Row2.Day -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                        Text(
                            r.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(12.dp, 4.dp),
                        )
                    }
                    is Row2.M -> Bubble(
                        r.msg, r.first, r.last, isGroup,
                        isLastOutgoing = r.msg.uid == msgs.lastOrNull { !it.incoming }?.uid,
                        canDelete = Sms.isDefault(ctx),
                        onRetry = { send(r.msg.body, emptyList()) { Sms.deleteMessages(ctx, listOf(r.msg)) } },
                        onDelete = { Sms.deleteMessages(ctx, listOf(r.msg)); onChanged() },
                        onImage = { viewer = it },
                    )
                }
            }
        }
    }

    viewer?.let { part ->
        val bmp by produceState<Bitmap?>(null, part.id) { value = withContext(Dispatchers.IO) { Attachments.loadPart(ctx, part.uri, 2000) } }
        Dialog({ viewer = null }, DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { viewer = null }, contentAlignment = Alignment.Center) {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    ?: CircularProgressIndicator()
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
                    else toast("Définis d'abord Messages comme appli SMS par défaut")
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun PartView(part: MsgPart, shape: RoundedCornerShape, fg: Color, onImage: (MsgPart) -> Unit) {
    val ctx = LocalContext.current
    if (part.isImage) {
        val bmp by produceState<Bitmap?>(null, part.id) { value = withContext(Dispatchers.IO) { Attachments.loadPart(ctx, part.uri, 700) } }
        val b = bmp
        if (b != null) Image(
            b.asImageBitmap(), null,
            Modifier.widthIn(max = 260.dp).clip(shape).clickable { onImage(part) }, contentScale = ContentScale.FillWidth,
        ) else Box(Modifier.size(160.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    } else {
        val (icon, label) = when {
            part.mime.startsWith("video/") -> Icons.Default.Movie to "Vidéo"
            part.mime.startsWith("audio/") -> Icons.Default.MusicNote to "Audio"
            else -> Icons.Default.AttachFile to part.name.ifBlank { "Pièce jointe" }
        }
        Row(
            Modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurface); Spacer(Modifier.width(8.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Msg, first: Boolean, last: Boolean, isGroup: Boolean, isLastOutgoing: Boolean, canDelete: Boolean,
    onRetry: () -> Unit, onDelete: () -> Unit, onImage: (MsgPart) -> Unit,
) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val out = !m.incoming
    val big = 22.dp; val small = 6.dp
    val shape = if (out) RoundedCornerShape(big, big, if (last) small else big, big)
    else RoundedCornerShape(big, big, big, if (last) small else big)
    val imgShape = RoundedCornerShape(18.dp)
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
        if (isGroup && !out && first) {
            val who = m.sender?.let { Sms.displayName(ctx, it) ?: it }
            if (who != null) Text(who, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp))
        }
        Box {
            Column(
                Modifier.combinedClickable(onClick = { if (m.failed) onRetry() else details = !details }, onLongClick = { open = true }),
                horizontalAlignment = if (out) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                m.parts.forEach { PartView(it, imgShape, fg, onImage) }
                if (m.body.isNotBlank()) Text(
                    m.body, color = fg, fontSize = 16.sp,
                    modifier = Modifier.widthIn(max = 300.dp).background(bg, shape).padding(14.dp, 9.dp),
                )
            }
            DropdownMenu(open, { open = false }) {
                if (m.body.isNotBlank()) DropdownMenuItem(
                    text = { Text("Copier") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                    onClick = { clip.setText(AnnotatedString(m.body)); open = false; Toast.makeText(ctx, "Copié", Toast.LENGTH_SHORT).show() },
                )
                if (m.body.isNotBlank()) DropdownMenuItem(
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
