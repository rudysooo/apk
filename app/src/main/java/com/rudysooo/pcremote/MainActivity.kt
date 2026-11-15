package com.rudysooo.pcremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

private val Dark = darkColorScheme(
    primary = Color(0xFF5B8CFF),
    background = Color(0xFF0F1115),
    surface = Color(0xFF181B22),
    surfaceVariant = Color(0xFF222633),
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
)

sealed interface Screen {
    data object Home : Screen
    data object Guide : Screen
    data class Edit(val pc: Pc?, val prefill: Found? = null) : Screen
    data object Scan : Screen
    data class Agent(val pc: Pc) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = Dark) { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val store = remember { PcStore(ctx) }
    var pcs by remember { mutableStateOf(store.load()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    fun persist(list: List<Pc>) { pcs = list; store.save(list) }

    BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            pcs,
            onAdd = { screen = Screen.Edit(null) },
            onScan = { screen = Screen.Scan },
            onGuide = { screen = Screen.Guide },
            onEdit = { screen = Screen.Edit(it) },
            onAgent = { screen = Screen.Agent(it) },
        )
        Screen.Guide -> GuideScreen { screen = Screen.Home }
        Screen.Scan -> ScanScreen(
            onBack = { screen = Screen.Home },
            onPick = { screen = Screen.Edit(null, it) },
        )
        is Screen.Edit -> EditScreen(
            s.pc, s.prefill,
            onSave = { pc -> persist(pcs.filter { it.id != pc.id } + pc); screen = Screen.Home },
            onDelete = { pc -> persist(pcs.filter { it.id != pc.id }); screen = Screen.Home },
            onBack = { screen = Screen.Home },
        )
        is Screen.Agent -> AgentScreen(s.pc) { screen = Screen.Home }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    pcs: List<Pc>,
    onAdd: () -> Unit, onScan: () -> Unit, onGuide: () -> Unit,
    onEdit: (Pc) -> Unit, onAgent: (Pc) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Mes PC", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onGuide) { Icon(Icons.Default.HelpOutline, "Aide") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Ajouter") })
        },
    ) { pad ->
        if (pcs.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Computer, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Aucun PC pour l'instant", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Ajoute ton PC pour pouvoir l'allumer à distance, ou lis d'abord le guide.", color = Color.Gray)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onScan) { Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Chercher sur le réseau") }
                TextButton(onClick = onGuide) { Text("Guide de configuration") }
            }
        } else {
            LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pcs, key = { it.id }) { pc -> PcCard(pc, onEdit, onAgent) }
                item {
                    OutlinedButton(onClick = onScan, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Chercher un PC sur le réseau")
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun PcCard(pc: Pc, onEdit: (Pc) -> Unit, onAgent: (Pc) -> Unit) {
    val scope = rememberCoroutineScope()
    var online by remember { mutableStateOf<Boolean?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    var waking by remember { mutableStateOf(false) }

    LaunchedEffect(pc.ip) {
        while (true) {
            online = Net.isOnline(pc.ip, pc.agentPort)
            delay(5000)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(when (online) { true -> Color(0xFF3DDC84); false -> Color(0xFFEF5350); null -> Color.Gray })
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(pc.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        (when (online) { true -> "En ligne"; false -> "Éteint / injoignable"; null -> "Vérification…" }) + " · ${pc.ip}",
                        color = Color.Gray, fontSize = 13.sp,
                    )
                }
                IconButton(onClick = { onEdit(pc) }) { Icon(Icons.Default.Edit, "Modifier") }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            waking = true
                            val r = Net.wake(pc.mac, pc.broadcast)
                            msg = if (r.isSuccess) "Paquet envoyé, le PC démarre…" else "Échec : ${r.exceptionOrNull()?.message}"
                            waking = false
                        }
                    },
                    enabled = !waking,
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.PowerSettingsNew, null); Spacer(Modifier.width(6.dp)); Text("Allumer") }
                FilledTonalButton(
                    onClick = {
                        if (pc.token.isBlank()) onAgent(pc)
                        else scope.launch {
                            msg = Net.agent(pc, "shutdown").fold({ "Extinction demandée" }, { "Échec : ${it.message}" })
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Icon(Icons.Default.PowerOff, null); Spacer(Modifier.width(6.dp)); Text("Éteindre") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { onAgent(pc) }) { Text("Configurer l'extinction", fontSize = 12.sp) }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Retour") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
fun Step(n: Int, title: String, body: String) {
    Card(Modifier.padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) { Text("$n", color = Color.White, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(body, color = Color(0xFFB8BCC8), fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun Code(text: String) {
    SelectionContainer {
        Text(
            text, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        )
    }
}

@Composable
fun GuideScreen(onBack: () -> Unit) = Page("Guide de configuration", onBack) {
    Text("Pour allumer un PC à distance (Wake-on-LAN), il doit être branché en câble Ethernet. Le Wi-Fi ne marche pas en général.", color = Color(0xFFB8BCC8))
    Spacer(Modifier.height(12.dp))
    Step(1, "BIOS : autoriser le réveil",
        "Redémarre et appuie sur Suppr ou F2. Active l'option qui réveille le PC par le réseau : « PCIE Devices Power On » (ASRock : Advanced → ACPI Configuration), « Wake on LAN » ou « Power On By PCI-E » selon la carte mère. Désactive ErP / EuP si présent. Sauvegarde avec F10.")
    Step(2, "Windows : la carte réseau",
        "Gestionnaire de périphériques → Cartes réseau → ta carte Ethernet → Propriétés → Gestion de l'alimentation : coche « Autoriser ce périphérique à sortir l'ordinateur de veille » et « Seulement autoriser un paquet magique ». Désactive aussi le démarrage rapide (Options d'alimentation).")
    Step(3, "Fixer l'IP du PC",
        "Si l'IP du PC change, l'état « en ligne » et l'extinction ne marchent plus. Fixe l'IP dans Windows (ou réserve-la dans ta box). Le réveil, lui, utilise surtout l'adresse MAC.")
    Step(4, "Trouver l'adresse MAC", "Sur le PC, ouvre PowerShell et tape :")
    Code("Get-NetAdapter | Where-Object Status -eq 'Up' | Select Name, MacAddress")
    Step(5, "Ajouter le PC dans l'appli",
        "Bouton « Ajouter » : nom, MAC et IP du PC. Le broadcast est rempli tout seul. Le téléphone doit être sur le même Wi-Fi que le PC (pas en 4G ni en réseau invité).")
    Step(6, "Éteindre à distance (optionnel)",
        "Il faut installer le petit agent sur le PC : dans l'appli, ouvre « Configurer l'extinction » sur ton PC, et suis les étapes.")
    Spacer(Modifier.height(24.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit, onPick: (Found) -> Unit) {
    val ctx = LocalContext.current
    var progress by remember { mutableStateOf(0f) }
    var results by remember { mutableStateOf<List<Found>?>(null) }
    LaunchedEffect(Unit) { results = Net.scan(ctx) { progress = it } }

    Page("Recherche sur le réseau", onBack) {
        val r = results
        if (r == null) {
            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Analyse du réseau en cours…", color = Color.Gray)
        } else if (r.isEmpty()) {
            Text("Aucun appareil trouvé. Vérifie que tu es connecté au Wi-Fi de ta box.")
        } else {
            Text("Choisis ton PC. Les PC Windows ont souvent un nom qui commence par DESKTOP- ou le nom de ton PC.", color = Color.Gray)
            Spacer(Modifier.height(12.dp))
            r.sortedBy { it.host == null }.forEach { f ->
                Card(
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    onClick = { onPick(f) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(f.host ?: "Appareil inconnu", fontWeight = FontWeight.SemiBold)
                        Text(f.ip + (f.mac?.let { " · $it" } ?: ""), color = Color.Gray, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EditScreen(pc: Pc?, prefill: Found?, onSave: (Pc) -> Unit, onDelete: (Pc) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lan = remember { Net.currentLan(ctx) }
    var name by remember { mutableStateOf(pc?.name ?: prefill?.host?.substringBefore('.') ?: "") }
    var mac by remember { mutableStateOf(pc?.mac ?: prefill?.mac ?: "") }
    var ip by remember { mutableStateOf(pc?.ip ?: prefill?.ip ?: "") }
    var bc by remember { mutableStateOf(pc?.broadcast ?: lan?.broadcast ?: "192.168.1.255") }
    var error by remember { mutableStateOf<String?>(null) }

    Page(if (pc == null) "Ajouter un PC" else "Modifier", onBack) {
        OutlinedTextField(name, { name = it }, label = { Text("Nom (ex: PC du salon)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(mac, { mac = it }, label = { Text("Adresse MAC (70-85-C2-9B-A0-CD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Sur le PC : PowerShell → Get-NetAdapter | Select Name, MacAddress", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(ip, { ip = it }, label = { Text("IP du PC (192.168.1.x)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(bc, { bc = it }, label = { Text("Broadcast") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = Color(0xFFEF5350)) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                error = when {
                    name.isBlank() -> "Donne un nom au PC"
                    Net.parseMac(mac) == null -> "Adresse MAC invalide (12 caractères 0-9 A-F)"
                    !Net.isIpv4(ip) -> "IP invalide"
                    !Net.isIpv4(bc) -> "Broadcast invalide"
                    else -> null
                }
                if (error == null) onSave(
                    (pc ?: Pc(name = "", mac = "", ip = "", broadcast = ""))
                        .copy(name = name.trim(), mac = Net.formatMac(mac), ip = ip.trim(), broadcast = bc.trim())
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enregistrer") }
        if (pc != null) {
            TextButton(onClick = { onDelete(pc) }, modifier = Modifier.fillMaxWidth()) {
                Text("Supprimer ce PC", color = Color(0xFFEF5350))
            }
        }
    }
}

@Composable
fun AgentScreen(pc: Pc, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val store = remember { PcStore(ctx) }
    var token by remember {
        mutableStateOf(pc.token.ifBlank {
            val b = ByteArray(12).also { SecureRandom().nextBytes(it) }
            b.joinToString("") { "%02x".format(it) }
        })
    }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }

    fun saveToken() { store.save(store.load().map { if (it.id == pc.id) it.copy(token = token) else it }) }

    Page("Extinction à distance", onBack) {
        Text("Un PC éteint se réveille grâce à sa carte réseau, mais un PC allumé ne s'éteint pas tout seul sur ordre. Il faut un petit programme (l'agent) installé sur le PC, qui écoute ton téléphone.", color = Color(0xFFB8BCC8))
        Spacer(Modifier.height(12.dp))
        Step(1, "Récupère le script", "Le fichier agent.ps1 est dans le dossier pc-agent du dépôt GitHub rudysooo/apk. Copie-le sur le PC, par exemple dans C:\\PcAgent.")
        Step(2, "Installe-le (une seule fois)", "Ouvre PowerShell en administrateur et lance la commande ci-dessous. Elle ouvre le port ${pc.agentPort} pour ton réseau local et lance l'agent à chaque démarrage de Windows.")
        Code("powershell -ExecutionPolicy Bypass -File C:\\PcAgent\\agent.ps1 -Install -Token $token -Port ${pc.agentPort}")
        Step(3, "Enregistre le jeton ici", "Le jeton est le mot de passe entre ton téléphone et le PC.")
        OutlinedTextField(token, { token = it.trim() }, label = { Text("Jeton") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { saveToken(); status = "Jeton enregistré" }, modifier = Modifier.fillMaxWidth()) { Text("Enregistrer le jeton") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                saveToken()
                scope.launch { status = Net.agent(pc.copy(token = token), "ping").fold({ "Agent joignable ✔" }, { "Échec : ${it.message}" }) }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Tester la connexion (PC allumé)") }
        status?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(24.dp))
    }
}
