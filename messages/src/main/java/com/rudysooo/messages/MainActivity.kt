package com.rudysooo.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface Screen {
    data object List : Screen
    data object New : Screen
    data object Settings : Screen
    data class Thread(val threadId: Long, val addresses: kotlin.collections.List<String>, val title: String, val draft: String = "") : Screen
}

private data class OpenReq(val address: String, val body: String = "")

class MainActivity : ComponentActivity() {
    companion object { const val EXTRA_ADDRESS = "address" }

    private val openReq = mutableStateOf<OpenReq?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        val settings = Settings(this)
        setContent {
            MessagesTheme(settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    App(settings, openReq.value) { openReq.value = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    /** Ouverture depuis une notification, ou depuis un lien sms:/smsto: d'une autre appli. */
    private fun readIntent(i: Intent?) {
        i ?: return
        val fromNotif = i.getStringExtra(EXTRA_ADDRESS)
        val data: Uri? = i.data
        val address = fromNotif ?: data?.schemeSpecificPart?.substringBefore('?')?.takeIf { it.isNotBlank() }
        if (address != null) {
            val body = i.getStringExtra("sms_body") ?: i.getStringExtra(Intent.EXTRA_TEXT)
                ?: data?.getQueryParameter("body") ?: ""
            openReq.value = OpenReq(address, body)
        }
    }
}

@Composable
private fun App(settings: Settings, open: OpenReq?, onOpenHandled: () -> Unit) {
    val ctx = LocalContext.current
    var smsGranted by remember { mutableStateOf(Sms.hasSmsPermissions(ctx)) }
    var contactsGranted by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var defaultTick by remember { mutableIntStateOf(0) }
    val isDefault = remember(defaultTick, smsGranted) { Sms.isDefault(ctx) }
    var version by remember { mutableIntStateOf(0) }
    var screen by remember { mutableStateOf<Screen>(Screen.List) }
    var convs by remember { mutableStateOf<List<Conv>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        smsGranted = Sms.hasSmsPermissions(ctx)
        contactsGranted = it[Manifest.permission.READ_CONTACTS] == true || contactsGranted
        version++
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        smsGranted = Sms.hasSmsPermissions(ctx)
        defaultTick++; version++
    }
    fun requestDefault() {
        val i = if (Build.VERSION.SDK_INT >= 29) {
            ctx.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, ctx.packageName)
        roleLauncher.launch(i)
    }

    // Revérifie les autorisations au retour depuis les réglages d'Android
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                smsGranted = Sms.hasSmsPermissions(ctx)
                contactsGranted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                defaultTick++
            }
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }

    // Rafraîchit la liste dès que la base SMS change
    DisposableEffect(smsGranted) {
        if (!smsGranted) return@DisposableEffect onDispose {}
        val handler = Handler(Looper.getMainLooper())
        val bump = Runnable { version++ }
        val obs = object : ContentObserver(handler) {
            // Regroupe les rafales de changements pour ne recharger qu'une fois
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(bump); handler.postDelayed(bump, 250) }
        }
        runCatching { ctx.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, obs) }
        onDispose { ctx.contentResolver.unregisterContentObserver(obs) }
    }

    LaunchedEffect(version, smsGranted) {
        if (smsGranted) {
            convs = withContext(Dispatchers.IO) { Sms.conversations(ctx) }
            loaded = true
        }
    }

    LaunchedEffect(open, smsGranted) {
        if (open != null && smsGranted) {
            val tid = withContext(Dispatchers.IO) { Sms.threadFor(ctx, open.address) }
            val title = withContext(Dispatchers.IO) { Sms.displayName(ctx, open.address) } ?: open.address
            screen = Screen.Thread(tid, listOf(open.address), title, open.body)
            onOpenHandled()
        }
    }

    BackHandler(enabled = screen != Screen.List) { screen = Screen.List }

    if (!smsGranted) {
        PermissionScreen(onDefault = ::requestDefault) {
            val p = mutableListOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(p.toTypedArray())
        }
        return
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            val push = depth(targetState) > depth(initialState)
            val offset = tween<androidx.compose.ui.unit.IntOffset>(380, easing = IosEasing)
            val t = if (push) {
                (slideInHorizontally(offset) { it } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(offset) { -it / 3 } + fadeOut(tween(380)))
            } else {
                (slideInHorizontally(offset) { -it / 3 } + fadeIn(tween(380))) togetherWith
                    (slideOutHorizontally(offset) { it } + fadeOut(tween(160, delayMillis = 220)))
            }
            t.apply { targetContentZIndex = if (push) 1f else -1f }
        },
        contentKey = { if (it is Screen.Thread) "t${it.threadId}" else it::class },
        label = "screens",
    ) { s ->
        when (s) {
            Screen.List -> ListScreen(
                settings, convs, loaded,
                onOpen = { screen = Screen.Thread(it.threadId, it.addresses.ifEmpty { listOf(it.title) }, it.title) },
                onOpenHit = { screen = Screen.Thread(it.threadId, listOf(it.address), it.name ?: it.address) },
                onNew = {
                    if (!contactsGranted) permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                    screen = Screen.New
                },
                onSettings = { screen = Screen.Settings },
                onChanged = { version++ },
            )
            Screen.New -> NewScreen(contactsGranted, onBack = { screen = Screen.List }) { numbers, title ->
                screen = Screen.Thread(Sms.threadFor(ctx, numbers), numbers, title)
            }
            Screen.Settings -> SettingsScreen(settings, isDefault, ::requestDefault) { screen = Screen.List }
            is Screen.Thread -> ThreadScreen(
                settings, version, s.threadId, s.addresses, s.title, s.draft,
                onBack = { screen = Screen.List },
                onChanged = { version++ },
            )
        }
    }
}

private fun depth(s: Screen) = if (s == Screen.List) 0 else 1

@Composable
private fun PermissionScreen(onDefault: () -> Unit, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    fun ok(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val smsOk = ok(Manifest.permission.READ_SMS) && ok(Manifest.permission.SEND_SMS)
    val rows = listOf(
        "Lire et envoyer des SMS" to smsOk,
        "Recevoir des SMS" to ok(Manifest.permission.RECEIVE_SMS),
        "Contacts" to ok(Manifest.permission.READ_CONTACTS),
    )
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Chat, null, Modifier.size(84.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Bienvenue dans Messages", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            "Pour lire et envoyer tes SMS, l'appli a besoin d'accéder aux SMS et aux contacts.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        rows.forEach { (label, granted) ->
            Text(
                (if (granted) "✔  " else "✘  ") + label,
                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (!smsOk) {
            Button(onDefault) { Text("Définir comme appli SMS par défaut") }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onGrant) { Text("Autoriser l'accès") }
        } else Button(onGrant) { Text("Autoriser l'accès") }
        TextButton({
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
            )
        }) { Text("Ouvrir les réglages de l'appli") }
        if (!smsOk) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Android bloque les SMS pour les applis installées hors du Play Store. Touche « Définir comme appli SMS par défaut » " +
                    "et accepte : cela débloque l'accès. (Sinon : Réglages de l'appli → ⋮ → « Autoriser les paramètres restreints ».)",
                fontSize = 13.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
