package com.example.rolechat

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoiceCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { VoiceCallScreen(onHangup = { finish() }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCallScreen(onHangup: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rolechat", 0) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    var bridgeUrl by remember { mutableStateOf(prefs.getString("voice_bridge_url", "http://192.168.1.100:8770/voice/turn") ?: "") }
    var lmUrl by remember { mutableStateOf(prefs.getString("lm_url", "http://192.168.1.100:1234/v1") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var roleName by remember { mutableStateOf("知心朋友") }
    var rolePrompt by remember { mutableStateOf("温柔、聪明、幽默，像熟悉的朋友一样自然聊天。回复简短，适合直接朗读。") }
    var recording by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("点击开始通话") }
    var transcript by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var silenceSeconds by remember { mutableIntStateOf(20) }
    var showSettings by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<AudioRecord?>(null) }
    var recordJob by remember { mutableStateOf<Job?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var recordBuffer by remember { mutableStateOf<ByteArrayOutputStream?>(null) }
    var recordError by remember { mutableStateOf("") }
    val stopRequested = remember { AtomicBoolean(false) }
    val sampleRate = 16000

    fun startRecording() {
        if (recording || processing) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "需要麦克风权限"
            return
        }
        try {
            player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
            player = null
            val minSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, sampleRate)
            )
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                status = "麦克风初始化失败"
                return
            }
            val output = ByteArrayOutputStream()
            recorder = audioRecord
            recordBuffer = output
            stopRequested.set(false)
            audioRecord.startRecording()
            recording = true
            status = "正在聆听…再次点击结束本轮"
            recordJob = scope.launch(Dispatchers.IO) {
                val chunk = ByteArray(4096)
                try {
                    while (!stopRequested.get()) {
                        val count = audioRecord.read(chunk, 0, chunk.size)
                        if (count > 0) synchronized(output) { output.write(chunk, 0, count) }
                    }
                } catch (e: Exception) {
                    recordError = e.message ?: "录音失败"
                } finally {
                    try { audioRecord.stop() } catch (_: Exception) {}
                    audioRecord.release()
                }
            }
        } catch (e: Exception) {
            status = "无法开始录音：${e.message}"
            recording = false
        }
    }

    fun stopAndSend() {
        if (!recording || processing) return
        recording = false
        processing = true
        status = "正在识别并生成回复…"
        stopRequested.set(true)
        try { recorder?.stop() } catch (_: Exception) {}
        val output = recordBuffer
        recordJob = scope.launch {
            try {
                delay(150)
                val pcm = output?.toByteArray() ?: ByteArray(0)
                if (pcm.size < sampleRate / 2) {
                    status = "录音太短，请再说一次"
                    return@launch
                }
                val wav = makeWav(pcm, sampleRate, 1, 16)
                val response = withContext(Dispatchers.IO) {
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("audio", "turn.wav", wav.toRequestBody("audio/wav".toMediaType()))
                        .addFormDataPart("role_name", roleName)
                        .addFormDataPart("role_prompt", rolePrompt)
                        .addFormDataPart("model", model)
                        .addFormDataPart("history_json", JSONArray().apply {
                            history.takeLast(12).forEach { (r, c) -> put(JSONObject().put("role", r).put("content", c)) }
                        }.toString())
                        .build()
                    val request = Request.Builder().url(bridgeUrl.trim()).post(body).build()
                    client.newCall(request).execute().use { res ->
                        val text = res.body?.string().orEmpty()
                        if (!res.isSuccessful) error("服务端 ${res.code}: $text")
                        JSONObject(text)
                    }
                }
                transcript = response.optString("transcript")
                answer = response.optString("answer")
                if (transcript.isNotBlank()) history = history + ("user" to transcript)
                if (answer.isNotBlank()) history = history + ("assistant" to answer)
                status = if (answer.isBlank()) "没有识别到语音，请重试" else "角色正在回复…"
                val audioB64 = response.optString("audio_base64")
                if (audioB64.isNotBlank()) {
                    val audio = android.util.Base64.decode(audioB64, android.util.Base64.DEFAULT)
                    val file = File(context.cacheDir, "voice_call_reply.wav")
                    FileOutputStream(file).use { it.write(audio) }
                    withContext(Dispatchers.Main) {
                        player?.release()
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnPreparedListener { mp -> mp.start(); status = "正在播放 · 点击“打断并说话”可插话" }
                            setOnCompletionListener { mp -> mp.release(); player = null; status = "回复完成 · 点击麦克风继续" }
                            setOnErrorListener { mp, _, _ -> mp.release(); player = null; status = "播放失败"; true }
                            prepareAsync()
                        }
                    }
                }
            } catch (e: Exception) {
                status = "通话失败：${e.message ?: "请检查电脑端服务"}"
            } finally {
                processing = false
                recorder = null
            }
        }
    }

    fun interruptAndTalk() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        recording = false
        processing = false
        startRecording()
    }

    DisposableEffect(Unit) {
        onDispose {
            stopRequested.set(true)
            try { recorder?.stop() } catch (_: Exception) {}
            recorder?.release()
            player?.release()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(topBar = {
            TopAppBar(
                title = { Column { Text("语音通话"); Text(status, style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                    IconButton(onClick = onHangup) { Icon(Icons.Default.CallEnd, contentDescription = "挂断") }
                }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(roleName, style = MaterialTheme.typography.headlineMedium)
                Text("电脑端识别语音 → LM Studio 生成回复 → GPT-SoVITS 合成声音", style = MaterialTheme.typography.bodyMedium)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("你说的", style = MaterialTheme.typography.labelLarge)
                        Text(transcript.ifBlank { "等待语音…" })
                        Divider()
                        Text("角色回复", style = MaterialTheme.typography.labelLarge)
                        Text(answer.ifBlank { "等待角色回复…" })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !processing,
                        onClick = { if (recording) stopAndSend() else startRecording() }
                    ) {
                        Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (recording) "结束并发送" else "开始说话")
                    }
                    OutlinedButton(enabled = player != null, onClick = { interruptAndTalk() }) {
                        Icon(Icons.Default.Mic, null)
                        Spacer(Modifier.width(4.dp))
                        Text("打断并说话")
                    }
                }
                OutlinedButton(onClick = onHangup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.CallEnd, null)
                    Spacer(Modifier.width(8.dp))
                    Text("挂断通话")
                }
                Text("提示：此版采用按轮次录音，处理完后播放回复；模型和语音合成耗时取决于电脑性能。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("语音通话设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(bridgeUrl, { bridgeUrl = it }, label = { Text("电脑语音桥接 API") }, singleLine = true)
                        OutlinedTextField(lmUrl, { lmUrl = it }, label = { Text("LM Studio API（记录用）") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, label = { Text("模型 ID（可留空）") }, singleLine = true)
                        OutlinedTextField(roleName, { roleName = it }, label = { Text("角色名称") }, singleLine = true)
                        OutlinedTextField(rolePrompt, { rolePrompt = it }, label = { Text("角色性格提示词") })
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("voice_bridge_url", bridgeUrl).putString("lm_url", lmUrl)
                            .putString("model", model).apply()
                        showSettings = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
            )
        }
    }
}

private fun makeWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val totalDataLen = pcm.size + 36
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val out = ByteArrayOutputStream()
    fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    out.write(ascii("RIFF"))
    out.write(le32(totalDataLen))
    out.write(ascii("WAVE"))
    out.write(ascii("fmt "))
    out.write(le32(16))
    out.write(le16(1))
    out.write(le16(channels))
    out.write(le32(sampleRate))
    out.write(le32(byteRate))
    out.write(le16(channels * bitsPerSample / 8))
    out.write(le16(bitsPerSample))
    out.write(ascii("data"))
    out.write(le32(pcm.size))
    out.write(pcm)
    return out.toByteArray()
}
private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
private fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
