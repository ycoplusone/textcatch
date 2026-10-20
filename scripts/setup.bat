@echo off
rem textcatch 설치 + 확인 + 권한 설정 (APK: 이 bat 과 같은 폴더의 app-debug.apk)

echo ===== 1. Installing =====
adb install -r "%~dp0app-debug.apk"

echo.
echo ===== 2. Install Check  =====
adb shell dumpsys package com.enfish.textcatch | findstr /c:"versionName=" /c:"versionCode=" /c:"lastUpdateTime="

echo.
echo ===== 3. Permission Setup =====
adb shell appops set com.enfish.textcatch ACCESS_RESTRICTED_SETTINGS allow
for %%P in (RECEIVE_SMS READ_SMS RECEIVE_MMS RECEIVE_WAP_PUSH READ_PHONE_STATE READ_PHONE_NUMBERS) do (
    adb shell pm grant com.enfish.textcatch android.permission.%%P >nul 2>&1
    if errorlevel 1 (echo   [Fail] %%P) else (echo   [Success] %%P)
)

echo.
pause
