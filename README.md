# 角色对话 APK（第一版）

安卓原生 Kotlin + Jetpack Compose；电脑端 LM Studio 提供聊天 API，电脑端 Python 服务提供 Edge TTS 语音合成。

## 功能
- 多角色创建、编辑、删除与切换
- 每个角色独立聊天记录和上下文
- 连接 LM Studio OpenAI 兼容 API
- 安卓系统语音识别（中文语音输入）
- 电脑端 Edge TTS 合成高质量中文语音，手机播放
- URL、模型 ID、语音音色可在应用内设置

## 一、电脑端配置 LM Studio
1. 在 LM Studio 加载 `Qwen3.5-35B-A3B-Abliterated GGUF`。
2. 打开 Developer / Local Server，启动 API 服务，默认端口通常是 `1234`。
3. 在服务器设置中允许局域网连接（不同版本设置项名称可能不同），并确保 Windows 防火墙允许该端口通过专用网络。
4. 在电脑命令行运行 `ipconfig`，查找当前局域网网卡的 IPv4 地址，例如 `192.168.1.20`。
5. 在同一 Wi-Fi/局域网下，手机和电脑需要互相可达。不要把 API 端口直接暴露到公网。

LM Studio API 地址示例：
`http://192.168.1.20:1234/v1`

## 二、启动电脑端语音服务
需要 Windows、Python 3.10 或更新版本，且首次安装需要联网下载依赖/访问 Edge TTS。
1. 双击 `tts_server/start_windows.bat`。
2. 如果 Windows 防火墙询问，允许专用网络访问。
3. 在手机浏览器打开 `http://电脑IP:8765/health`，若返回 `{"ok":true,...}` 即可访问。
4. 默认音色 `zh-CN-XiaoxiaoNeural`。可在应用设置中改成 `zh-CN-YunxiNeural` 等 Edge TTS 支持的声音。

注意：Edge TTS 使用在线语音服务，不是完全离线语音；合成时需要电脑可访问互联网。不要将此服务暴露到公网。若需要完全离线的高质量 TTS，可后续替换为本地模型服务。

## 三、用 Android Studio 打开并运行
1. 解压工程 ZIP。
2. Android Studio 选择 **Open**，打开 `RoleChatAndroid` 文件夹。
3. 使用 JDK 17；等待 Gradle Sync 完成。
4. 连接安卓手机，开启开发者选项和 USB 调试，点击 Run。
5. 首次运行在右上角设置按钮中填写：
   - LM Studio API：`http://电脑IP:1234/v1`
   - TTS 服务：`http://电脑IP:8765/tts`
   - 模型 ID：留空会尝试自动读取 `/v1/models`
   - 语音音色：`zh-CN-XiaoxiaoNeural`
6. 点击“保存并测试”。若连接失败，检查 IP、同一局域网、LM Studio 服务器是否启动和防火墙。


## 五、语音通话原型（新增）

新增 Android 语音通话入口与 PC 端语音桥接服务，代码位于 `voice_server/`。

1. 先按 `voice_server/README.md` 配置并启动 GPT-SoVITS API。
2. 根据示例文件设置 `LM_STUDIO_URL`、`GPT_SOVITS_REF_AUDIO`、参考文本等环境变量。
3. 启动 `voice_server/start_voice_bridge.bat`，手机端访问 `http://电脑IP:8770/health` 验证连接。
4. 在 APK 聊天页右上角点击电话图标，进入语音通话；首次使用需要麦克风权限，并在通话页设置中填写 `http://电脑IP:8770/voice/turn`。

本版是可先行验证的轮次式语音原型：点击录音、结束并发送、电脑识别/生成/合成、手机播放；可在播放时点击“打断并说话”。它尚不是自动 VAD 的全双工连续通话，静默后自动主动开口也尚未接入。请先在本地按 GPT-SoVITS 实际版本核对 API 路径和参数。

## 六、打包 APK
Android Studio：**Build > Build Bundle(s) / APK(s) > Build APK(s)**。
生成路径通常为 `app/build/outputs/apk/debug/app-debug.apk`。

## 常见问题
- **无法连接 LM Studio**：确认电脑 IP 没变；服务监听地址允许局域网；检查 Windows 防火墙的 TCP 1234。
- **语音失败**：确认 TTS 服务窗口仍在运行；测试 `http://电脑IP:8765/health`；检查防火墙 TCP 8765 和电脑互联网连接。
- **语音输入无结果**：Android 系统语音识别依赖设备的语音识别服务和网络/系统语言设置。
- **模型回复为空或报错**：先在 LM Studio 本机测试 `/v1/chat/completions`；模型 ID 可在 `/v1/models` 查看后手动填写。

## 安全提示
第一版为家庭/局域网测试版，使用 HTTP 明文通信，没有账号鉴权。仅在可信局域网使用，不要将 LM Studio 或 TTS 服务端口转发到公网。对敏感对话请谨慎使用在线语音合成服务。
