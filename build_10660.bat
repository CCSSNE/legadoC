@echo off
chcp 65001 >nul
setlocal

set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
set "ANDROID_HOME=D:\AI\audio\android-sdk"
set "ANDROID_SDK_ROOT=D:\AI\audio\android-sdk"
set "GRADLE_USER_HOME=D:\AI\audio\android-gradle-user-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools;%PATH%"

set VERSION_CODE=10660
set VERSION_NAME=3.26.081736

echo Starting build for version %VERSION_CODE% / %VERSION_NAME%c
echo.

call gradlew.bat :app:assembleAppC -Pabi=arm64-v8a -PVERSION_CODE=%VERSION_CODE% -PVERSION_NAME=%VERSION_NAME% --console=plain --warning-mode=summary > build_10660.log 2>&1

set BUILD_EXIT_CODE=%ERRORLEVEL%
echo Build exit code: %BUILD_EXIT_CODE% >> build_10660.log
exit /b %BUILD_EXIT_CODE%
