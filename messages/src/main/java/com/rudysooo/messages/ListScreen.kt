package com.rudysooo.messages

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ListScreen(
    settings: Settings,
    convs: List<Conv>,
    loaded: Boolean,
    onOpen: (Conv) -> Unit,
    onOpenHit: (Hit) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var edit by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var deleteIds by remember { mutableStateOf<Set<Long>?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(query) {
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(250)
        hits = withContext(Dispatchers.IO) { Sms.search(ctx, query.trim()) }
    }

    val sorted = remember(convs, settings.archived, showArchived) {
        convs.filter { settings.isArchived(it.threadId) == showArchived }.sortedByDescending { it.date }
    }
    val shown = if (query.isBlank()) sorted else sorted.filter {
        it.title.contains(query, true) || it.address.contains(query, true)
    }
    val showPinned = query.isBlank() && !showArchived
    val pinned = if (showPinned) shown.filter { settings.isPinned(it.threadId) }.take(9) else emptyList()
    val rest = if (showPinned) shown.filterNot { it in pinned } else shown

    fun leaveEdit() { edit = false; selected = emptySet() }
    fun askDelete(ids: Set<Long>) { deleteIds = ids }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (edit) {
                TopAppBar(
                    title = {
                        Text(
                            if (selected.isEmpty()) "Sélectionner" else "${selected.size} sélectionnée(s)",
                            fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                        )
                    },
                    navigationIcon = { TextButton(::leaveEdit) { Text("Terminé", fontWeight = FontWeight.SemiBold) } },
                    actions = {
                        TextButton({ selected = if (selected.size == shown.size) emptySet() else shown.map { it.threadId }.toSet() }) {
                            Text(if (selected.size == shown.size && shown.isNotEmpty()) "Aucun" else "Tout")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                LargeTopAppBar(
                    title = { Text(if (showArchived) "Archivées" else "Messages", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (showArchived) IconButton({ showArchived = false }) { Icon(Icons.Default.ArrowBackIosNew, "Retour", Modifier.size(20.dp)) }
                        else TextButton({ edit = true }) { Text("Modifier") }
                    },
                    actions = {
                        if (!showArchived) IconButton({ showArchived = true }) { Icon(Icons.Default.Archive, "Archivées") }
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Réglages") }
                        val src = remember { MutableInteractionSource() }
                        Box(
                            Modifier.padding(end = 8.dp).size(36.dp).pressScale(src, 0.88f).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .combinedClickable(interactionSource = src, indication = null, onClick = onNew),
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Default.Edit, "Nouveau message", Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                    },
                    scrollBehavior = scroll,
                    colors = TopAppBarDefaults.largeTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                edit, enter = slideInVertically(tween(300, easing = IosEasing)) { it } + fadeIn(),
                exit = slideOutVertically(tween(250, easing = IosEasing)) { it } + fadeOut(),
            ) {
                val on = selected.isNotEmpty()
                val allPinned = on && selected.all { settings.isPinned(it) }
                val allMuted = on && selected.all { settings.isMuted(it) }
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).navigationBarsPadding().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    ToolButton(Icons.Default.PushPin, if (allPinned) "Désépingler" else "Épingler", on) {
                        settings.setPinned(selected, !allPinned); leaveEdit()
                    }
                    ToolButton(if (allMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff, "Sourdine", on) {
                        settings.setMuted(selected, !allMuted); leaveEdit()
                    }
                    ToolButton(if (showArchived) Icons.Default.Unarchive else Icons.Default.Archive, if (showArchived) "Désarchiver" else "Archiver", on) {
                        settings.setArchived(selected, !showArchived); leaveEdit()
                    }
                    ToolButton(Icons.Default.MarkEmailRead, "Lu", on) {
                        selected.forEach { Sms.markRead(ctx, it) }; leaveEdit(); onChanged()
                    }
                    ToolButton(Icons.Default.Delete, "Supprimer", on, danger = true) { askDelete(selected) }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "search") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Rechercher") },
                        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) },
                        trailingIcon = {
                            AnimatedVisibility(query.isNotEmpty(), enter = scaleIn(spring(stiffness = Spring.StiffnessMedium)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                                IconButton({ query = "" }) { Icon(Icons.Default.Cancel, "Effacer", Modifier.size(18.dp)) }
                            }
                        },
                        singleLine = true, shape = CircleShape,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f).heightIn(min = 44.dp).onFocusChanged { searchFocused = it.isFocused },
                    )
                    AnimatedVisibility(
                        searchFocused || query.isNotEmpty(),
                        enter = expandHorizontally(tween(280, easing = IosEasing)) + fadeIn(),
                        exit = shrinkHorizontally(tween(220, easing = IosEasing)) + fadeOut(),
                    ) {
                        TextButton({ query = ""; focus.clearFocus() }) { Text("Annuler") }
                    }
                }
            }

            if (pinned.isNotEmpty()) item(key = "pinned") {
                FlowRow(
                    Modifier.fillMaxWidth().padding(12.dp, 8.dp).animateContentSize(),
                    horizontalArrangement = Arrangement.Center, maxItemsInEachRow = 3,
                ) {
                    pinned.forEach { c -> PinnedTile(c, onClick = { if (edit) selected = selected.toggle(c.threadId) else onOpen(c) }) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); edit = true; selected = selected + c.threadId
                    } }
                }
            }

            if (loaded && shown.isEmpty() && query.isBlank()) item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ChatBubbleOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(if (showArchived) "Aucune conversation archivée" else "Aucune conversation", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            items(rest, key = { it.threadId }, contentType = { "conv" }) { c ->
                SwipeRow(
                    c, settings, edit, c.threadId in selected, Modifier.animateItemPlacement(spring(stiffness = Spring.StiffnessMediumLow)),
                    onPin = { settings.setPinned(listOf(c.threadId), !settings.isPinned(c.threadId)) },
                    onDelete = { askDelete(setOf(c.threadId)) },
                    onClick = { if (edit) selected = selected.toggle(c.threadId) else onOpen(c) },
                    onLong = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        edit = true; selected = selected + c.threadId
                    },
                )
            }

            if (query.isNotBlank() && hits.isNotEmpty()) {
                item(key = "hits-title") {
                    Text(
                        "MESSAGES", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp, 18.dp, 20.dp, 6.dp),
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

    deleteIds?.let { ids ->
        AlertDialog(
            onDismissRequest = { deleteIds = null },
            title = { Text(if (ids.size == 1) "Supprimer la conversation ?" else "Supprimer ${ids.size} conversations ?") },
            text = { Text("Les messages seront définitivement supprimés.") },
            confirmButton = {
                TextButton({
                    deleteIds = null
                    if (!Sms.isDefault(ctx)) {
                        Toast.makeText(ctx, "Définis d'abord Messages comme appli SMS par défaut", Toast.LENGTH_LONG).show()
                    } else {
                        ids.forEach { Sms.deleteThread(ctx, it) }
                        onChanged()
                    }
                    leaveEdit()
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleteIds = null }) { Text("Annuler") } },
        )
    }
}

private fun Set<Long>.toggle(id: Long) = if (id in this) this - id else this + id

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outline
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        Modifier.pressScale(src, 0.9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .combinedClickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick).padding(10.dp, 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        Text(label, fontSize = 11.sp, color = tint)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PinnedTile(c: Conv, onClick: () -> Unit, onLong: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Column(
        Modifier.width(104.dp).padding(vertical = 6.dp).pressScale(src, 0.92f)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Avatar(c.title, 76.dp)
            if (c.unread > 0) Box(
                Modifier.align(Alignment.TopStart).size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.background).padding(2.dp)
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(c.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SwipeRow(
    c: Conv, s: Settings, edit: Boolean, selected: Boolean, modifier: Modifier,
    onPin: () -> Unit, onDelete: () -> Unit, onClick: () -> Unit, onLong: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val pin by rememberUpdatedState(onPin)
    val del by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); pin() }
            SwipeToDismissBoxValue.EndToStart -> del()
            else -> {}
        }
        false // la ligne revient toujours à sa place
    })
    SwipeToDismissBox(
        state = state, modifier = modifier,
        enableDismissFromStartToEnd = !edit, enableDismissFromEndToStart = !edit,
        backgroundContent = {
            val dir = state.dismissDirection
            val pinning = dir == SwipeToDismissBoxValue.StartToEnd
            val active = dir != SwipeToDismissBoxValue.Settled
            val scale by animateFloatAsState(
                if (state.targetValue != SwipeToDismissBoxValue.Settled) 1.15f else 0.8f,
                spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "swipeIcon",
            )
            Box(
                Modifier.fillMaxSize().background(
                    when {
                        !active -> MaterialTheme.colorScheme.background
                        pinning -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.error
                    },
                ).padding(horizontal = 28.dp),
                contentAlignment = if (pinning) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (active) Icon(
                    if (pinning) Icons.Default.PushPin else Icons.Default.Delete, null, tint = Color.White,
                    modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                )
            }
        },
    ) { ConvRow(c, s, edit, selected, onClick, onLong) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConvRow(c: Conv, s: Settings, edit: Boolean, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val unread = c.unread > 0
    val src = remember { MutableInteractionSource() }
    Box(Modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong)
                .padding(end = 16.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(edit, enter = expandHorizontally(tween(260, easing = IosEasing)) + fadeIn(), exit = shrinkHorizontally(tween(220, easing = IosEasing)) + fadeOut()) {
                Box(Modifier.padding(start = 16.dp).size(24.dp), contentAlignment = Alignment.Center) {
                    val fill by animateFloatAsState(if (selected) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "check")
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = fill)),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                }
            }
            // Pastille bleue des messages non lus, à gauche comme sur iOS
            Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(unread, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)), exit = scaleOut()) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
            }
            Avatar(c.title, 50.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        c.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold, fontSize = 16.sp,
                    )
                    if (s.isMuted(c.threadId)) Icon(Icons.Default.NotificationsOff, null, Modifier.size(13.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(fmtList(c.date), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    c.snippet.replace('\n', ' '), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp,
                    color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(
            Modifier.align(Alignment.BottomEnd).fillMaxWidth().padding(start = 82.dp),
            thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
        )
    }
}
