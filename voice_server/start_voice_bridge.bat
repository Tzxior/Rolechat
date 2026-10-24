@echo off
chcp 65001 >nul
cd /d "%~dp0"
if not exist .venv py -m venv .venv
call .venv\Scripts\activate
python -m pip install --upgrade pip
pip install -r requirements.txt
echo.
echo RoleChat Voice Bridge: http://0.0.0.0:8770
echo Configure GPT_SOVITS_REF_AUDIO and start GPT-SoVITS API on port 9880 first.
echo LM_STUDIO_URL defaults to http://127.0.0.1:1234/v1
echo For another LM Studio port, set LM_STUDIO_URL before launching this script.
echo Allow TCP 8770 on Windows Private networks only.
python -m uvicorn voice_bridge:app --host 0.0.0.0 --port 8770
pause
