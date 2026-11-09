# Game Voice Studio

Windows 桌面端批量角色语音制作工具。第一版使用 PySide6 GUI，通过 GPT-SoVITS 常见 API v2 的 /tts 接口逐行生成 WAV。

## 设计

- GPT-SoVITS 作为独立常驻推理服务，模型加载一次，批量任务复用。
- GUI 可启动你配置的服务命令，也可连接已手动启动的服务。
- 每个声音角色独立保存参考音频路径、参考文本和语言。
- TXT 每个非空行生成一段 WAV，按原始行号命名。
- 支持跳过已有文件、暂停、继续、停止、失败重试。
- 每个任务输出 CSV；tokens 字段暂留空，除非 API 明确提供真实 token usage，不用字符数冒充 token。

## 安装

建议 Windows 10/11，Python 3.10–3.12（同时要符合你本机 GPT-SoVITS 版本的兼容要求）。GUI 环境与 GPT-SoVITS 推理环境分开安装，避免依赖互相污染。

    cd game_voice_studio
    py -3.11 -m venv .venv
    .venv\Scripts\activate
    python -m pip install --upgrade pip
    pip install -r requirements.txt
    python game_voice_studio.py

也可以双击 start_windows.bat。

## 首次配置

1. 在「设置」填写 GPT-SoVITS API 根地址，默认 http://127.0.0.1:9880。
2. 如需由 GUI 启动推理服务，填写启动命令与工作目录。常见源码安装形式可能类似：

    python api_v2.py -a 127.0.0.1 -p 9880

   这只是示例。不同版本、整合包的参数可能不同，请以本机启动方式为准。也可以留空，手动启动后由 GUI 连接。
3. 在「声音角色」新增角色，选择参考音频并填写对应参考文本。
4. 在「批量生成」选择角色、导入 TXT、指定输出目录并开始。

## CSV 字段

line_number, text, char_count, generation_time_seconds, audio_duration_seconds, tokens, retry_count, status, output_file, error。

tokens 暂为空值：除非所用 API 明确提供真实 token 统计，否则不估算。generation_time_seconds 是本工具测得的请求耗时，不等于纯模型推理时间。

## 当前限制与后续验证

- 按 GPT-SoVITS 常见 API v2 JSON 请求格式编写，必须用本机实际版本做首次联调。
- 服务检测只探测根路径或 /docs，只说明 HTTP 可连接，不代表模型已正确加载或 /tts 一定成功；首次建议先生成一条短句。
- GUI 启动命令是通用命令行方式；复杂整合包可填写自己的 .bat 脚本。
- 当前只实现 WAV 输出。暂停与停止会在当前 HTTP 请求结束后生效。
- 角色配置保存本机参考音频绝对路径，换电脑后需要重新指定。
- 约 10 秒参考音频能否达到预期音色相似度必须实测。录音应尽量安静、无混响，参考文本需逐字准确。
- 第一版优先普通中文台词；笑声、哭泣、喘息等非语言发声暂不作为验收条件。

## 授权与隐私

仅使用获得明确授权的录音。若用于游戏发行或商业用途，授权范围建议明确包含音色克隆、生成合成语音、游戏内使用与发行，以及录音保存和删除安排。参考音频不会由本工具自动上传到其他服务。
