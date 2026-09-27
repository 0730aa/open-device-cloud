# Uses the Windows bundle on a Windows machine the way its README tells a person to, minus the
# phone: start-server.bat, register the super admin, create an agent, start-agent.bat with the
# agent's key until the agent is online, then restart and stop the server. Windows PowerShell 5.1.
# Needs MySQL on 127.0.0.1:3306, with the root password in SMOKE_MYSQL_PASSWORD.
param([Parameter(Mandatory = $true)][string]$Bundle)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy
$Web = 'http://127.0.0.1:3000'
$env:ODC_NO_BROWSER = '1'
$env:ODC_MYSQL_PASSWORD = $env:SMOKE_MYSQL_PASSWORD
$Password = 'smoke-test-password'

function Step([string]$Text) {
    Write-Host ''
    Write-Host "=== $Text" -ForegroundColor Cyan
}

function Assert-That([bool]$Condition, [string]$What) {
    if (-not $Condition) {
        throw "FAILED: $What"
    }
    Write-Host "ok: $What"
}

# Runs one of the bundle's .bat files; the input from NUL answers its closing pause. The output goes
# to a file, not a pipe: the server's programs inherit the handle and keep running, so a pipe would
# never reach its end.
function Invoke-Bat([string]$Name) {
    $log = Join-Path $env:RUNNER_TEMP "$Name.log"
    $start = New-Object Diagnostics.ProcessStartInfo 'cmd.exe', "/c `"`"$Bundle\$Name`" > `"$log`" 2>&1 < NUL`""
    $start.UseShellExecute = $false
    $process = [Diagnostics.Process]::Start($start)
    $process.WaitForExit()
    [IO.File]::ReadAllLines($log, [Console]::OutputEncoding) | ForEach-Object { Write-Host "  | $_" }
    return $process.ExitCode
}

# Everything the bundle started: its Java, nginx and adb.
function Stop-Bundle {
    Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith($Bundle, 'OrdinalIgnoreCase') } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null, [string]$Token = '') {
    $request = @{ Uri = "$Web/server/api$Path"; Method = $Method; UseBasicParsing = $true; TimeoutSec = 30; Headers = @{} }
    if ($Token) {
        $request.Headers['SonicToken'] = $Token
    }
    if ($null -ne $Body) {
        $request.Body = ConvertTo-Json $Body -Compress
        $request.ContentType = 'application/json'
    }
    return Invoke-RestMethod @request
}

function Get-Agent([string]$Token) {
    return (Invoke-Api GET '/controller/agents/list' -Token $Token).data | Where-Object { $_.name -eq 'smoke-test' }
}

function Wait-For([int]$Seconds, [scriptblock]$Condition) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        if (& $Condition) {
            return $true
        }
        Start-Sleep -Seconds 3
    }
    return $false
}

# The agent logs this each time it connects; the server's record of it may be stale after a hard stop.
function Get-AgentLogins {
    return @(Select-String -Path "$Bundle\agent\logs\sonic-agent.log" -Pattern 'server auth successful' -SimpleMatch -ErrorAction SilentlyContinue).Count
}

function Stop-Tree([int]$Id) {
    $ErrorActionPreference = 'Continue'
    & taskkill /PID $Id /T /F 2>&1 | Out-Null
}

