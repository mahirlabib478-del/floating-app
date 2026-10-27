@echo off
set "GRADLE_VERSION=8.10.2"
set "GRADLE_BASE=%USERPROFILE%\.gradle\floatclip"
set "GRADLE_HOME=%GRADLE_BASE%\gradle-%GRADLE_VERSION%"

where gradle >nul 2>nul
if not errorlevel 1 (
    gradle %*
    exit /b %ERRORLEVEL%
)

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
    if not exist "%GRADLE_BASE%" mkdir "%GRADLE_BASE%"
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%GRADLE_BASE%\gradle.zip'; Expand-Archive -Path '%GRADLE_BASE%\gradle.zip' -DestinationPath '%GRADLE_BASE%' -Force"
    if errorlevel 1 exit /b %ERRORLEVEL%
)
call "%GRADLE_HOME%\bin\gradle.bat" %*
