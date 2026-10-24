import asyncio
import io
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field
import edge_tts

app = FastAPI(title="RoleChat TTS", version="0.1.0")

class TTSRequest(BaseModel):
    text: str = Field(min_length=1, max_length=5000)
    voice: str = "zh-CN-XiaoxiaoNeural"
    rate: str = "+0%"
    volume: str = "+0%"

@app.get("/health")
def health():
    return {"ok": True, "service": "rolechat-tts"}

@app.post("/tts")
async def tts(req: TTSRequest):
    try:
        communicate = edge_tts.Communicate(
            text=req.text, voice=req.voice, rate=req.rate, volume=req.volume
        )
        buffer = io.BytesIO()
        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                buffer.write(chunk["data"])
        data = buffer.getvalue()
        if not data:
            raise RuntimeError("TTS returned empty audio")
        return StreamingResponse(io.BytesIO(data), media_type="audio/mpeg",
                                 headers={"Cache-Control": "no-store"})
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))

@app.get("/voices")
async def voices():
    try:
        return await edge_tts.list_voices()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
