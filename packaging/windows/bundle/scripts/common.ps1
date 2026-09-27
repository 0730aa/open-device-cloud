# Shared by server.ps1 and agent.ps1 (Windows PowerShell 5.1, which every Windows 10 and 11 has).
# Saved as UTF-8 with a byte order mark: without it, Windows PowerShell misreads the Chinese text.

$Root = Split-Path -Parent $PSScriptRoot
$Java = Join-Path $Root 'jre\bin\java.exe'
# Everything checked here is on this PC, so a system proxy (e.g. one set by a VPN) must not be used.
[Net.WebRequest]::DefaultWebProxy = New-Object Net.WebProxy
# Windows PowerShell's progress bars make every web request slow.
$ProgressPreference = 'SilentlyContinue'

function Write-Step([string]$Text) { Write-Host ''; Write-Host "==> $Text" -ForegroundColor Cyan }
function Write-Note([string]$Text) { Write-Host "    $Text" }
function Write-Good([string]$Text) { Write-Host "    $Text" -ForegroundColor Green }
function Write-Hint([string]$Text) { Write-Host "    $Text" -ForegroundColor Yellow }

function Stop-WithError([string]$Text) {
    Write-Host ''
    Write-Host $Text -ForegroundColor Red
    exit 1
}

# nginx cannot run from a folder whose path has spaces or non-English characters.
function Assert-PlainPath {
    if ($Root -notmatch '^[A-Za-z0-9_.:\\-]+$') {
        Stop-WithError ("请把整个文件夹移到路径里只有英文字母和数字、没有空格的位置再运行，例如 D:\odc`n" +
            "当前位置：$Root")
    }
}

function Test-Port([string]$HostName, [int]$Port) {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $attempt.AsyncWaitHandle.WaitOne(1000)) {
            return $false
        }
        $client.EndConnect($attempt)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Get-PortOwner([int]$Port) {
    try {
        $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction Stop | Select-Object -First 1
        $process = Get-Process -Id $connection.OwningProcess -ErrorAction Stop
        return "$($process.ProcessName)，PID $($process.Id)"
    } catch {
        return '未知程序'
    }
}

# Runs a program and returns its output lines, stderr included, without Windows PowerShell turning
# what the program writes to stderr (e.g. adb starting its daemon) into errors.
function Invoke-Quietly([scriptblock]$Command) {
    $ErrorActionPreference = 'Continue'
    & $Command 2>&1 | ForEach-Object { "$_" }
}

function Write-Utf8File([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, (New-Object Text.UTF8Encoding($false)))
}

function Show-LogTail([string]$Path, [int]$Lines, [string]$Encoding = 'UTF8') {
    if (Test-Path $Path) {
        Write-Host "---- $Path ----" -ForegroundColor DarkGray
        Get-Content -Path $Path -Tail $Lines -Encoding $Encoding | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
    }
}
