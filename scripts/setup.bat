@echo off
rem textcatch 설치 + 권한 + 배터리 설정 (순수 cmd, PowerShell 불필요)
rem 사용법: setup.bat [APK경로]   (기본: 이 bat 과 같은 폴더의 app-debug.apk)
chcp 65001 >nul
setlocal

set "PKG=com.enfish.textcatch"
set "APK=%~1"
if "%APK%"=="" set "APK=%~dp0app-debug.apk"
set "INSTALL_RESULT=실행 안 됨"

if not exist "%APK%" (
    echo [오류] APK 파일이 없습니다: %APK%
    goto :end
)

echo ===== 1. 연결 해지 및 확인 =====
adb disconnect
adb devices
adb get-state >nul 2>&1
if errorlevel 1 (
    echo [오류] 연결된 기기가 없거나 2대 이상입니다. USB 연결/디버깅 허용을 확인하세요.
    goto :end
)

echo.
echo ===== 2. 설치: %APK% =====
adb install -r "%APK%"
if errorlevel 1 (
    set "INSTALL_RESULT=실패 (위 adb 메시지 확인)"
    goto :result
)
set "INSTALL_RESULT=성공"

echo.
echo ===== 3. 제한된 설정 해제 (이후 폰에서 SMS 권한 다시 허용) =====
adb shell appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow

echo.
echo ===== 4. 권한 부여 =====
for %%P in (RECEIVE_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH READ_PHONE_STATE READ_PHONE_NUMBERS) do (
    adb shell pm grant %PKG% android.permission.%%P >nul 2>&1
    if errorlevel 1 (echo   [실패] %%P) else (echo   [허용] %%P)
)

echo.
echo ===== 5. 배터리 / 백그라운드 =====
adb shell dumpsys deviceidle whitelist +%PKG%
adb shell cmd appops set %PKG% RUN_IN_BACKGROUND allow
adb shell cmd appops set %PKG% RUN_ANY_IN_BACKGROUND allow
adb shell am set-standby-bucket %PKG% active

:result
echo.
echo ===== 설치 결과 =====
echo 이번 설치: %INSTALL_RESULT%
adb shell pm list packages %PKG% | findstr /x "package:%PKG%" >nul
if errorlevel 1 (
    echo 기기 상태: 미설치 - %PKG% 패키지가 없습니다
    goto :end
)
echo 기기 상태: 설치됨
adb shell dumpsys package %PKG% | findstr /c:"versionName=" /c:"versionCode=" /c:"lastUpdateTime="

:end
echo.
endlocal
pause
