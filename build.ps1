param(
    [string]$GameRoot = "U:\PCL 正式版 2.9.3\.minecraft",
    [string]$Instance = "大唐盛兔1008",
    [string]$JavaHome = "C:\Program Files\Java\jdk-21.0.12.1"
)

$ErrorActionPreference = "Stop"
$javac = Join-Path $JavaHome "bin\javac.exe"
$jarExe = Join-Path $JavaHome "bin\jar.exe"

if (-not (Test-Path $javac)) { throw "javac not found: $javac" }

$proj = $PSScriptRoot
$modsDir = Join-Path $GameRoot "versions\$Instance\mods"
$libDir = Join-Path $GameRoot "libraries"
$buildDir = Join-Path $proj "build"
$classDir = Join-Path $buildDir "classes"
$distDir = Join-Path $proj "dist"

Write-Host "=== 1/5 清理 ==="
if (Test-Path $buildDir) { Remove-Item $buildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $classDir | Out-Null
New-Item -ItemType Directory -Force -Path $distDir | Out-Null

Write-Host "=== 2/5 收集 classpath ==="
$jarList = New-Object System.Collections.Generic.List[string]
Get-ChildItem $libDir -Recurse -Filter *.jar -ErrorAction SilentlyContinue | ForEach-Object { $jarList.Add($_.FullName) }
foreach ($pat in @("SC-kubejs-neoforge-*.jar", "SC-rhino-*.jar", "banksystem-neoforge-*.jar")) {
    Get-ChildItem $modsDir -Filter $pat -ErrorAction SilentlyContinue | ForEach-Object { $jarList.Add($_.FullName) }
}
$cp = ($jarList | Select-Object -Unique) -join ';'
Write-Host ("    jars: " + $jarList.Count)

Write-Host "=== 3/5 编译 ==="
$sources = Get-ChildItem (Join-Path $proj "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Write-Host ("    sources: " + $sources.Count)
$argFile = Join-Path $buildDir "javac.args"
$lines = @()
$lines += "-d `"$classDir`""
$lines += "-encoding UTF-8"
$lines += "-source 21"
$lines += "-target 21"
$lines += "-nowarn"
$lines += ("-classpath `"" + $cp + "`"")
foreach ($s in $sources) { $lines += "`"$s`"" }
Set-Content -Path $argFile -Value $lines -Encoding UTF8

& $javac "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }

Write-Host "=== 4/5 打资源 ==="
Copy-Item (Join-Path $proj "src\main\resources\*") $classDir -Recurse -Force

Write-Host "=== 5/5 打包 ==="
$outJar = Join-Path $distDir "kjbankbridge-1.0.0.jar"
if (Test-Path $outJar) { Remove-Item $outJar -Force }
& $jarExe --create --file $outJar --manifest="$((Join-Path $proj 'MANIFEST.MF'))" -C $classDir .
if ($LASTEXITCODE -ne 0) { throw "jar failed: $LASTEXITCODE" }

Write-Host ""
Write-Host ("OK -> " + $outJar + "  (" + [math]::Round((Get-Item $outJar).Length/1KB,1) + " KB)")
