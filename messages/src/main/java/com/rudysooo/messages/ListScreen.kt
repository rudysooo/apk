package com.rudysooo.messages

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ListScreen(
    settings: Settings,
    convs: List<Conv>,
    loaded: Boolean,
    isDefault: Boolean,
    onRequestDefault: () -> Unit,
    onOpen: (Conv) -> Unit,
    onOpenHit: (Hit) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        hits = withContext(Dispatchers.IO) { Sms.search(ctx, query.trim()) }
    }

    val sorted = remember(convs, settings.pinned, settings.archived, showArchived) {
        convs.filter { settings.isArchived(it.threadId) == showArchived }.sortedWith(compareByDescending<Conv> { settings.isPinned(it.threadId) }.thenByDescending { it.date })
    }
    val shown = if (query.isBlank()) sorted else sorted.filter {
        it.title.contains(query, true) || it.address.contains(query, true)
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (selecting) {
                val allPinned = selected.all { settings.isPinned(it) }
                val allMuted = selected.all { settings.isMuted(it) }
                TopAppBar(
                    title = { Text("${selected.size} sélectionnée(s)", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, "Annuler") } },
                    actions = {
                        IconButton({ selected = convs.map { it.threadId }.toSet() }) { Icon(Icons.Default.SelectAll, "Tout") }
                        IconButton({ settings.setPinned(selected, !allPinned); selected = emptySet() }) {
                            Icon(Icons.Default.PushPin, "Épingler")
                        }
                        IconButton({ settings.setArchived(selected, !showArchived); selected = emptySet() }) {
                            Icon(if (showArchived) Icons.Default.Unarchive else Icons.Default.Archive, "Archiver")
                        }
                        IconButton({ settings.setMuted(selected, !allMuted); selected = emptySet() }) {
                            Icon(if (allMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff, "Sourdine")
                        }
                        IconButton({
                            selected.forEach { Sms.markRead(ctx, it) }; selected = emptySet(); onChanged()
                        }) { Icon(Icons.Default.MarkEmailRead, "Lu") }
                        IconButton({ confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            } else {
                LargeTopAppBar(
                    title = { Text(if (showArchived) "Archivées" else "Messages", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (showArchived) IconButton({ showArchived = false }) { Icon(Icons.Default.ArrowBack, "Retour") }
                    },
                    actions = {
                        if (!showArchived) IconButton({ showArchived = true }) { Icon(Icons.Default.Archive, "Archivées") }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Réglages") }
                    },
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Chat, null) },
                text = { Text("Discuter") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                TextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Rechercher dans les messages") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Effacer") } },
                    singleLine = true, shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!isDefault) item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Appli SMS par défaut", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Définis Messages comme appli SMS par défaut pour recevoir, supprimer et gérer tes messages ici.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onRequestDefault) { Text("Définir par défaut") }
                    }
                }
            }
            if (loaded && shown.isEmpty() && query.isBlank()) item {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(if (showArchived) "Aucune conversation archivée" else "Aucune conversation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(shown, key = { it.threadId }) { c ->
                ConvRow(
                    c, settings, c.threadId in selected,
                    onClick = {
                        if (selecting) selected = if (c.threadId in selected) selected - c.threadId else selected + c.threadId
                        else onOpen(c)
                    },
                    onLong = { selected = selected + c.threadId },
                )
            }
            if (query.isNotBlank() && hits.isNotEmpty()) {
                item {
                    Text(
                        "Messages", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 4.dp),
                    )
                }
                items(hits, key = { "h${it.threadId}_${it.date}_${it.body.hashCode()}" }) { h ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenHit(h) }).padding(16.dp, 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(h.name ?: h.address, 40.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.name ?: h.address, fontWeight = FontWeight.Medium, maxLines = 1)
                            Text(h.body, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fmtList(h.date), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer ${selected.size} conversation(s) ?") },
            text = { Text("Les messages seront définitivement supprimés.") },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    if (!Sms.isDefault(ctx)) {
                        Toast.makeText(ctx, "Définis d'abord Messages comme appli SMS par défaut", Toast.LENGTH_LONG).show()
                    } else {
                        selected.forEach { Sms.deleteThread(ctx, it) }
                        onChanged()
                    }
                    selected = emptySet()
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvRow(c: Conv, s: Settings, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val unread = c.unread > 0
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.title, 52.dp, selected)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium, fontSize = 16.sp,
                )
                if (s.isPinned(c.threadId)) Icon(Icons.Default.PushPin, null, Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.isMuted(c.threadId)) Icon(Icons.Default.NotificationsOff, null, Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    fmtList(c.date), fontSize = 12.sp,
                    color = if (unread) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.snippet.replace('\n', ' '), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.Medium else FontWeight.Normal,
                )
                if (unread) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).defaultMinSize(20.dp, 20.dp).padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (c.unread > 99) "99+" else "${c.unread}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
