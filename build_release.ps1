# CineIsle release 出包 + 复制到 F:\apk_release
# 用法（PowerShell，管理员非必需）：
#   .\build_release.ps1                 # 默认 arm64-v8a release
#   .\build_release.ps1 -Abi x86_64     # 换 ABI
#   .\build_release.ps1 -NoClean        # 跳过锁清理（构建正常时更快）

param(
  [string]$Abi = "arm64-v8a",
  [switch]$NoClean
)

$ErrorActionPreference = "Stop"
$ProjectDir = "D:\project\mpvEx-master"
$OutDir = "F:\apk_release"
$VersionName = "1.0.1"

$abiCode = @{ "armeabi-v7a" = 1; "arm64-v8a" = 2; "x86" = 3; "x86_64" = 4 }[$Abi]
$versionCode = 1 * 10 + $abiCode

Set-Location $ProjectDir

# 1) 收掉可能残留的守护进程：它们会占着 ~/.gradle/caches/.../locks，
#    导致后续任务报「拒绝访问」。
if (-not $NoClean) {
  Write-Host "[1/4] 停止 Gradle 守护进程并清理 transform 锁 ..." -ForegroundColor Cyan
  & .\gradlew.bat --stop 2>&1 | Out-Null
  Start-Sleep -Seconds 5
  $lockDir = Join-Path $env:USERPROFILE ".gradle\caches\9.6.0\transforms\.internal\locks"
  if (Test-Path $lockDir) {
    Get-ChildItem $lockDir -Filter *.lock -ErrorAction SilentlyContinue |
      ForEach-Object { Remove-Item $_.FullName -Force -ErrorAction SilentlyContinue }
  }
}

# 2) 打 release 包（走 keystore.properties 里的签名配置）
Write-Host "[2/4] 构建 StandardRelease / $Abi ..." -ForegroundColor Cyan
& .\gradlew.bat :app:assembleStandardRelease "-Pabi=$Abi" --console=plain
if ($LASTEXITCODE -ne 0) {
  Write-Host "构建失败，exit=$LASTEXITCODE" -ForegroundColor Red
  exit $LASTEXITCODE
}

# 3) 定位产物：ABI 体现在文件名里（app-standard-<abi>-release.apk）。
#    兼容两种输出布局：<abi>\release\ 子目录 或 直接在 standard\release\ 下。
$apkCandidates = @(
  (Join-Path $ProjectDir "app\build\outputs\apk\standard\$Abi\release\app-standard-$Abi-release.apk"),
  (Join-Path $ProjectDir "app\build\outputs\apk\standard\release\app-standard-$Abi-release.apk")
)
$apk = $apkCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apk) {
  $apk = Get-ChildItem (Join-Path $ProjectDir "app\build\outputs\apk") -Recurse -Filter "*$Abi*release*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notmatch "unsigned|\.idsig$" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
}
if (-not $apk -or -not (Test-Path $apk)) {
  Write-Host "找不到产物（ABI=$Abi）" -ForegroundColor Red
  Get-ChildItem (Join-Path $ProjectDir "app\build\outputs\apk") -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "  -> $($_.FullName)" }
  exit 1
}

# 4) 复制到 F:\apk_release，按项目命名约定带版本/ABI/时间戳，不覆盖旧包
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }
$stamp = Get-Date -Format "yyyyMMdd-HHmm"
$dest = Join-Path $OutDir "CineIsle-v${VersionName}_${versionCode}-$Abi-release-$stamp.apk"
Copy-Item -LiteralPath $apk -Destination $dest -Force

# 5) 校验签名
$sdk = "D:\project\AlistClientN\.android-sdk\build-tools\36.0.0\apksigner.bat"
if (Test-Path $sdk) {
  Write-Host "[3/4] 校验签名 ..." -ForegroundColor Cyan
  & $sdk verify --print-certs $dest 2>&1 | Select-Object -First 8
}

Write-Host "[4/4] 完成" -ForegroundColor Green
Write-Host "  源包: $apk"
Write-Host "  产物: $dest"
Write-Host "  大小: $([math]::Round((Get-Item $dest).Length / 1MB, 1)) MB"
