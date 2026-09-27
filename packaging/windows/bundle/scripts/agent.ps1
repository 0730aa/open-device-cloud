# Checks the phone connection and runs the agent in this window (closing the window stops it).
# The first run asks for the agent's key and saves it in agent\config\application-sonic-agent.yml.
# Used by start-agent.bat, and by check-phone.bat with -PhoneOnly.
# For unattended runs, ODC_AGENT_KEY gives the key.
param([switch]$PhoneOnly)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')

$Agent = Join-Path $Root 'agent'
$Adb = Join-Path $Agent 'plugins\adb.exe'
$Config = Join-Path $Agent 'config\application-sonic-agent.yml'
$KeyPlaceholder = 'PASTE-YOUR-AGENT-KEY-HERE'
$AgentPort = 7777
$ServerPort = 3000

function Show-Phones {
    Write-Step '检查手机连接'
    $phones = @(Invoke-Quietly { & $Adb devices } | Where-Object { $_ -match '^(\S+)\t(.+)$' } |
        ForEach-Object { [pscustomobject]@{ Serial = $Matches[1]; State = $Matches[2] } })
    foreach ($phone in $phones) {
        switch ($phone.State) {
            'device' {
                $model = (Invoke-Quietly { & $Adb -s $phone.Serial shell getprop ro.product.model }) -join ''
                $android = (Invoke-Quietly { & $Adb -s $phone.Serial shell getprop ro.build.version.release }) -join ''
                Write-Good "已连接：$model，Android $android（$($phone.Serial)）"
            }
            'unauthorized' {
                Write-Hint "手机 $($phone.Serial) 还没有允许这台电脑调试：请看手机屏幕，在「允许 USB 调试吗？」里勾选「始终允许」，再点「允许」。"
                Write-Hint '没有看到提示的话：拔掉数据线重新插上；或在开发者选项里点「撤销 USB 调试授权」后再插。'
            }
            'offline' {
                Write-Hint "手机 $($phone.Serial) 处于离线状态：拔掉数据线重新插上，不行就重启手机。"
            }
            default {
                Write-Hint "手机 $($phone.Serial) 的状态是 $($phone.State)，请重启手机后再试。"
            }
        }
    }
    if ($phones.Count -eq 0) {
        Write-Hint '没有检测到手机。请逐项检查：'
        Write-Hint '  1. 用能传数据的数据线连接（有些线只能充电），插拔后通知栏的 USB 用途选「传输文件」'
        Write-Hint '  2. 手机已打开「开发者选项」和其中的「USB 调试」'
        Write-Hint '  3. 关闭电脑上的手机助手类软件（它们会抢占 adb）'
        Write-Hint '  4. 仍然不行：在设备管理器里看手机是否有黄色感叹号，需要安装该品牌手机的 USB 驱动'
    }
    return $phones.Count
}

function Set-AgentKey {
    $text = [IO.File]::ReadAllText($Config)
    if (-not $text.Contains($KeyPlaceholder)) {
        return
    }
    Write-Step '设置 Agent 的 Key（只需要一次）'
    Write-Note '在网页顶部点「设备中心」，切到「Agent中心」，点「新增Agent」，名字随意，保存后复制那一行的 Key，粘贴到这里（在窗口里点右键就是粘贴）。'
    $key = $env:ODC_AGENT_KEY
    while (-not $key) {
        $answer = (Read-Host -Prompt '    Key').Trim()
        if ($answer -match '^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$') {
            $key = $answer
        } else {
            Write-Hint 'Key 的样子类似 5aa13292-b9a8-408c-a091-d784d1f37472，请重新粘贴。'
        }
    }
    Write-Utf8File $Config ($text.Replace($KeyPlaceholder, $key))
    Write-Good '已保存到 agent\config\application-sonic-agent.yml'
}

$Host.UI.RawUI.WindowTitle = '开放云真机 · Agent（关闭窗口即停止）'
Assert-PlainPath
$phoneCount = Show-Phones
if ($PhoneOnly) {
    exit 0
}
Set-AgentKey
if (-not (Test-Port '127.0.0.1' $ServerPort)) {
    Write-Hint "还连不上服务端（127.0.0.1:$ServerPort）。请先双击 start-server.bat；Agent 会一直自动重连。"
}
if (Test-Port '127.0.0.1' $AgentPort) {
    Stop-WithError "端口 $AgentPort 已被占用（$(Get-PortOwner $AgentPort)），可能已经有一个 Agent 窗口在运行。"
}
foreach ($folder in @('logs', 'temp')) {
    New-Item -ItemType Directory -Force -Path (Join-Path $Agent $folder) | Out-Null
}

Write-Step '启动 Agent（关闭这个窗口就会停止 Agent）'
if ($phoneCount -eq 0) {
    Write-Note '现在插上手机也可以，Agent 会自动发现。'
}
Write-Note "网页里「设备中心」出现你的手机后，点「马上使用」就能远程控制。日志在 agent\logs。"
Set-Location $Agent
# The helper libraries unpack native code into their cache and temp folders; keep those in this
# folder, whose path is plain, rather than under a user profile whose path may not be.
& $Java -Xms128m -Xmx768m '-Djava.io.tmpdir=temp' '-Dorg.bytedeco.javacpp.cachedir=javacpp' -jar 'sonic-agent-windows-x86_64.jar'
exit $LASTEXITCODE
