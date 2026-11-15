<#
 PC Remote - agent Windows
 Écoute des ordres de l'application Android sur le réseau local.

 Installation (PowerShell administrateur, une seule fois) :
   powershell -ExecutionPolicy Bypass -File agent.ps1 -Install -Token <jeton> -Port 8765

 Désinstallation :
   powershell -ExecutionPolicy Bypass -File agent.ps1 -Uninstall
#>
param(
    [string]$Token = "",
    [int]$Port = 8765,
    [switch]$Install,
    [switch]$Uninstall
)

$TaskName = "PcRemoteAgent"
$FwName   = "PcRemoteAgent"
$Dir      = "C:\ProgramData\PcRemoteAgent"

if ($Uninstall) {
    Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName $FwName -ErrorAction SilentlyContinue
    Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" |
        Where-Object { $_.CommandLine -like '*PcRemoteAgent*agent.ps1*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Remove-Item $Dir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host "Agent désinstallé."
    exit
}

if ($Install) {
    if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole('Administrator')) {
        Write-Host "Relance PowerShell en administrateur." -ForegroundColor Red; exit 1
    }
    if ($Token.Length -lt 8) { Write-Host "Donne un jeton d'au moins 8 caractères (-Token)." -ForegroundColor Red; exit 1 }

    New-Item -ItemType Directory -Force -Path $Dir | Out-Null
    Copy-Item $PSCommandPath "$Dir\agent.ps1" -Force
    Set-Content -Path "$Dir\config.json" -Value (@{ token = $Token; port = $Port } | ConvertTo-Json)
    # Seuls SYSTEM et les administrateurs peuvent lire le jeton
    icacls $Dir /inheritance:r /grant:r "SYSTEM:(OI)(CI)F" "Administrators:(OI)(CI)F" | Out-Null

    Remove-NetFirewallRule -DisplayName $FwName -ErrorAction SilentlyContinue
    New-NetFirewallRule -DisplayName $FwName -Direction Inbound -Protocol TCP -LocalPort $Port `
        -Action Allow -RemoteAddress LocalSubnet -Profile Any | Out-Null

    $action  = New-ScheduledTaskAction -Execute "powershell.exe" `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$Dir\agent.ps1`""
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $princ   = New-ScheduledTaskPrincipal -UserId "SYSTEM" -RunLevel Highest
    $set     = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 99 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $princ -Settings $set -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
    Write-Host "Agent installé et démarré sur le port $Port." -ForegroundColor Green
    exit
}

# ---- Mode serveur ----
$cfg = Get-Content "$Dir\config.json" -Raw | ConvertFrom-Json
$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://+:$($cfg.port)/")
$listener.Start()

function Send($ctx, [int]$code, [string]$text) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($text)
    $ctx.Response.StatusCode = $code
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
}

while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    try {
        $given = $ctx.Request.QueryString["token"]
        if ($given -ne $cfg.token) { Send $ctx 401 "token"; continue }
        switch ($ctx.Request.Url.AbsolutePath.Trim('/')) {
            "ping"     { Send $ctx 200 "pong" }
            "shutdown" { Send $ctx 200 "shutdown"; Start-Process shutdown.exe -ArgumentList "/s","/t","3" }
            "restart"  { Send $ctx 200 "restart";  Start-Process shutdown.exe -ArgumentList "/r","/t","3" }
            "lock"     { Send $ctx 200 "lock";     Start-Process rundll32.exe -ArgumentList "user32.dll,LockWorkStation" }
            default    { Send $ctx 404 "unknown" }
        }
    } catch { try { $ctx.Response.Abort() } catch {} }
}
