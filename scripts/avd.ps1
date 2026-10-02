# textcatch 에뮬레이터(AVD) 실행 스크립트
# 사용: scripts\avd.bat                     (AVD 실행 + 부팅 대기)
#       scripts\avd.bat -Install            (+ APK 설치 + 권한 부여 + 앱 실행)
#       scripts\avd.bat -Cold               (스냅샷 무시하고 새로 부팅)
#       scripts\avd.bat -Avd <이름>          (AVD 지정, 기본: 목록의 첫 번째)
param(
    [string]$Avd = "",
    [switch]$Cold,
    [switch]$Install,
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk",
    [int]$TimeoutSec = 180
)

$Pkg = "com.enfish.textcatch"
$Perms = @(
    "RECEIVE_SMS", "READ_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH",
    "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "POST_NOTIFICATIONS"
)

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Fail($msg) { Write-Host $msg -ForegroundColor Red; exit 1 }

# ---------- 1. SDK 경로 ----------
$Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "$env:LOCALAPPDATA\Android\Sdk" }
$Emulator = "$Sdk\emulator\emulator.exe"
$Adb = "$Sdk\platform-tools\adb.exe"
if (-not (Test-Path $Emulator)) { Fail "emulator.exe 없음: $Emulator" }
if (-not (Test-Path $Adb)) { Fail "adb.exe 없음: $Adb" }

function Emus { @(& $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "^emulator-\d+\t" } | ForEach-Object { ($_ -split "\t")[0] }) }

# ---------- 2. AVD 실행 (이미 실행 중이면 재사용) ----------
Step "AVD 실행"
& $Adb start-server | Out-Null
$running = @(Emus)
if ($running.Count -gt 0) {
    $Serial = $running[0]
    Write-Host "이미 실행 중: $Serial"
} else {
    $avds = @(& $Emulator -list-avds | Where-Object { $_ -and $_ -notmatch "^INFO" })
    if ($avds.Count -eq 0) { Fail "AVD 가 없습니다. Android Studio Device Manager 또는 avdmanager 로 만드세요." }
    if (-not $Avd) { $Avd = $avds[0] }
    elseif ($avds -notcontains $Avd) { Fail "AVD '$Avd' 없음. 목록:`n$($avds -join "`n")" }

    $emuArgs = @("-avd", $Avd)
    if ($Cold) { $emuArgs += "-no-snapshot-load" }
    Write-Host "실행: $Avd $(if ($Cold) { '(콜드 부팅)' })"
    Start-Process -FilePath $Emulator -ArgumentList $emuArgs -WindowStyle Minimized | Out-Null

    $Serial = $null
    $deadline = (Get-Date).AddSeconds(60)
    while (-not $Serial -and (Get-Date) -lt $deadline) {
        Start-Sleep -Seconds 2
        $Serial = (Emus | Select-Object -First 1)
    }
    if (-not $Serial) { Fail "에뮬레이터가 adb 에 나타나지 않습니다." }
}

# ---------- 3. 부팅 대기 ----------
Step "부팅 대기 ($Serial)"
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$booted = $false
while ((Get-Date) -lt $deadline) {
    $v = (& $Adb -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
    if ($v -eq "1") { $booted = $true; break }
    Write-Host "." -NoNewline
    Start-Sleep -Seconds 2
}
Write-Host ""
if (-not $booted) { Fail "부팅 시간 초과 (${TimeoutSec}s). -Cold 로 다시 시도해 보세요." }
Write-Host "부팅 완료" -ForegroundColor Green

if (-not $Install) {
    Write-Host "`n>> 설치까지 하려면: scripts\avd.bat -Install" -ForegroundColor Yellow
    Write-Host ">> SMS 테스트:      adb -s $Serial emu sms send 01012345678 `"Test SMS`"" -ForegroundColor Yellow
    exit 0
}

# ---------- 4. 설치 ----------
Step "설치"
if (-not (Test-Path $Apk)) { Fail "APK 없음: $Apk  (먼저 .\gradlew.bat assembleDebug)" }
$out = (& $Adb -s $Serial install -r $Apk 2>&1 | Out-String)
Write-Host $out.Trim()
if ($out -notmatch "Success") { Fail "설치 실패" }

# ---------- 5. 권한 부여 ----------
Step "권한 부여 (pm grant)"
foreach ($p in $Perms) {
    $r = (& $Adb -s $Serial shell pm grant $Pkg "android.permission.$p" 2>&1 | Out-String).Trim()
    if ($r) { Write-Host "  $p : 실패 ($r)" -ForegroundColor Yellow } else { Write-Host "  $p : OK" }
}

# ---------- 6. 앱 실행 ----------
Step "앱 실행"
& $Adb -s $Serial shell am start -n "$Pkg/.MainActivity" | Out-Null

Write-Host "`n>> SMS 테스트: adb -s $Serial emu sms send 01012345678 `"Test SMS`"" -ForegroundColor Yellow
Write-Host ">> 로그:       adb -s $Serial logcat -s SMSReceiver:D ForwardWorker:D" -ForegroundColor Yellow
Write-Host "`n완료" -ForegroundColor Green
