# ---------------------------------------------------------------
#  远程服务端数据捞取（在远程服务器的 PowerShell 里整段运行）
#  产物：桌面 正式服数据_<时间>.zip
#  并尝试自动复制回本机（RDP 驱动器重定向生效时）
# ---------------------------------------------------------------

$ErrorActionPreference = 'Continue'
$srv   = 'C:\baidunetdiskdownload\20261002'
$stage = Join-Path $env:TEMP ('tangtu_pull_' + (Get-Date -Format 'HHmmss'))

Write-Host '=== 0) 环境检查 ===' -ForegroundColor Cyan
Write-Host ('  server root : ' + $srv + '   exists=' + (Test-Path $srv))

Write-Host '=== 1) 准备临时目录 ===' -ForegroundColor Cyan
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Write-Host '=== 2) 收集 config\kjbb ===' -ForegroundColor Cyan
if (Test-Path "$srv\config\kjbb") {
    Copy-Item "$srv\config\kjbb" $stage -Recurse -Force
    $n = (Get-ChildItem "$stage\kjbb" -File).Count
    Write-Host ('  kjbb : ' + $n + ' files')
} else {
    Write-Host '  config\kjbb NOT FOUND' -ForegroundColor Yellow
}

Write-Host '=== 3) 收集日志（最新 2 个 log + 最新 1 个 crash）===' -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "$stage\logs" | Out-Null
Get-ChildItem "$srv\logs" -Filter '*.log' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 2 |
    ForEach-Object {
        Copy-Item $_.FullName "$stage\logs" -Force
        Write-Host ('  log  : ' + $_.Name + '  ' + [math]::Round($_.Length/1KB,1) + ' KB')
    }
$crash = Get-ChildItem "$srv\crash-reports" -Filter '*.txt' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if ($crash) {
    New-Item -ItemType Directory -Force -Path "$stage\crash" | Out-Null
    Copy-Item $crash.FullName "$stage\crash" -Force
    Write-Host ('  crash: ' + $crash.Name)
}

Write-Host '=== 4) 模组清单 + 关键 jar 检查 ===' -ForegroundColor Cyan
Get-ChildItem "$srv\mods" -Filter '*.jar' | Select-Object -ExpandProperty Name |
    Sort-Object | Set-Content "$stage\mods清单.txt" -Encoding UTF8
$tangtu = Get-ChildItem "$srv\mods" -Filter '*Tangtu*' -ErrorAction SilentlyContinue
if ($tangtu) {
    $tangtu | ForEach-Object { '  JAR  ' + $_.Name + '  ' + $_.Length + ' B  ' + $_.LastWriteTime } |
        Set-Content "$stage\本模组jar.txt" -Encoding UTF8
    Write-Host ('  本模组 jar : ' + (($tangtu | ForEach-Object { $_.Name }) -join ', '))
} else {
    '  NO TangtuEconomical jar in mods\' | Set-Content "$stage\本模组jar.txt" -Encoding UTF8
    Write-Host '  本模组 jar : 缺失！' -ForegroundColor Red
}

Write-Host '=== 5) 数据包 tuning ===' -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "$stage\datapack" | Out-Null
$i = 0
Get-ChildItem -Path "$srv\world" -Filter 'default.json' -Recurse -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match 'kjbb\\tuning' } | ForEach-Object {
        $i++
        Copy-Item $_.FullName "$stage\datapack\tuning_$i.json" -Force
        Write-Host ('  ' + $_.FullName)
    }
if ($i -eq 0) { Write-Host '  未找到 kjbb\tuning 数据包' -ForegroundColor Yellow }

Write-Host '=== 6) 服务器信息 ===' -ForegroundColor Cyan
$info = @()
$info += 'server root : ' + $srv
$info += 'export time : ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
$info += 'mods count  : ' + (Get-ChildItem "$srv\mods" -Filter '*.jar').Count
$info += 'jar         : ' + (($tangtu | ForEach-Object { $_.Name + ' ' + $_.Length }) -join ' | ')
$info += 'java        : ' + ((Get-Process java -ErrorAction SilentlyContinue |
    ForEach-Object { 'PID=' + $_.Id + ' start=' + $_.StartTime }) -join ' | ')
$ver = (Select-String -Path "$srv\logs\latest.log" -Pattern 'TangtuEconomical \d+\.\d+\.\d+' -ErrorAction SilentlyContinue |
    Select-Object -Last 1).Line
$info += 'loaded ver  : ' + ($ver -replace '^\s+', '')
$info | Set-Content "$stage\服务器信息.txt" -Encoding UTF8
$info | ForEach-Object { Write-Host ('  ' + $_) }

Write-Host '=== 7) 打包 ===' -ForegroundColor Cyan
$zip = Join-Path ([Environment]::GetFolderPath('Desktop')) ('正式服数据_' + (Get-Date -Format 'MMdd-HHmm') + '.zip')
Remove-Item $zip -Force -ErrorAction SilentlyContinue
Compress-Archive -Path "$stage\*" -DestinationPath $zip -Force
$size = (Get-Item $zip).Length
$hash = (Get-FileHash $zip -Algorithm SHA256).Hash.Substring(0,16).ToLower()
Write-Host ('  zip  : ' + $zip)
Write-Host ('  size : ' + $size + ' B')
Write-Host ('  sha  : ' + $hash)

Write-Host '=== 8) 尝试直接回传到本机（RDP 驱动器重定向）===' -ForegroundColor Cyan
$targets = @('\\tsclient\C\dsh_inbox', '\\tsclient\U\dsh_inbox', '\\tsclient\C\Users\Administrator\Desktop\dsh_inbox')
$done = $false
foreach ($t in $targets) {
    try {
        if (-not (Test-Path -LiteralPath (Split-Path $t -Parent))) { continue }
        New-Item -ItemType Directory -Force -Path $t -ErrorAction Stop | Out-Null
        Copy-Item $zip $t -Force -ErrorAction Stop
        Write-Host ('  copied -> ' + $t) -ForegroundColor Green
        $done = $true
        break
    } catch {
        Write-Host ('  skip   ' + $t + '  (' + $_.Exception.Message + ')') -ForegroundColor DarkGray
    }
}
if (-not $done) {
    Write-Host '  未能自动回传（RDP 未开启驱动器重定向）。' -ForegroundColor Yellow
    Write-Host '  请手动把桌面上的 zip 复制到本机，或用 Ctrl+C / Ctrl+V。' -ForegroundColor Yellow
}

Write-Host ''
Write-Host '=== 完成 ===' -ForegroundColor Cyan
Write-Host ('  压缩包：' + $zip)
Write-Host ('  大小  ：' + $size + ' 字节')
Write-Host ('  SHA256：' + $hash)
Write-Host ''
Read-Host '按回车关闭'
