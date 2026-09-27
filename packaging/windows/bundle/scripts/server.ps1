# Starts or stops the server on this PC: the registry (eureka), the controller, file storage (folder),
# the gateway, and nginx serving the web client. Used by start-server.bat and stop-server.bat.
#
# Everything listens on 127.0.0.1 only. The first start asks for MySQL's root password and generates
# the server's secrets; server\settings.json keeps them (delete it to be asked again).
# For unattended runs, ODC_MYSQL_PASSWORD gives the password and ODC_NO_BROWSER=1 skips the browser.
param([ValidateSet('start', 'stop')][string]$Action = 'start')

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$Server = Join-Path $Root 'server'
$Logs = Join-Path $Server 'logs'
$Nginx = Join-Path $Server 'nginx'
$NginxExe = Join-Path $Nginx 'nginx.exe'
$SettingsFile = Join-Path $Server 'settings.json'
$RegistryPort = 8761
$GatewayPort = 8094
$WebPort = 3000
$Url = "http://127.0.0.1:$WebPort"

# Heap sizes that leave room for the agent, MySQL and a browser on a PC with 8 GB.
$Components = [ordered]@{
    eureka     = @{ Heap = '192m'; Args = @() }
    controller = @{ Heap = '512m'; Args = @('--eureka.instance.ip-address=127.0.0.1') }
    folder     = @{ Heap = '192m'; Args = @('--eureka.instance.ip-address=127.0.0.1') }
    gateway    = @{ Heap = '256m'; Args = @('--eureka.instance.ip-address=127.0.0.1', "--server.port=$GatewayPort") }
}

