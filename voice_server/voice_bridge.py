import base64
import io
import os
import tempfile
from pathlib import Path

import httpx
import soundfile as sf
import numpy as np
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse
from faster_whisper import WhisperModel

app = FastAPI(title="RoleChat Voice Bridge", version="0.1.0")

LM_STUDIO_URL = os.getenv("LM_STUDIO_URL", "http://127.0.0.1:1234/v1").rstrip("/")
LM_STUDIO_MODEL = os.getenv("LM_STUDIO_MODEL", "")
GPT_SOVITS_URL = os.getenv("GPT_SOVITS_URL", "http://127.0.0.1:9880").rstrip("/")
GPT_SOVITS_REF_AUDIO = os.getenv("GPT_SOVITS_REF_AUDIO", "")
GPT_SOVITS_PROMPT_TEXT = os.getenv("GPT_SOVITS_PROMPT_TEXT", "")
GPT_SOVITS_PROMPT_LANG = os.getenv("GPT_SOVITS_PROMPT_LANG", "zh")
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
whisper = None


def get_whisper():
    global whisper
    if whisper is None:
        # CPU is the compatibility default; set WHISPER_DEVICE=cuda only when configured.
        whisper = WhisperModel(
            WHISPER_MODEL,
            device=os.getenv("WHISPER_DEVICE", "cpu"),
            compute_type=os.getenv("WHISPER_COMPUTE_TYPE", "int8"),
        )
    return whisper


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "rolechat-voice-bridge",
        "lm_studio_url": LM_STUDIO_URL,
        "gpt_sovits_configured": bool(GPT_SOVITS_REF_AUDIO),
    }


@app.post("/voice/turn")
async def voice_turn(
    audio: UploadFile = File(...),
    role_name: str = Form("知心朋友"),
    role_prompt: str = Form("请用自然口语中文简短回复。"),
    model: str = Form(""),
    history_json: str = Form("[]"),
):
    """One call turn: transcribe an uploaded WAV, ask LM Studio, synthesize with GPT-SoVITS."""
    raw = await audio.read()
    if not raw:
        raise HTTPException(400, "没有收到音频")
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
            f.write(raw)
            wav_path = f.name
        try:
            segments, _ = get_whisper().transcribe(
                wav_path, language="zh", vad_filter=True, beam_size=1
            )
            transcript = "".join(s.text for s in segments).strip()
        finally:
            Path(wav_path).unlink(missing_ok=True)
    except Exception as exc:
        raise HTTPException(500, f"语音识别失败：{exc}")
    if not transcript:
        return JSONResponse({"transcript": "", "answer": "", "audio_base64": ""})

    try:
        import json
        history = json.loads(history_json)
        if not isinstance(history, list):
            history = []
    except Exception:
        history = []
    messages = [
        {"role": "system", "content": role_prompt + "\n你正在和用户进行语音通话。使用自然口语中文，回答简短，适合直接朗读。当前角色：" + role_name},
    ]
    for item in history[-12:]:
        if isinstance(item, dict) and item.get("role") in ("user", "assistant"):
            messages.append({"role": item["role"], "content": str(item.get("content", ""))[:4000]})
    messages.append({"role": "user", "content": transcript})
    try:
        async with httpx.AsyncClient(timeout=180) as client:
            models_response = None
            chosen_model = model.strip() or LM_STUDIO_MODEL
            if not chosen_model:
                models_response = await client.get(f"{LM_STUDIO_URL}/models")
                models_response.raise_for_status()
                data = models_response.json().get("data", [])
                if not data:
                    raise RuntimeError("LM Studio 没有已加载模型")
                chosen_model = data[0]["id"]
            response = await client.post(
                f"{LM_STUDIO_URL}/chat/completions",
                json={"model": chosen_model, "messages": messages, "temperature": 0.8,
                      "max_tokens": 350, "stream": False},
            )
            response.raise_for_status()
            answer = response.json()["choices"][0]["message"]["content"].strip()
    except Exception as exc:
        raise HTTPException(502, f"LM Studio 请求失败：{exc}")

    if not GPT_SOVITS_REF_AUDIO:
        raise HTTPException(500, "请先配置 GPT_SOVITS_REF_AUDIO（参考音频文件路径）")
    try:
        payload = {
            "text": answer,
            "text_lang": "zh",
            "ref_audio_path": GPT_SOVITS_REF_AUDIO,
            "prompt_text": GPT_SOVITS_PROMPT_TEXT,
            "prompt_lang": GPT_SOVITS_PROMPT_LANG,
            "media_type": "wav",
            "streaming_mode": False,
        }
        async with httpx.AsyncClient(timeout=180) as client:
            response = await client.post(f"{GPT_SOVITS_URL}/tts", json=payload)
            response.raise_for_status()
            audio_bytes = response.content
        return {
            "transcript": transcript,
            "answer": answer,
            "audio_base64": base64.b64encode(audio_bytes).decode("ascii"),
            "audio_mime": "audio/wav",
        }
    except Exception as exc:
        raise HTTPException(502, f"GPT-SoVITS 合成失败：{exc}")
