# RoleChat 连续语音通话桥接服务（实验版）

该服务在电脑端串联：手机 WAV 音频 -> faster-whisper 中文识别 -> LM Studio OpenAI 兼容接口 -> GPT-SoVITS API -> 手机播放合成音频。

## 1. 前提条件

- Windows、Python 3.10+。
- LM Studio 已启动 API server。默认地址是 `http://127.0.0.1:1234/v1`；如果你的端口为 1235，请按下方设置。
- GPT-SoVITS 已按你当前版本的说明启动 API，默认假定为 `http://127.0.0.1:9880`，并有可用的参考音频。
- 手机和电脑在可信的同一局域网。不要把 8770、LM Studio 或 GPT-SoVITS API 暴露到公网。

## 2. 配置并启动

1. 复制 `voice_bridge.env.example.bat` 中的环境变量到命令提示符执行（修改 LM Studio 端口、参考音频路径和参考文本）。也可以在 `start_voice_bridge.bat` 执行前，在同一终端逐项设置变量。
2. 双击 `start_voice_bridge.bat`。首次启动会下载 Python 依赖和 faster-whisper 模型；首次识别还会下载模型文件，需联网。
3. 在手机浏览器访问 `http://电脑IP:8770/health`，应返回 JSON。允许 Windows 防火墙仅在“专用网络”访问 TCP 8770。
4. Android 应用设置中将语音桥接地址设为 `http://电脑IP:8770/voice/turn`。

## 3. GPT-SoVITS 兼容性

本服务使用常见的 GPT-SoVITS API v2 `POST /tts` 参数（`text`、`text_lang`、`ref_audio_path`、`prompt_text`、`prompt_lang`、`media_type`）。不同发行版/版本可能使用不同的 API 路径或字段，请以你实际启动的 API 文档为准，并按需修改 `voice_bridge.py` 的请求部分。

## 4. 当前功能边界

这是“录一段 -> 上传 -> 识别/生成/合成 -> 播放”的轮次式通话原型，不是低延迟全双工实时流式通话。手机端录音由用户点击开始/停止控制；每一轮完整处理后播放回复。后续可再加自动静音检测、回声消除、边说边传和打断播放。服务会将最近少量文字历史作为上下文，但不会自动写回现有 APK 的角色聊天记录。

## 5. 性能

faster-whisper 默认 small + CPU int8，首次加载较慢；可把 `WHISPER_MODEL` 改为 `base` 提升速度（识别质量会下降）。如果已配置 CUDA 环境，可尝试 `WHISPER_DEVICE=cuda` 和相应 compute type。LM Studio 的生成速度仍取决于本机模型和硬件。
