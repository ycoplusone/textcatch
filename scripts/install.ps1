# textcatch 설치 + 권한 설정 스크립트 (USB 연결 기기)
# 사용: scripts\install.bat            (기기 1대 연결 시)
#       scripts\install.bat -Serial <시리얼>   (여러 대 연결 시)
param(
    [string]$Serial = "",
    # 프로젝트 빌드 결과물 사용
    [string]$Apk = "$PSScriptRoot\..\app\build\outputs\apk\debug\app-debug.apk"
    # 스크립트와 같은 폴더에 APK 를 두고 쓸 경우 (위 줄을 주석 처리하고 아래 줄 주석 해제)
    # [string]$Apk = "$PSScriptRoot\app-debug.apk"
)

$Pkg = "com.enfish.textcatch"
$Perms = @(
    "RECEIVE_SMS", "READ_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH",
    "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "POST_NOTIFICATIONS"
)

function Dev { if ($Serial) { & adb.exe -s $Serial @args } else { & adb.exe @args } }
function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }

# ---------- 1. 기기 확인 ----------
Step "기기 확인"
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { Write-Host "adb 가 PATH 에 없습니다." -ForegroundColor Red; exit 1 }
$devices = @(& adb.exe devices | Select-Object -Skip 1 | Where-Object { $_ -match "\tdevice$" } | ForEach-Object { ($_ -split "\t")[0] })
if ($devices.Count -eq 0) { Write-Host "연결된 기기가 없습니다. USB 디버깅 허용 여부를 확인하세요." -ForegroundColor Red; exit 1 }
if (-not $Serial) {
    if ($devices.Count -gt 1) { Write-Host "기기가 여러 대입니다. -Serial 로 지정하세요:`n$($devices -join "`n")" -ForegroundColor Red; exit 1 }
    $Serial = $devices[0]
}
$maker = (Dev shell getprop ro.product.manufacturer).Trim()
$model = (Dev shell getprop ro.product.model).Trim()
$ver = (Dev shell getprop ro.build.version.release).Trim()
$isXiaomi = $maker -match "Xiaomi|Redmi|POCO"
Write-Host "$Serial : $maker $model (Android $ver)"

if (-not (Test-Path $Apk)) { Write-Host "APK 없음: $Apk  (먼저 .\gradlew.bat assembleDebug)" -ForegroundColor Red; exit 1 }

# ---------- 2. 설치 ----------
Step "설치"
$out = (Dev install -r $Apk 2>&1 | Out-String)
Write-Host $out.Trim()
if ($out -match "Success") {
    Write-Host "설치 성공" -ForegroundColor Green
} elseif ($out -match "INSTALL_FAILED_USER_RESTRICTED") {
    # 샤오미: "USB를 통해 설치" 꺼짐(Mi 계정 필요) → 파일 복사 후 폰에서 직접 설치
    Write-Host "USB 설치가 막혀 있습니다. APK 를 폰으로 복사합니다." -ForegroundColor Yellow
    Dev push $Apk /sdcard/Download/textcatch.apk | Out-Null
    Write-Host ">> 폰의 파일 관리자 > Download > textcatch.apk 를 눌러 설치하세요. (설치 완료를 기다립니다, 최대 5분)" -ForegroundColor Yellow
    $before = (Dev shell dumpsys package $Pkg | Select-String "lastUpdateTime" | Select-Object -First 1) -as [string]
    $ok = $false
    for ($i = 0; $i -lt 100; $i++) {
        Start-Sleep -Seconds 3
        $now = (Dev shell dumpsys package $Pkg | Select-String "lastUpdateTime" | Select-Object -First 1) -as [string]
        if ($now -and $now -ne $before) { $ok = $true; break }
    }
    if (-not $ok) { Write-Host "설치가 감지되지 않았습니다." -ForegroundColor Red; exit 1 }
    Write-Host "설치 감지됨" -ForegroundColor Green
} elseif ($out -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Host "서명이 다른 기존 앱이 있습니다. 'adb uninstall $Pkg' 후 다시 실행하세요. (송신 이력/설정 삭제됨)" -ForegroundColor Red
    exit 1
} else {
    Write-Host "설치 실패" -ForegroundColor Red; exit 1
}

# ---------- 3. 제한된 설정 해제 (사이드로드 시 SMS 권한 자동 거부 방지) ----------
Step "제한된 설정 해제"
Dev shell appops set $Pkg ACCESS_RESTRICTED_SETTINGS allow

# ---------- 4. 권한 부여 ----------
Step "권한 부여 (pm grant)"
foreach ($p in $Perms) {
    $r = (Dev shell pm grant $Pkg "android.permission.$p" 2>&1 | Out-String).Trim()
    if ($r) { Write-Host "  $p : 실패 (폰에서 직접 허용 필요)" -ForegroundColor Yellow } else { Write-Host "  $p : OK" }
}

# ---------- 5. 상태 확인 ----------
Step "권한 상태"
$dump = Dev shell dumpsys package $Pkg
$missing = @()
foreach ($p in $Perms) {
    $line = $dump | Select-String "android.permission.$p`: granted=" | Select-Object -First 1
    $granted = "$line" -match "granted=true"
    if (-not $granted) { $missing += $p }
    Write-Host ("  {0,-20} {1}" -f $p, $(if ($granted) { "허용" } else { "거부" })) -ForegroundColor $(if ($granted) { "Green" } else { "Yellow" })
}

# ---------- 6. 앱 실행 + 수동 설정 안내 ----------
Step "앱 실행"
Dev shell am start -n "$Pkg/.MainActivity" | Out-Null

if ($missing.Count -gt 0) {
    Write-Host "`n>> 앱의 권한 요청 버튼으로 허용하세요: $($missing -join ', ')" -ForegroundColor Yellow
}
if ($isXiaomi) {
    Write-Host @"

>> 샤오미 수동 설정 (adb 로 불가) - 앱 정보 화면을 엽니다
   1. 자동 시작: 켜기
   2. 배터리 절약: 제한 없음
   3. 앱 권한 > 기타 권한: SMS 받기 / 알림 SMS 읽기 / MMS 읽기 -> 허용
   4. (선택) 최근 앱 화면에서 앱 잠금
"@ -ForegroundColor Yellow
    Start-Sleep -Seconds 2
    Dev shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:$Pkg" | Out-Null
} else {
    Write-Host "`n>> 권장: 설정 > 앱 > textcatch > 배터리 > '제한 없음'" -ForegroundColor Yellow
}

Write-Host "`n완료" -ForegroundColor Green
