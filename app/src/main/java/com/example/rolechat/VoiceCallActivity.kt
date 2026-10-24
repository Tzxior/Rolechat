package com.example.rolechat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class VoiceCallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true
        setContent { VoiceCallScreen(
            initialRoleId = intent.getStringExtra("role_id") ?: "friend",
            initialRoleName = intent.getStringExtra("role_name") ?: "知心朋友",
            initialRolePrompt = intent.getStringExtra("role_prompt") ?: "温柔、聪明、幽默，像熟悉的朋友一样自然聊天。回复简短，适合直接朗读。",
            onHangup = { finish() }
        ) }
    }

    override fun onDestroy() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.isSpeakerphoneOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallScreen(
    initialRoleId: String,
    initialRoleName: String,
    initialRolePrompt: String,
    onHangup: () -> Unit,
    embedded: Boolean = false,
    onHistoryChanged: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rolechat", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(240, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    var bridgeUrl by remember { mutableStateOf(prefs.getString("voice_bridge_url", "http://192.168.1.100:8770") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var roles by remember { mutableStateOf(loadRoles(prefs)) }
    var roleId by remember {
        mutableStateOf(
            initialRoleId.takeIf { id -> roles.any { it.id == id } }
                ?: prefs.getString("selected_role", roles.firstOrNull()?.id ?: "") ?: ""
        )
    }
    var histories by remember { mutableStateOf(loadHistories(prefs)) }
    val selectedRole = roles.firstOrNull { it.id == roleId }
    var roleName by remember { mutableStateOf(selectedRole?.name ?: initialRoleName) }
    var rolePrompt by remember { mutableStateOf(selectedRole?.let { rolePromptFor(it) } ?: initialRolePrompt) }
    var active by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("点击开始连续通话") }
    var transcript by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var proactiveSeconds by remember { mutableIntStateOf(prefs.getInt("voice_proactive_seconds", 45)) }
    var currentPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var finishPlayback by remember { mutableStateOf<(() -> Unit)?>(null) }
    var loopJob by remember { mutableStateOf<Job?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        status = if (granted) "麦克风权限已授予，再点一次开始通话" else "未授予麦克风权限，无法通话"
    }
    val callActive = remember { AtomicBoolean(false) }
    val stopPlayback = remember { AtomicBoolean(false) }
    val sampleRate = 16000

    fun persistMessage(role: String, text: String) {
        if (text.isBlank()) return
        synchronized(prefs) {
            val all = try { JSONObject(prefs.getString("histories", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val arr = try { all.optJSONArray(roleId) ?: JSONArray() } catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().put("role", role).put("content", text).put("id", UUID.randomUUID().toString()))
            all.put(roleId, arr)
            prefs.edit().putString("histories", all.toString()).apply()
            histories = loadHistories(prefs)
            scope.launch(Dispatchers.Main) { onHistoryChanged?.invoke() }
        }
    }

    fun historyJson(): String {
        val all = try { JSONObject(prefs.getString("histories", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        val source = all.optJSONArray(roleId) ?: JSONArray()
        val result = JSONArray()
        for (i in maxOf(0, source.length() - 20) until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val role = item.optString("role")
            if (role == "user" || role == "assistant") {
                result.put(JSONObject().put("role", role).put("content", item.optString("content").take(4000)))
            }
        }
        return result.toString()
    }

    suspend fun requestTurn(wav: ByteArray): JSONObject = withContext(Dispatchers.IO) {
        val root = bridgeUrl.trim().trimEnd('/')
        val url = if (root.endsWith("/voice/turn")) root else "$root/voice/turn"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("audio", "utterance.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("role_name", roleName)
            .addFormDataPart("role_prompt", rolePrompt)
            .addFormDataPart("model", model)
            .addFormDataPart("history_json", historyJson())
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("语音服务 ${response.code}: $text")
            JSONObject(text)
        }
    }

    suspend fun requestProactive(): JSONObject = withContext(Dispatchers.IO) {
        val root = bridgeUrl.trim().trimEnd('/')
        val url = if (root.endsWith("/voice/turn")) root.removeSuffix("/voice/turn") + "/voice/proactive"
            else "$root/voice/proactive"
        val payload = JSONObject()
            .put("role_name", roleName)
            .put("role_prompt", rolePrompt)
            .put("model", model)
            .put("history_json", historyJson())
        val request = Request.Builder().url(url)
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("主动聊天请求 ${response.code}: $text")
            JSONObject(text)
        }
    }

    suspend fun playReply(base64Audio: String) {
        if (base64Audio.isBlank() || !callActive.get()) return
        val bytes = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
        val file = File(context.cacheDir, "voice_call_reply.wav")
        FileOutputStream(file).use { it.write(bytes) }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                if (!callActive.get() || stopPlayback.get()) {
                    if (continuation.isActive) continuation.resume(Unit)
                    return@suspendCancellableCoroutine
                }
                val mp = MediaPlayer()
                currentPlayer = mp
                var finished = false
                fun finishPlayer() {
                    if (finished) return
                    finished = true
                    finishPlayback = null
                    try { mp.release() } catch (_: Exception) {}
                    if (currentPlayer === mp) currentPlayer = null
                    if (callActive.get()) status = "回复结束 · 正在聆听"
                    if (continuation.isActive) continuation.resume(Unit)
                }
                finishPlayback = { finishPlayer() }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener {
                        if (callActive.get() && !stopPlayback.get()) {
                            status = "角色正在说话 · 可随时打断"
                            it.start()
                        } else finishPlayer()
                    }
                    mp.setOnCompletionListener { finishPlayer() }
                    mp.setOnErrorListener { _, _, _ -> finishPlayer(); true }
                    continuation.invokeOnCancellation { try { mp.release() } catch (_: Exception) {} }
                    mp.prepareAsync()
                } catch (_: Exception) { finishPlayer() }
            }
        }
    }

    fun stopCurrentPlayback() {
        stopPlayback.set(true)
        finishPlayback?.invoke()
        currentPlayer?.let { mp ->
            try { mp.stop() } catch (_: Exception) {}
            try { mp.release() } catch (_: Exception) {}
        }
        currentPlayer = null
        stopPlayback.set(false)
        status = "已打断播放，恢复聆听"
    }

    fun stopCall() {
        callActive.set(false)
        active = false
        stopCurrentPlayback()
        loopJob?.cancel()
        loopJob = null
        processing = false
        status = "通话已结束"
    }

    fun startCall() {
        if (callActive.get()) return
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "需要麦克风权限"
            return
        }
        if (bridgeUrl.isBlank()) {
            status = "请先设置电脑端语音桥接地址"
            showSettings = true
            return
        }
        callActive.set(true)
        stopPlayback.set(false)
        active = true
        status = "正在聆听 · 说完后自动识别并回复"
        loopJob = scope.launch(Dispatchers.IO) {
            var lastInteraction = SystemClock.elapsedRealtime()
            var proactiveDoneThisIdle = false
            val frameBytes = 3200 // 100 ms at 16 kHz, mono, PCM16
            val preRoll = ArrayDeque<ByteArray>()
            var utterance = ByteArrayOutputStream()
            var speechStarted = false
            var voicedFrames = 0
            var silenceFrames = 0
            var utteranceFrames = 0
            var audioRecord: AudioRecord? = null
            try {
                while (callActive.get()) {
                    if (audioRecord == null) {
                        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, frameBytes * 4))
                        if (rec.state != AudioRecord.STATE_INITIALIZED) {
                            rec.release()
                            throw IllegalStateException("麦克风初始化失败")
                        }
                        rec.startRecording()
                        audioRecord = rec
                    }
                    val frame = ByteArray(frameBytes)
                    val count = audioRecord!!.read(frame, 0, frame.size)
                    if (count <= 0) continue
                    val chunk = if (count == frame.size) frame else frame.copyOf(count)
                    var sum = 0L
                    var samples = 0
                    var i = 0
                    while (i + 1 < count) {
                        val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xff)).toShort().toInt()
                        sum += sample.toLong() * sample.toLong()
                        samples++
                        i += 2
                    }
                    val rms = if (samples > 0) kotlin.math.sqrt(sum.toDouble() / samples) else 0.0
                    val voiced = rms > 650.0
                    if (!speechStarted) {
                        if (preRoll.size >= 3) preRoll.removeFirst()
                        preRoll.addLast(chunk)
                        if (voiced) voicedFrames++ else voicedFrames = 0
                        if (voicedFrames >= 2) {
                            speechStarted = true
                            utterance = ByteArrayOutputStream()
                            preRoll.forEach { utterance.write(it) }
                            preRoll.clear()
                            utterance.write(chunk)
                            utteranceFrames = 4
                            silenceFrames = 0
                            withContext(Dispatchers.Main) { status = "检测到你在说话…" }
                        } else {
                            val idleSeconds = (SystemClock.elapsedRealtime() - lastInteraction) / 1000
                            if (!proactiveDoneThisIdle && idleSeconds >= proactiveSeconds) {
                                audioRecord?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                                audioRecord = null
                                withContext(Dispatchers.Main) { processing = true; status = "角色主动找你聊天…" }
                                try {
                                    val result = requestProactive()
                                    val text = result.optString("answer").trim()
                                    if (text.isNotBlank()) {
                                        answer = text
                                        persistMessage("assistant", text)
                                    }
                                    withContext(Dispatchers.Main) { processing = false }
                                    if (text.isNotBlank()) playReply(result.optString("audio_base64"))
                                    proactiveDoneThisIdle = true
                                    lastInteraction = SystemClock.elapsedRealtime()
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { status = "主动聊天失败：${e.message}" ; processing = false }
                                    proactiveDoneThisIdle = true
                                }
                            }
                        }
                    } else {
                        utterance.write(chunk)
                        utteranceFrames++
                        if (voiced) silenceFrames = 0 else silenceFrames++
                        if (silenceFrames >= 10 || utteranceFrames >= 180) {
                            val pcm = utterance.toByteArray()
                            audioRecord?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                            audioRecord = null
                            speechStarted = false
                            voicedFrames = 0
                            silenceFrames = 0
                            utteranceFrames = 0
                            preRoll.clear()
                            if (pcm.size >= sampleRate * 2 / 3) {
                                withContext(Dispatchers.Main) { processing = true; status = "正在识别并生成回复…" }
                                try {
                                    val result = requestTurn(makeWav(pcm, sampleRate, 1, 16))
                                    val heard = result.optString("transcript").trim()
                                    val reply = result.optString("answer").trim()
                                    transcript = heard
                                    answer = reply
                                    if (heard.isNotBlank()) persistMessage("user", heard)
                                    if (reply.isNotBlank()) persistMessage("assistant", reply)
                                    if (heard.isNotBlank()) lastInteraction = SystemClock.elapsedRealtime()
                                    proactiveDoneThisIdle = false
                                    withContext(Dispatchers.Main) { processing = false }
                                    if (reply.isNotBlank()) playReply(result.optString("audio_base64"))
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { processing = false; status = "本轮失败：${e.message}" }
                                    delay(700)
                                }
                                lastInteraction = SystemClock.elapsedRealtime()
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status = "通话异常：${e.message}"; active = false; processing = false }
                callActive.set(false)
            } finally {
                audioRecord?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
                withContext(Dispatchers.Main) {
                    active = false
                    processing = false
                    currentPlayer?.let { try { it.release() } catch (_: Exception) {} }
                    currentPlayer = null
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            callActive.set(false)
            stopPlayback.set(true)
            loopJob?.cancel()
            currentPlayer?.let { try { it.release() } catch (_: Exception) {} }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        if (embedded) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = !processing,
                    onClick = {
                        if (active) stopCall()
                        else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else startCall()
                    }
                ) {
                    Icon(if (active) Icons.Default.CallEnd else Icons.Default.Mic, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (active) "结束通话" else "开始通话")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = currentPlayer != null,
                    onClick = { stopCurrentPlayback() }
                ) { Text("打断对方") }
            }
        } else {
        Scaffold(topBar = {
            TopAppBar(
                title = { Column { Text("语音通话 · ${roleName}"); Text(status, style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    TextButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, null); Text("设置") }
                    IconButton(onClick = { if (active) stopCall() else onHangup() }) { Icon(Icons.Default.CallEnd, contentDescription = "挂断") }
                }
            )
        }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                Icon(Icons.Default.Call, null, Modifier.size(76.dp))
                Text(roleName, style = MaterialTheme.typography.headlineMedium)
                Text("连续监听 · 自动断句 · 自动回复 · 静默时主动聊天", style = MaterialTheme.typography.bodyMedium)
                if (roles.size > 1) {
                    Text("切换角色", style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        roles.forEach { role ->
                            FilterChip(
                                selected = role.id == roleId,
                                onClick = {
                                    if (active) stopCall()
                                    roleId = role.id
                                    roleName = role.name
                                    rolePrompt = rolePromptFor(role)
                                    prefs.edit().putString("selected_role", role.id).apply()
                                    histories = loadHistories(prefs)
                                    transcript = ""
                                    answer = ""
                                    status = "已切换到 " + role.name
                                },
                                label = { Text(role.name) }
                            )
                        }
                    }
                }
                Text("完整聊天记录", style = MaterialTheme.typography.titleMedium)
                if (histories[roleId].orEmpty().isEmpty()) {
                    Text("这个角色还没有聊天记录。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    histories[roleId].orEmpty().forEach { message ->
                        val isUser = message.role == "user"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                            Card {
                                Column(Modifier.widthIn(max = 320.dp).padding(12.dp)) {
                                    Text(if (isUser) "你" else roleName, style = MaterialTheme.typography.labelSmall)
                                    Text(message.content)
                                }
                            }
                        }
                    }
                }
                Text("静默 ${proactiveSeconds} 秒后，角色可主动发起一段对话。通话时会暂停监听来避免把角色播放声重新录进去。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !processing,
                        onClick = {
                            if (active) stopCall()
                            else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            else startCall()
                        }
                    ) {
                        Icon(if (active) Icons.Default.CallEnd else Icons.Default.Mic, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "结束通话" else "开始连续通话")
                    }
                    OutlinedButton(enabled = currentPlayer != null, onClick = { stopCurrentPlayback() }) {
                        Text("打断播放")
                    }
                }
                OutlinedButton(onClick = onHangup, modifier = Modifier.fillMaxWidth()) { Text("切换到文字聊天") }
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("连续语音设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(bridgeUrl, { bridgeUrl = it }, label = { Text("电脑桥接服务地址") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, label = { Text("LM Studio 模型 ID（可留空）") }, singleLine = true)
                        Text("当前角色：$roleName")
                        Text("角色设定与文字聊天共用；请在文字聊天的角色编辑中修改。", style = MaterialTheme.typography.bodySmall)
                        Text("静默主动开口时间")
                        Slider(value = proactiveSeconds.toFloat(), onValueChange = { proactiveSeconds = it.toInt() }, valueRange = 20f..180f, steps = 7)
                        Text("${proactiveSeconds} 秒")
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        prefs.edit().putString("voice_bridge_url", bridgeUrl.trim().trimEnd('/'))
                            .putString("model", model).putInt("voice_proactive_seconds", proactiveSeconds)
                            .putString("selected_role", roleId).apply()
                        showSettings = false
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
            )
        }
        }
    }
}

private fun makeWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
    val out = ByteArrayOutputStream()
    fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    out.write(ascii("RIFF")); out.write(le32(pcm.size + 36)); out.write(ascii("WAVE"))
    out.write(ascii("fmt ")); out.write(le32(16)); out.write(le16(1)); out.write(le16(channels))
    out.write(le32(sampleRate)); out.write(le32(sampleRate * channels * bitsPerSample / 8))
    out.write(le16(channels * bitsPerSample / 8)); out.write(le16(bitsPerSample))
    out.write(ascii("data")); out.write(le32(pcm.size)); out.write(pcm)
    return out.toByteArray()
}
private fun le16(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte())
private fun le32(v: Int) = byteArrayOf((v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(), ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte())