function New-Secret([int]$Bytes) {
    $buffer = New-Object byte[] $Bytes
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $random.GetBytes($buffer)
    } finally {
        $random.Dispose()
    }
    return (($buffer | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Read-Settings {
    if (Test-Path $SettingsFile) {
        return Get-Content -Raw -Encoding UTF8 $SettingsFile | ConvertFrom-Json
    }
    return [pscustomobject]@{
        mysqlHost        = '127.0.0.1'
        mysqlPort        = 3306
        mysqlDatabase    = 'sonic'
        mysqlUser        = 'root'
        mysqlPassword    = $null
        secretKey        = New-Secret 32
        registryPassword = New-Secret 24
    }
}

function Set-ServerEnvironment($Settings) {
    $env:SONIC_EUREKA_USERNAME = 'sonic'
    $env:SONIC_EUREKA_PASSWORD = $Settings.registryPassword
    $env:SONIC_EUREKA_HOST = '127.0.0.1'
    $env:SONIC_EUREKA_PORT = "$RegistryPort"
    $env:MYSQL_HOST = $Settings.mysqlHost
    $env:MYSQL_PORT = "$($Settings.mysqlPort)"
    $env:MYSQL_DATABASE = $Settings.mysqlDatabase
    $env:MYSQL_USERNAME = $Settings.mysqlUser
    # An empty value would remove the variable; an empty password goes on the command line instead.
    $env:MYSQL_PASSWORD = $Settings.mysqlPassword
    # application-jdbc.yml's URL, plus MySQL 8's default login over a connection without TLS.
    # DbCheck creates the database.
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://$($Settings.mysqlHost):$($Settings.mysqlPort)/$($Settings.mysqlDatabase)" +
        '?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&autoReconnect=true&allowPublicKeyRetrieval=true'
    $env:SONIC_SERVER_HOST = '127.0.0.1'
    $env:SONIC_SERVER_PORT = "$WebPort"
    $env:SECRET_KEY = $Settings.secretKey
    $env:EXPIRE_DAY = '14'
    $env:PERMISSION_ENABLE = 'true'
    $env:PERMISSION_SUPER_ADMIN = 'sonic'
    $env:REGISTER_ENABLE = 'true'
    $env:NORMAL_USER_ENABLE = 'true'
    $env:LDAP_USER_ENABLE = 'false'
}

function Read-MySqlPassword {
    if ($null -ne $env:ODC_MYSQL_PASSWORD) {
        return $env:ODC_MYSQL_PASSWORD
    }
    Write-Note '请输入安装 MySQL 时设置的 root 密码（输入时屏幕上不显示，输完按回车）'
    $secure = Read-Host -Prompt '    MySQL root 密码' -AsSecureString
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try {
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    }
}

# Logs in to MySQL the way the controller will and creates its database (see DbCheck.java), asking
# for the password until it works.
function Initialize-Database($Settings) {
    Write-Step '检查 MySQL'
    if (-not (Test-Port $Settings.mysqlHost $Settings.mysqlPort)) {
        Stop-WithError ("连不上 MySQL（$($Settings.mysqlHost):$($Settings.mysqlPort)）。`n" +
            "请确认已经安装 MySQL，并且它的服务在运行：按 Win+R，输入 services.msc 回车，找到 MySQL 开头的服务，右键「启动」。`n" +
            "如果你的 MySQL 用的不是 3306 端口，请用记事本修改 server\settings.json 里的 mysqlPort。")
    }
    $driver = Get-ChildItem (Join-Path $Server 'lib') -Filter 'mysql-connector-j-*.jar' | Select-Object -First 1
    $classpath = (Join-Path $Server 'tools') + ';' + $driver.FullName
    for ($attempt = 1; ; $attempt++) {
        if ($null -eq $Settings.mysqlPassword) {
            $Settings.mysqlPassword = Read-MySqlPassword
        }
        Set-ServerEnvironment $Settings
        # Java may print notes first (e.g. "Picked up _JAVA_OPTIONS"), so look for DbCheck's line.
        $output = @(Invoke-Quietly { & $Java -cp $classpath DbCheck })
        $code = $LASTEXITCODE
        $result = $output | Where-Object { $_ -match '^(OK|CHARSET|ERROR) ' } | Select-Object -First 1
        if (-not $result) {
            $result = $output -join ' '
        }
        if ($code -eq 0) {
            Write-Good "已连上 MySQL（版本 $($result -replace '^OK ', '')），使用数据库 $($Settings.mysqlDatabase)"
            Write-Utf8File $SettingsFile ($Settings | ConvertTo-Json)
            return
        }
        if ($code -eq 2) {
            Write-Utf8File $SettingsFile ($Settings | ConvertTo-Json)
            Stop-WithError ("MySQL 里已经有一个叫 $($Settings.mysqlDatabase) 的数据库，但它的字符集是 $($result -replace '^CHARSET ', '')，存不了中文。`n" +
                "请用记事本打开 server\settings.json，把 `"mysqlDatabase`": `"$($Settings.mysqlDatabase)`" 改成别的名字（例如 sonic_odc），保存后重新运行。")
        }
        if ($result -match '^ERROR 1045 ' -and $null -eq $env:ODC_MYSQL_PASSWORD -and $attempt -lt 5) {
            Write-Hint "MySQL 说密码不对（用户 $($Settings.mysqlUser)），请再输入一次。"
            $Settings.mysqlPassword = $null
            continue
        }
        Stop-WithError ("登录 MySQL 失败：$result`n" +
            "如果是密码不对（ERROR 1045），删除 server\settings.json 后重新运行，会再问一次密码。")
    }
}

# The server's programs: this bundle's nginx, and its Java running a component's argument file.
function Get-ServerProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'nginx.exe'" | ForEach-Object {
        if ($_.ExecutablePath -eq $NginxExe) {
            [pscustomobject]@{ Id = $_.ProcessId; Name = 'nginx' }
        } elseif ($_.ExecutablePath -eq $Java -and $_.CommandLine -match '@apps/(\w+)/java\.args') {
            [pscustomobject]@{ Id = $_.ProcessId; Name = $Matches[1] }
        }
    }
}

function Stop-Server {
    $processes = @(Get-ServerProcesses)
    foreach ($process in $processes) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    $deadline = (Get-Date).AddSeconds(15)
    while (@(Get-ServerProcesses).Count -gt 0 -and (Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 300
    }
    return $processes.Count
}

function Assert-PortFree([int]$Port) {
    if (Test-Port '127.0.0.1' $Port) {
        Stop-WithError "端口 $Port 已被其他程序占用（$(Get-PortOwner $Port)），请先关闭那个程序再运行。"
    }
}

function Start-Component([string]$Name) {
    $spec = $Components[$Name]
    $arguments = @('-Xms64m', "-Xmx$($spec.Heap)", '-XX:+UseSerialGC', '-XX:TieredStopAtLevel=1',
        '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=temp', "@apps/$Name/java.args", '--server.address=127.0.0.1') + $spec.Args
    if ($Name -eq 'controller' -and $Settings.mysqlPassword -eq '') {
        $arguments += '--spring.datasource.password='
    }
    Start-Process -FilePath $Java -ArgumentList $arguments -WorkingDirectory $Server -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $Logs "$Name.out.log") -RedirectStandardError (Join-Path $Logs "$Name.err.log") | Out-Null
}

function Stop-WithComponentFailure([string]$Name, [string]$Reason) {
    Write-Host ''
    $log = Join-Path $Logs "$Name.out.log"
    Show-LogTail $log 40
    Show-LogTail (Join-Path $Logs "$Name.err.log") 20
    Show-LogTail (Join-Path $Nginx 'logs\error.log') 10 'Default'
    $text = if (Test-Path $log) { Get-Content -Raw -Encoding UTF8 $log } else { '' }
    if ($text -match 'Access denied for user') {
        Write-Hint 'MySQL 密码不对：删除 server\settings.json 后重新运行，会再问一次密码。'
    } elseif ($text -match 'Communications link failure') {
        Write-Hint '连不上 MySQL：请确认 MySQL 服务在运行。'
    } elseif ($text -match 'already in use|Address already in use|BindException') {
        Write-Hint '有端口被其他程序占用了，请关闭占用的程序后重试。'
    } elseif ($text -match 'OutOfMemoryError') {
        Write-Hint '内存不够：请关闭一些程序后重试。'
    }
    Stop-Server | Out-Null
    Stop-WithError "$Name 启动失败：$Reason。完整日志在 server\logs 文件夹里。"
}

# Waits until $Ready (given $Name) returns true, and stops everything if a started program exits.
function Wait-Until([string]$Name, [int]$Seconds, [scriptblock]$Ready) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    Write-Host -NoNewline '    '
    while (-not (& $Ready $Name)) {
        $running = @(Get-ServerProcesses | ForEach-Object { $_.Name })
        foreach ($started in $script:Started) {
            if ($running -notcontains $started) {
                Stop-WithComponentFailure $started '进程意外退出'
            }
        }
        if ((Get-Date) -gt $deadline) {
            Stop-WithComponentFailure $Name "等了 $Seconds 秒还没有准备好"
        }
        Write-Host -NoNewline '.'
        Start-Sleep -Seconds 2
    }
    Write-Host ''
}

