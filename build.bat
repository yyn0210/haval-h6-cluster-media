@echo off
REM 一键构建。需要 python 在 PATH 里（或用 py 启动器）。
cd /d "%~dp0"
python build_apk.py %*
if errorlevel 1 (
    py build_apk.py %*
)
pause
