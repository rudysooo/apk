# PC Remote - installation complete sur le PC (lancee par Installer.bat, en administrateur)
$ErrorActionPreference = "Stop"
$Port = 8765
Get-ChildItem $PSScriptRoot | Unblock-File -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "=== PC Remote : installation ===" -ForegroundColor Cyan

# 1. Carte reseau principale (celle qui a acces a la box)
$route = Get-NetRoute -DestinationPrefix "0.0.0.0/0" | Sort-Object RouteMetric | Select-Object -First 1
if (-not $route) { throw "Aucune connexion reseau active. Connecte le PC a internet puis relance." }
$nic = Get-NetAdapter -InterfaceIndex $route.InterfaceIndex
$mac = ($nic.MacAddress -replace "-", ":").ToUpper()
$ipInfo = Get-NetIPAddress -InterfaceIndex $nic.ifIndex -AddressFamily IPv4 | Select-Object -First 1
$ip = $ipInfo.IPAddress
$ipBytes = ([System.Net.IPAddress]::Parse($ip)).GetAddressBytes()
$maskInt = [uint32]([math]::Pow(2, 32) - [math]::Pow(2, 32 - $ipInfo.PrefixLength))
$maskBytes = [BitConverter]::GetBytes($maskInt); [Array]::Reverse($maskBytes)
$bcBytes = for ($i = 0; $i -lt 4; $i++) { $ipBytes[$i] -bor (255 - $maskBytes[$i]) }
$broadcast = $bcBytes -join "."

$isWifi = ($nic.PhysicalMediaType -like "*802.11*") -or ($nic.Name -like "*Wi-Fi*") -or ($nic.Name -like "*Wireless*")
Write-Host "Carte reseau : $($nic.Name) ($($nic.InterfaceDescription))"

# 2. Windows : autoriser le reveil par paquet magique
try {
    Set-NetAdapterPowerManagement -Name $nic.Name -WakeOnMagicPacket Enabled -WakeOnPattern Disabled -ErrorAction Stop
    Write-Host "[OK] Reveil par paquet magique active sur la carte reseau" -ForegroundColor Green
} catch {
    Write-Host "[!] Impossible de regler la carte automatiquement. Fais-le a la main :" -ForegroundColor Yellow
    Write-Host "    Gestionnaire de peripheriques > Cartes reseau > ta carte > Proprietes > Gestion de l'alimentation"
    Write-Host "    Coche 'Autoriser ce peripherique a sortir l'ordinateur de veille' et 'Seulement autoriser un paquet magique'."
}
try {
    Set-ItemProperty -Path "HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager\Power" -Name HiberbootEnabled -Value 0 -Type DWord
    Write-Host "[OK] Demarrage rapide desactive (sinon le reveil echoue apres un arret)" -ForegroundColor Green
} catch { Write-Host "[!] Desactive le demarrage rapide a la main (Options d'alimentation)." -ForegroundColor Yellow }

# 3. Agent d'extinction
$bytes = New-Object byte[] 12
[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$token = ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
& "$PSScriptRoot\agent.ps1" -Install -Token $token -Port $Port
if (-not (Get-ScheduledTask -TaskName "PcRemoteAgent" -ErrorAction SilentlyContinue)) {
    Write-Host "[ERREUR] L'agent d'extinction n'a pas pu etre installe. Les infos ci-dessous ne sont pas valables pour l'extinction." -ForegroundColor Red
    exit 1
}

# 4. Recap, affiche et enregistre sur le Bureau
$nom = $env:COMPUTERNAME
$recap = @"
=== INFOS A ENTRER DANS L'APPLI PC REMOTE (Ajouter un PC) ===
Nom       : $nom
Adresse MAC : $mac
IP du PC  : $ip
Broadcast : $broadcast
Jeton (pour l'extinction) : $token
Port agent : $Port
"@
$file = Join-Path ([Environment]::GetFolderPath("Desktop")) "PcRemote-infos.txt"
$recap | Set-Content -Path $file -Encoding UTF8
Write-Host ""
Write-Host $recap -ForegroundColor Cyan
Write-Host "(Ces infos sont aussi enregistrees sur le Bureau : PcRemote-infos.txt)"
Write-Host ""
if ($isWifi) {
    Write-Host "[!] Ce PC est en Wi-Fi. Le reveil a distance ne marche generalement qu'en cable Ethernet." -ForegroundColor Yellow
}
Write-Host "IL RESTE 2 CHOSES A FAIRE :" -ForegroundColor Yellow
Write-Host " 1. BIOS : active le reveil reseau (voir LISEZMOI.txt, etape 3)."
Write-Host " 2. IP fixe : si l'IP du PC change, l'appli ne le retrouve plus (voir LISEZMOI.txt, etape 4)."