function Test-Listening([int]$Port) {
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Get-BundleJava {
    $java = Join-Path $Bundle 'jre\bin\java.exe'
    return @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" | Where-Object { $_.ExecutablePath -eq $java })
}

$agentConsole = $null
try {
    Step 'start the server'
    Assert-That ((Invoke-Bat 'start-server.bat') -eq 0) 'start-server.bat succeeds'
    foreach ($port in 3000, 8094, 8761) {
        $address = (Get-NetTCPConnection -LocalPort $port -State Listen).LocalAddress | Sort-Object -Unique
        Assert-That ("$address" -eq '127.0.0.1') "port $port only listens on 127.0.0.1 ($address)"
    }

    Step 'the web client, also on a page reached by a link'
    Assert-That ((Invoke-WebRequest "$Web/" -UseBasicParsing).Content -match 'id="app"') 'the index page is served'
    Assert-That ((Invoke-WebRequest "$Web/Index/Devices" -UseBasicParsing).Content -match 'id="app"') 'links into the app are served'

    Step 'a second start finds the running server'
    Assert-That ((Invoke-Bat 'start-server.bat') -eq 0) 'the second start-server.bat succeeds'

    Step 'register the super admin and log in'
    $register = Invoke-Api POST '/controller/users/register' @{ userName = 'sonic'; password = $Password }
    Assert-That ($register.code -eq 2000) "registering sonic ($($register.code))"
    $login = Invoke-Api POST '/controller/users/login' @{ userName = 'sonic'; password = $Password }
    Assert-That ($login.code -eq 2000 -and $login.data) 'logging in'
    $token = $login.data

    Step 'create an agent'
    $created = Invoke-Api PUT '/controller/agents/update' @{ id = 0; name = 'smoke-test'; highTemp = 45; highTempTime = 15;
        robotType = -1; robotToken = ''; robotSecret = ''; alertRobotIds = $null } $token
    Assert-That ($created.code -eq 2000) 'creating the agent'
    $key = (Get-Agent $token).secretKey
    Assert-That ([bool]$key) 'the owner sees the agent key'

    Step 'check-phone.bat without a phone'
    Assert-That ((Invoke-Bat 'check-phone.bat') -eq 0) 'check-phone.bat succeeds'

    Step 'start the agent with its key'
    $env:ODC_AGENT_KEY = $key
    $agentConsole = Start-Process cmd.exe -ArgumentList '/c', "`"$Bundle\start-agent.bat`" < NUL" -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput "$Bundle\agent-console.log" -RedirectStandardError "$Bundle\agent-console.err.log"
    Assert-That (Wait-For 300 { (Get-Agent $token).status -eq 1 }) 'the agent comes online'
    Assert-That (Wait-For 30 { (Get-AgentLogins) -eq 1 }) 'the agent logged its login'
    $config = Get-Content -Raw (Join-Path $Bundle 'agent\config\application-sonic-agent.yml')
    Assert-That ($config.Contains("key: $key")) 'the key was saved in the agent config'

    Step 'restart the server; the agent reconnects'
    Assert-That ((Invoke-Bat 'stop-server.bat') -eq 0) 'stop-server.bat succeeds'
    foreach ($port in 3000, 8094, 8761) {
        Assert-That (-not (Test-Listening $port)) "port $port is closed"
    }
    Assert-That ((Invoke-Bat 'start-server.bat') -eq 0) 'start-server.bat succeeds again'
    $login = Invoke-Api POST '/controller/users/login' @{ userName = 'sonic'; password = $Password }
    Assert-That ($login.code -eq 2000) 'logging in again'
    $token = $login.data
    Assert-That (Wait-For 300 { (Get-AgentLogins) -ge 2 }) 'the agent logs in again'
    Assert-That ((Get-Agent $token).status -eq 1) 'the agent is online again'

    Step 'stop everything'
    Stop-Tree $agentConsole.Id
    $agentConsole = $null
    Assert-That ((Invoke-Bat 'stop-server.bat') -eq 0) 'stop-server.bat succeeds'
    Start-Sleep -Seconds 2
    Assert-That ((Get-BundleJava).Count -eq 0) 'no Java process of the bundle is left'
    Write-Host ''
    Write-Host 'The Windows bundle works.' -ForegroundColor Green
} catch {
    Write-Host $_ -ForegroundColor Red
    foreach ($log in @(Get-ChildItem "$Bundle\server\logs\*.log", "$Bundle\server\nginx\logs\error.log", "$Bundle\agent\logs\*.log",
            "$Bundle\agent-console*.log" -ErrorAction SilentlyContinue)) {
        Write-Host "---- $($log.FullName)" -ForegroundColor DarkGray
        Get-Content $log.FullName -Tail 60 | ForEach-Object { Write-Host $_ }
    }
    exit 1
} finally {
    if ($agentConsole) {
        Stop-Tree $agentConsole.Id
    }
    Stop-Bundle
}
