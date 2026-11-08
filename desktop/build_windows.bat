@echo off
setlocal
cd /d "%~dp0"

set "PY=python"
py -3.12 --version >nul 2>&1
if not errorlevel 1 (
    set "PY=py -3.12"
) else (
    py -3 --version >nul 2>&1
    if not errorlevel 1 set "PY=py -3"
)

echo Using Python:
%PY% --version
if errorlevel 1 goto failed

echo.
echo Installing PyInstaller...
%PY% -m pip install --upgrade pyinstaller
if errorlevel 1 goto failed

echo.
echo Building FloatClip TXT...
%PY% -m PyInstaller --noconfirm --clean --onefile --windowed --name FloatClipTXT --distpath dist floatclip_txt.py
if errorlevel 1 goto failed

echo.
echo Build complete:
echo %CD%\dist\FloatClipTXT.exe
pause
exit /b 0

:failed
echo.
echo Build failed. Check the error shown above.
pause
exit /b 1
