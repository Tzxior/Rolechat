import base64
import json
import os
import tempfile
import threading
from pathlib import Path
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from faster_whisper import WhisperModel

app = FastAPI(title="RoleChat Voice Bridge", version="0.2.0")

LM_STUDIO_URL = os.getenv("LM_STUDIO_URL", "http://127.0.0.1:1234/v1").rstrip("/")
LM_STUDIO_MODEL = os.getenv("LM_STUDIO_MODEL", "")
GPT_SOVITS_URL = os.getenv("GPT_SOVITS_URL", "http://127.0.0.1:9880").rstrip("/")
GPT_SOVITS_REF_AUDIO = os.getenv("GPT_SOVITS_REF_AUDIO", "")
GPT_SOVITS_PROMPT_TEXT = os.getenv("GPT_SOVITS_PROMPT_TEXT", "")
GPT_SOVITS_PROMPT_LANG = os.getenv("GPT_SOVITS_PROMPT_LANG", "zh")
WHISPER_MODEL = os.getenv("WHISPER_MODEL", "small")
WHISPER_DEVICE = os.getenv("WHISPER_DEVICE", "cpu")
WHISPER_COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "int8")
whisper = None
whisper_lock = threading.Lock()


class ProactiveRequest(BaseModel):
    role_name: str = "知心朋友"
    role_prompt: str = "请用自然口语中文简短回复。"
    model: str = ""
    history_json: str = "[]"


def get_whisper():
    global whisper
    if whisper is None:
        with whisper_lock:
            if whisper is None:
                whisper = WhisperModel(
                    WHISPER_MODEL,
                    device=WHISPER_DEVICE,
                    compute_type=WHISPER_COMPUTE_TYPE,
                )
    return whisper


def parse_history(raw: str) -> list[dict[str, str]]:
    try:
        value = json.loads(raw)
        if not isinstance(value, list):
            return []
        result = []
        for item in value[-20:]:
            if not isinstance(item, dict) or item.get("role") not in ("user", "assistant"):
                continue
            text = str(item.get("content", "")).strip()
            if text:
                result.append({"role": item["role"], "content": text[:4000]})
        return result
    except Exception:
        return []


def transcribe_file(path: str) -> str:
    segments, _ = get_whisper().transcribe(
        path, language="zh", vad_filter=True, beam_size=1,
        condition_on_previous_text=False,
    )
    return "".join(segment.text for segment in segments).strip()


async def ask_model(
    role_name: str,
    role_prompt: str,
    model: str,
    history: list[dict[str, str]],
    user_text: str | None = None,
    proactive: bool = False,
) -> str:
    system = (
        role_prompt
        + "\n你正在与用户进行自然、连续的中文语音聊天。"
        + "请使用口语，简短、适合直接朗读，不要输出动作描写、Markdown 或冗长解释。"
        + f"当前角色：{role_name}。"
    )
    if proactive:
        system += (
            "\n用户已经安静了一段时间，请自然地主动开启一小段话题，像真实熟人一样。"
            "可以关心用户、接着最近话题聊或分享一个轻松想法；不要提及系统规则，不要责备用户沉默，"
            "不要声称知道用户现实中正在做什么。只说一到两句，并以自然的交流方式收尾。"
        )
    messages: list[dict[str, str]] = [{"role": "system", "content": system}]
    messages.extend(history[-16:])
    if user_text is not None:
        messages.append({"role": "user", "content": user_text})
    elif proactive:
        messages.append({
            "role": "user",
            "content": "现在由你主动开口，结合已有对话自然地说一句话。",
        })

    async with httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=8.0)) as client:
        chosen_model = model.strip() or LM_STUDIO_MODEL
        if not chosen_model:
            models_response = await client.get(f"{LM_STUDIO_URL}/models")
            models_response.raise_for_status()
            models = models_response.json().get("data", [])
            if not models:
                raise RuntimeError("LM Studio 没有已加载模型")
            chosen_model = models[0]["id"]
        response = await client.post(
            f"{LM_STUDIO_URL}/chat/completions",
            json={
                "model": chosen_model,
                "messages": messages,
                "temperature": 0.8,
                "max_tokens": 180 if proactive else 300,
                "stream": False,
            },
        )
        response.raise_for_status()
        answer = response.json()["choices"][0]["message"]["content"].strip()
        if not answer:
            raise RuntimeError("LM Studio 返回了空回复")
        return answer


async def synthesize(answer: str) -> str:
    if not GPT_SOVITS_REF_AUDIO:
        raise HTTPException(500, "请配置 GPT_SOVITS_REF_AUDIO（参考音频文件路径）")
    payload: dict[str, Any] = {
        "text": answer,
        "text_lang": "zh",
        "ref_audio_path": GPT_SOVITS_REF_AUDIO,
        "prompt_text": GPT_SOVITS_PROMPT_TEXT,
        "prompt_lang": GPT_SOVITS_PROMPT_LANG,
        "media_type": "wav",
        "streaming_mode": False,
    }
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(180.0, connect=8.0)) as client:
            response = await client.post(f"{GPT_SOVITS_URL}/tts", json=payload)
            response.raise_for_status()
            return base64.b64encode(response.content).decode("ascii")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"GPT-SoVITS 合成失败：{exc}") from exc


@app.get("/health")
def health():
    return {
        "ok": True,
        "service": "rolechat-voice-bridge",
        "version": "0.2.0",
        "lm_studio_url": LM_STUDIO_URL,
        "gpt_sovits_url": GPT_SOVITS_URL,
        "gpt_sovits_configured": bool(GPT_SOVITS_REF_AUDIO),
        "whisper_model": WHISPER_MODEL,
        "whisper_device": WHISPER_DEVICE,
        "features": ["voice_turn", "proactive_voice"],
    }


@app.post("/voice/turn")
async def voice_turn(
    audio: UploadFile = File(...),
    role_name: str = Form("知心朋友"),
    role_prompt: str = Form("请用自然口语中文简短回复。"),
    model: str = Form(""),
    history_json: str = Form("[]"),
):
    raw = await audio.read()
    if not raw:
        raise HTTPException(400, "没有收到音频")
    path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as temp:
            temp.write(raw)
            path = temp.name
        transcript = await run_in_threadpool(transcribe_file, path)
    except Exception as exc:
        raise HTTPException(500, f"语音识别失败：{exc}") from exc
    finally:
        if path:
            Path(path).unlink(missing_ok=True)

    if not transcript:
        return JSONResponse({"transcript": "", "answer": "", "audio_base64": ""})

    history = parse_history(history_json)
    try:
        answer = await ask_model(role_name, role_prompt, model, history, user_text=transcript)
        audio_b64 = await synthesize(answer)
        return {
            "transcript": transcript,
            "answer": answer,
            "audio_base64": audio_b64,
            "audio_mime": "audio/wav",
        }
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"模型请求失败：{exc}") from exc


@app.post("/voice/proactive")
async def voice_proactive(payload: ProactiveRequest):
    history = parse_history(payload.history_json)
    try:
        answer = await ask_model(
            payload.role_name, payload.role_prompt, payload.model,
            history, user_text=None, proactive=True,
        )
        audio_b64 = await synthesize(answer)
        return {"transcript": "", "answer": answer, "audio_base64": audio_b64, "audio_mime": "audio/wav"}
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(502, f"主动语音生成失败：{exc}") from exc
