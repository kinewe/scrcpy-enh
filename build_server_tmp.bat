@echo off
set JAVA_HOME=F:\jdk17
set ANDROID_HOME=F:\Android
set ANDROID_SDK_ROOT=F:\Android
cd /d "D:\Hermes save\yinmo\scrcpy\build\scrcpy-src"
call gradlew.bat -p server --no-daemon assembleRelease
exit /b %errorlevel%
