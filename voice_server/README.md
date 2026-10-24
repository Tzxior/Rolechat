# RoleChat 连续语音通话桥接服务

本服务运行在 Windows 电脑上，供 Android APK 在同一局域网内访问。请求链路为：

1. 手机持续采集语音，在本地检测到一句话结束后上传该段 WAV。
2. faster-whisper 识别中文。
3. LM Studio 根据当前角色设定和角色历史生成简短口语回复。
4. GPT-SoVITS 合成角色语音并返回手机。
5. 手机播放完成后恢复麦克风监听；用户长时间没说话时，手机调用主动聊天接口，让角色基于已有历史主动开口。

## 前提条件

- Windows、Python 3.10+。
- LM Studio 已启动 API server。示例配置使用 http://127.0.0.1:1235/v1；如果你的端口不同，请调整 LM_STUDIO_URL。
- GPT-SoVITS 已按当前安装版本的说明启动 API，默认假定地址为 http://127.0.0.1:9880，并有可用参考音频。
- 手机和电脑在可信的同一局域网。不要将桥接服务、LM Studio 或 GPT-SoVITS API 暴露到公网。

## 配置与启动

1. 修改 voice_bridge.env.example.bat 中的 LM Studio 地址、参考音频路径和参考文本。
2. 打开命令提示符，进入 voice_server 文件夹，执行该环境变量文件，然后在同一个窗口启动服务。例如：

   ```bat
   call voice_bridge.env.example.bat
   call start_voice_bridge.bat
   ```

   也可以手动逐项设置环境变量。示例参考音频路径和参考文本必须替换成真实值。
3. 首次启动会创建 .venv 并安装依赖；首次运行 faster-whisper 时可能下载识别模型，需要联网。
4. 在手机浏览器访问 http://电脑IP:8770/health。返回 JSON 表示服务可以访问。
5. Windows 防火墙仅允许可信的“专用网络”访问 TCP 8770。

## Android 通话设置

- 推荐填桥接根地址：http://电脑IP:8770。
- 也兼容填完整的单轮地址：http://电脑IP:8770/voice/turn。
- 模型 ID 可以留空，服务会从 LM Studio 的 /models 自动选择第一个已加载模型；若不合适，请填写准确模型 ID。
- 当前角色的名称、角色提示词和最近 20 条聊天记录会随请求发送。通话中识别的用户文本和角色回复会写回该角色在 APK 中的聊天历史。

## 接口

- GET /health：服务健康状态和配置摘要。
- POST /voice/turn：multipart 表单，字段包括 audio（WAV）、role_name、role_prompt、model、history_json。
- POST /voice/proactive：JSON 表单，字段包括 role_name、role_prompt、model、history_json。根据角色历史生成一条主动开场，并返回语音。

两个语音接口均返回 transcript、answer、audio_base64 和 audio_mime。

## GPT-SoVITS 兼容性

服务按常见 GPT-SoVITS API v2 的 POST /tts 编写，使用 text、text_lang、ref_audio_path、prompt_text、prompt_lang、media_type 等字段。不同发行版可能有不同路径或参数，请以本机 API 文档为准。

## 延迟与限制

这是自动轮次式连续对话，不是低延迟全双工音频流。手机会自动监听和断句，但每轮识别、模型生成、语音合成期间会暂停录音，播放完成后恢复监听；当前没有边说边上传、流式语音输出或声学回声消除。模型与语音合成耗时取决于电脑硬件和服务配置。

默认 faster-whisper 使用 small + CPU int8。可将 WHISPER_MODEL 改为 base 以降低延迟（识别质量可能下降）；只有在 CUDA 环境正确配置时才尝试 WHISPER_DEVICE=cuda。
