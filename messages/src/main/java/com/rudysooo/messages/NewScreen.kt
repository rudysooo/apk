package com.rudysooo.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewScreen(contactsGranted: Boolean, onBack: () -> Unit, onPick: (number: String, name: String?) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(contactsGranted) {
        if (contactsGranted) contacts = withContext(Dispatchers.IO) { Sms.contacts(ctx) }
    }
    val q = query.trim()
    val looksLikeNumber = q.isNotEmpty() && q.all { it.isDigit() || it in "+ -().".toList() } && q.count { it.isDigit() } >= 3
    val filtered = if (q.isEmpty()) contacts else contacts.filter { it.name.contains(q, true) || it.number.filter { c -> c.isDigit() }.contains(q.filter { c -> c.isDigit() }.ifEmpty { "§" }) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Nouvelle conversation", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("À : nom ou numéro") },
                singleLine = true, shape = CircleShape,
                keyboardOptions = KeyboardOptions.Default,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp),
            )
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                if (looksLikeNumber) item {
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(q, null) }.padding(16.dp, 12.dp),
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
                        "Autorise l'accès aux contacts (Réglages) pour les retrouver ici. Tu peux quand même taper un numéro.",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                    )
                }
                items(filtered, key = { it.number + it.name }) { c ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(c.number, c.name) }.padding(16.dp, 8.dp),
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
