@echo off
chcp 65001 >nul
cd /d "%~dp0"
py -m venv .venv
call .venv\Scripts\activate
python -m pip install --upgrade pip
pip install -r requirements.txt
echo.
echo TTS 服务启动中： http://0.0.0.0:8765
echo 首次安装需要联网；请允许 Windows 防火墙访问专用网络。
python -m uvicorn tts_server:app --host 0.0.0.0 --port 8765
pause