function Test-Http([string]$Uri, [hashtable]$Headers = @{}) {
    try {
        return (Invoke-WebRequest -Uri $Uri -Headers $Headers -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200
    } catch {
        return $false
    }
}

function Get-RegisteredApps {
    $auth = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("sonic:$($Settings.registryPassword)"))
    try {
        $registry = Invoke-RestMethod -Uri "http://127.0.0.1:$RegistryPort/eureka/apps" -TimeoutSec 5 `
            -Headers @{ Authorization = $auth; Accept = 'application/json' }
        return @($registry.applications.application | Where-Object { $_ } | ForEach-Object { $_.name.ToLower() })
    } catch {
        return @()
    }
}

function Open-Browser {
    if ($env:ODC_NO_BROWSER -ne '1') {
        Start-Process $Url
    }
}

function Start-Server {
    Assert-PlainPath
    $running = @(Get-ServerProcesses | ForEach-Object { $_.Name } | Sort-Object -Unique)
    if ($running.Count -eq $Components.Count + 1 -and (Test-Http "$Url/server/api/controller/users/loginConfig")) {
        Write-Good "服务端已经在运行：$Url"
        Open-Browser
        return
    }
    if ($running.Count -gt 0) {
        Write-Step '先停止上次没有完全启动或停止的服务端程序'
        Stop-Server | Out-Null
    }
    foreach ($folder in @($Logs, (Join-Path $Server 'temp'), (Join-Path $Nginx 'logs'), (Join-Path $Nginx 'temp'))) {
        New-Item -ItemType Directory -Force -Path $folder | Out-Null
    }

    $script:Settings = Read-Settings
    Initialize-Database $Settings
    foreach ($port in @($RegistryPort, $GatewayPort, $WebPort)) {
        Assert-PortFree $port
    }
    $script:Started = @()

    Write-Step '启动注册中心（1/3）'
    Start-Component 'eureka'
    $script:Started += 'eureka'
    Wait-Until 'eureka' 180 { Test-Http "http://127.0.0.1:$RegistryPort/actuator/health" }

    Write-Step '启动服务端组件（2/3），第一次启动要建数据库表，可能需要两三分钟'
    foreach ($name in @('controller', 'folder', 'gateway')) {
        Start-Component $name
        $script:Started += $name
    }
    Start-Process -FilePath $NginxExe -WorkingDirectory $Nginx -WindowStyle Hidden | Out-Null
    $script:Started += 'nginx'
    foreach ($component in @('controller', 'folder', 'gateway')) {
        Wait-Until $component 600 { param($name) (Get-RegisteredApps) -contains "sonic-server-$name" }
    }

    Write-Step '检查网页（3/3）'
    Wait-Until 'gateway' 120 { Test-Http "$Url/server/api/controller/users/loginConfig" }

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Green
    Write-Host " 服务端已启动：$Url" -ForegroundColor Green
    Write-Host ' 第一次使用：在登录页点「注 册」，用户名填 sonic（它是超级管理员）' -ForegroundColor Green
    Write-Host ' 这个窗口可以关掉，服务端会在后台继续运行' -ForegroundColor Green
    Write-Host ' 停止服务端：双击 stop-server.bat；日志在 server\logs' -ForegroundColor Green
    Write-Host '============================================================' -ForegroundColor Green
    Open-Browser
}

$Host.UI.RawUI.WindowTitle = '开放云真机 · 服务端'
if ($Action -eq 'stop') {
    $count = Stop-Server
    if ($count -gt 0) {
        Write-Good "服务端已停止（结束了 $count 个进程）"
    } else {
        Write-Good '服务端没有在运行'
    }
} else {
    Start-Server
}
