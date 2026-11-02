# 在【远程服务器】的 PowerShell 里运行这个脚本
# 作用：自动找到服务器目录，打包 config\kjbb + 日志 + mods 清单 + 数据包参数
#
# 用法（远程机器上）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File 远程打包.ps1
# 或者直接把本文件内容整段粘进远程的 PowerShell 窗口

$ErrorActionPreference = 'Continue'

Write-Host "== ① 查找服务器目录 ==" -ForegroundColor Cyan
$ledger = Get-ChildItem -Path 'C:\','D:\','E:\' -Filter 'bot_ledger.txt' -Recurse -ErrorAction SilentlyContinue |
          Select-Object -First 5
if (-not $ledger) {
    Write-Host "没找到 bot_ledger.txt" -ForegroundColor Red
    Write-Host "请手动把 \$srvRoot 改成服务器根目录后重跑" -ForegroundColor Yellow
    $srvRoot = Read-Host "服务器根目录（例如 C:\mc\server）"
} else {
    $srvRoot = Split-Path (Split-Path (Split-Path $ledger[0].FullName -Parent) -Parent) -Parent
    Write-Host "  找到：$srvRoot" -ForegroundColor Green
    $ledger | ForEach-Object { Write-Host "    $($_.FullName)" }
}

Write-Host "== ② 收集文件 ==" -ForegroundColor Cyan
$stage = Join-Path $env:TEMP ("tangtu_dump_" + (Get-Date -Format 'HHmmss'))
New-Item -ItemType Directory -Force -Path $stage | Out-Null

# config\kjbb
$kj = Join-Path $srvRoot 'config\kjbb'
if (Test-Path $kj) {
    Copy-Item $kj -Destination $stage -Recurse -Force
    Write-Host "  config\kjbb ✔ $((Get-ChildItem $kj -File).Count) 个文件" -ForegroundColor Green
} else {
    Write-Host "  config\kjbb 不存在" -ForegroundColor Yellow
}

# 日志（最新的两个）
$logDir = Join-Path $srvRoot 'logs'
if (Test-Path $logDir) {
    $logs = Get-ChildItem $logDir -Filter '*.log' | Sort-Object LastWriteTime -Descending | Select-Object -First 2
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'logs') | Out-Null
    foreach ($l in $logs) {
        Copy-Item $l.FullName -Destination (Join-Path $stage 'logs') -Force
        Write-Host "  logs\$($l.Name) ✔ $([math]::Round($l.Length/1KB,1)) KB" -ForegroundColor Green
    }
}

# mods 清单（只记名字，不拷 jar）
$modsDir = Join-Path $srvRoot 'mods'
if (Test-Path $modsDir) {
    Get-ChildItem $modsDir -Filter '*.jar' | Select-Object -ExpandProperty Name |
        Sort-Object | Set-Content (Join-Path $stage 'mods清单.txt') -Encoding UTF8
    Write-Host "  mods清单.txt ✔" -ForegroundColor Green
}

# 数据包参数
Write-Host "== ③ 查找数据包 tuning ==" -ForegroundColor Cyan
$tunings = Get-ChildItem -Path $srvRoot -Filter 'default.json' -Recurse -ErrorAction SilentlyContinue |
           Where-Object { $_.FullName -match 'kjbb\\tuning' }
if ($tunings) {
    New-Item -ItemType Directory -Force -Path (Join-Path $stage 'datapack') | Out-Null
    $i = 0
    foreach ($t in $tunings) {
        $i++
        Copy-Item $t.FullName -Destination (Join-Path $stage "datapack\tuning_$i.json") -Force
        Write-Host "  $($t.FullName)" -ForegroundColor Green
    }
} else {
    Write-Host "  没有找到 kjbb\tuning 数据包" -ForegroundColor Yellow
}

# 服务器信息
$info = @()
$info += "服务器目录: $srvRoot"
$info += "导出时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$jar = Get-ChildItem $modsDir -Filter 'Tangtu*.jar' -ErrorAction SilentlyContinue
$info += "本模组 jar: " + $(if ($jar) { ($jar | ForEach-Object { $_.Name }) -join ', ' } else { '未找到' })
$info += "Java 进程:"
$info += (Get-Process java -ErrorAction SilentlyContinue | Select-Object Id, StartTime | Format-Table -AutoSize | Out-String)
$info | Set-Content (Join-Path $stage '服务器信息.txt') -Encoding UTF8

Write-Host "== ④ 打包 ==" -ForegroundColor Cyan
$zip = Join-Path ([Environment]::GetFolderPath('Desktop')) ("正式服数据_" + (Get-Date -Format 'MMdd-HHmm') + ".zip")
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $zip -Force
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "== 完成 ==" -ForegroundColor Cyan
Write-Host "  压缩包：$zip"
Write-Host "  大小：  $([math]::Round((Get-Item $zip).Length/1KB,1)) KB"
Write-Host ""
Write-Host "下一步：把这个 zip 通过远程桌面的剪贴板 / 驱动器映射 复制到本机" -ForegroundColor Yellow
