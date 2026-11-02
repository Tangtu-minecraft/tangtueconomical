# 远程正式服读取脚本（云服务器 + PowerShell）
#
# 用法（在你的 PowerShell 窗口里跑）：
#   .\远程读取.ps1 -SshHost 1.2.3.4 -User root
#   .\远程读取.ps1 -SshHost 1.2.3.4 -User ubuntu -Port 22022
#   .\远程读取.ps1 -SshHost 1.2.3.4 -User root -RemoteRoot /opt/minecraft
#
# 前置条件（只需做一次）：
#   type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh root@IP "mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys"
#   （会让你输一次密码，之后全程免密）
#
# 做三件事：
#   ① 测通 SSH
#   ② 自动定位服务器目录（找 bot_ledger.txt）
#   ③ 把 config/kjbb、日志尾部、mods 清单、数据包参数拉回本地

param(
    [Parameter(Mandatory = $true)] [string] $SshHost,
    [Parameter(Mandatory = $true)] [string] $User,
    [int]    $Port = 22,
    [string] $RemoteRoot = "",
    [string] $OutRoot = "$env:USERPROFILE\Documents\远程正式服",
    [int]    $LogTail = 800,
    [switch] $SkipLogs
)

$ErrorActionPreference = 'Stop'
$sshOpts = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', '-o', 'StrictHostKeyChecking=accept-new', '-p', "$Port")
$target = ('{0}@{1}' -f $User, $SshHost)

function Invoke-Remote([string] $cmd) {
    & ssh @sshOpts $target $cmd 2>&1
}

Write-Host "== ① 测试 SSH 连接 ==" -ForegroundColor Cyan
$probe = Invoke-Remote "echo OK; uname -s; whoami"
if ($LASTEXITCODE -ne 0 -or -not ($probe -match 'OK')) {
    Write-Host "连接失败。输出如下：" -ForegroundColor Red
    $probe | ForEach-Object { Write-Host "   $_" }
    Write-Host ""
    Write-Host "常见原因：" -ForegroundColor Yellow
    Write-Host "  · 还没装公钥 → 先跑一次："
    Write-Host "      type `$env:USERPROFILE\.ssh\id_ed25519.pub | ssh $target `"mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys`""
    Write-Host "  · 端口不对 → 加 -Port"
    Write-Host "  · 安全组没放行该端口"
    exit 1
}
Write-Host "   连接正常：$($probe -join ' / ')" -ForegroundColor Green

Write-Host "== ② 定位服务器目录 ==" -ForegroundColor Cyan
if ([string]::IsNullOrWhiteSpace($RemoteRoot)) {
    $found = Invoke-Remote "find / -name 'bot_ledger.txt' -not -path '*/proc/*' 2>/dev/null | head -n 5"
    $line = ($found | Where-Object { $_ -match 'bot_ledger\.txt$' } | Select-Object -First 1)
    if (-not $line) {
        Write-Host "没找到 bot_ledger.txt，请手动指定 -RemoteRoot（服务器根目录）" -ForegroundColor Red
        exit 1
    }
    $RemoteRoot = ($line -replace '/config/kjbb/bot_ledger\.txt$', '')
    Write-Host "   自动识别：$RemoteRoot" -ForegroundColor Green
} else {
    Write-Host "   使用指定目录：$RemoteRoot" -ForegroundColor Green
}

Write-Host "== ③ 版本与目录结构 ==" -ForegroundColor Cyan
Invoke-Remote "ls -1 '$RemoteRoot' | head -n 30; echo '--- mods ---'; ls -1 '$RemoteRoot/mods' | grep -i tangtu" |
    ForEach-Object { Write-Host "   $_" }

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$outDir = Join-Path $OutRoot $stamp
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Write-Host "== ④ 拉取文件 → $outDir ==" -ForegroundColor Cyan

$scpOpts = @('-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new', '-P', "$Port", '-r')

# config/kjbb（账本、订单、挂单、流水、bots.json…）
& scp @scpOpts "${target}:${RemoteRoot}/config/kjbb" (Join-Path $outDir 'kjbb') 2>&1 | Out-Null
if (Test-Path (Join-Path $outDir 'kjbb')) {
    $n = (Get-ChildItem (Join-Path $outDir 'kjbb') -File).Count
    Write-Host "   config/kjbb：$n 个文件" -ForegroundColor Green
} else {
    Write-Host "   config/kjbb：拉取失败" -ForegroundColor Red
}

# 数据包参数（可能在世界目录下的 datapacks）
$dpOut = Join-Path $outDir 'datapack'
New-Item -ItemType Directory -Force -Path $dpOut | Out-Null
$dpPath = (Invoke-Remote "find '$RemoteRoot' -path '*kjbb/tuning/default.json' -not -path '*/proc/*' 2>/dev/null | head -n 3") |
    Where-Object { $_ -match 'default\.json$' } | Select-Object -First 1
if ($dpPath) {
    Write-Host "   数据包：$dpPath"
    & scp @scpOpts "${target}:${dpPath}" (Join-Path $dpOut 'tuning-default.json') 2>&1 | Out-Null
    Write-Host "   已拉取 tuning/default.json" -ForegroundColor Green
} else {
    Write-Host "   没有找到 tangtu 数据包（可能未安装）" -ForegroundColor Yellow
}

# 日志尾部
if (-not $SkipLogs) {
    $logDir = Join-Path $outDir 'logs'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $logs = Invoke-Remote "ls -1t '$RemoteRoot/logs'/*.log 2>/dev/null | head -n 3"
    foreach ($l in $logs) {
        $name = Split-Path $l -Leaf
        if ([string]::IsNullOrWhiteSpace($name)) { continue }
        Invoke-Remote "tail -n $LogTail '$l'" | Out-File -FilePath (Join-Path $logDir $name) -Encoding UTF8
        Write-Host "   日志：$name（尾 $LogTail 行）" -ForegroundColor Green
    }
}

Write-Host ""
Write-Host "== 完成 ==" -ForegroundColor Cyan
Write-Host "本地目录：$outDir"
Get-ChildItem $outDir | ForEach-Object { Write-Host "   $($_.Name)" }
Write-Host ""
Write-Host "把上面的目录路径发给我，我直接读本地的副本做核对。" -ForegroundColor Yellow
