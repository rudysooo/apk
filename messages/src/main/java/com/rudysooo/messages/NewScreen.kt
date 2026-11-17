package com.rudysooo.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewScreen(contactsGranted: Boolean, onBack: () -> Unit, onPick: (addresses: List<String>, title: String) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var chosen by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(contactsGranted) {
        if (contactsGranted) contacts = withContext(Dispatchers.IO) { Sms.contacts(ctx) }
    }
    val q = query.trim()
    val digits = q.filter { it.isDigit() }
    val looksLikeNumber = q.isNotEmpty() && q.all { it.isDigit() || it in "+ -().".toList() } && digits.length >= 3
    val filtered = (if (q.isEmpty()) contacts else contacts.filter {
        it.name.contains(q, true) || (digits.isNotEmpty() && it.number.filter { c -> c.isDigit() }.contains(digits))
    }).filter { c -> chosen.none { it.number == c.number } }

    fun add(c: Contact) { chosen = chosen + c; query = "" }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle conversation", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (chosen.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = { onPick(chosen.map { it.number }, chosen.joinToString(", ") { it.name }) },
                icon = { Icon(Icons.Default.ArrowForward, null) },
                text = { Text(if (chosen.size > 1) "Créer le groupe (${chosen.size})" else "Continuer") },
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (chosen.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chosen.forEach { c ->
                        InputChip(
                            selected = false, onClick = { chosen = chosen - c }, label = { Text(c.name) },
                            trailingIcon = { Icon(Icons.Default.Close, "Retirer", Modifier.size(16.dp)) },
                        )
                    }
                }
            }
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(if (chosen.isEmpty()) "À : nom ou numéro" else "Ajouter un destinataire") },
                singleLine = true, shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (looksLikeNumber) add(Contact(q, q)) }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )
            if (chosen.size == 1) {
                Text(
                    "Ajoute d'autres personnes pour créer une conversation de groupe (envoyée en MMS).",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                if (looksLikeNumber) item {
                    Row(
                        Modifier.fillMaxWidth().clickable { add(Contact(q, q)) }.padding(16.dp, 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(q, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Envoyer à $q", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Default.Send, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!contactsGranted) item {
                    Text(
                        "Autorise l'accès aux contacts pour les retrouver ici. Tu peux quand même taper un numéro.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                    )
                }
                items(filtered, key = { it.number + it.name }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { add(c) }.padding(16.dp, 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(c.name, 44.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(c.name, fontWeight = FontWeight.Medium)
                            Text(c.number, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
