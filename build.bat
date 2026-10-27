@echo off
chcp 65001 > nul
cd /d "%~dp0"
python -m PyInstaller --noconfirm --onefile --windowed --uac-admin ^
  --name "Запрет от Фузика" --icon "%~dp0icon.ico" ^
  --exclude-module numpy zapret_fuzik.py
