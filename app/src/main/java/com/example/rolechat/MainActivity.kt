package com.example.rolechat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

data class Role(
    val id: String,
    val name: String,
    val background: String,
    val personality: String,
    val style: String,
    val relationship: String,
    val scene: String
)
data class ChatMessage(val role: String, val content: String, val id: String = UUID.randomUUID().toString())

fun rolePromptFor(role: Role): String = """
    你是沉浸式角色扮演对话引擎。始终以当前角色身份自然地和用户交谈，不要频繁跳出角色解释规则。
    角色名称：${role.name}
    身份背景：${role.background}
    性格：${role.personality}
    说话风格：${role.style}
    与用户的关系：${role.relationship}
    当前场景：${role.scene}
    保持对话连续性，允许角色有自己的想法和情绪。默认使用自然口语中文，回复简洁、有生活感，适合直接朗读。
""".trimIndent()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(20, 22, 28)
        window.navigationBarColor = android.graphics.Color.rgb(20, 22, 28)
        setContent { RoleChatApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoleChatApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rolechat", 0) }
    val scope = rememberCoroutineScope()
    val client = remember {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    var lmUrl by remember { mutableStateOf(prefs.getString("lm_url", "http://192.168.1.100:1234/v1") ?: "") }
    var ttsUrl by remember { mutableStateOf(prefs.getString("tts_url", "http://192.168.1.100:8765/tts") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var voice by remember { mutableStateOf(prefs.getString("voice", "zh-CN-XiaoxiaoNeural") ?: "") }
    var showSettings by remember { mutableStateOf(false) }
    var voiceMode by remember { mutableStateOf(false) }
    var showRoleEditor by remember { mutableStateOf(false) }
    var editingRole by remember { mutableStateOf<Role?>(null) }
    var status by remember { mutableStateOf("请在设置中填写电脑的局域网 IP") }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var selectedRoleId by remember { mutableStateOf(prefs.getString("selected_role", "friend") ?: "friend") }
    var roles by remember {
        mutableStateOf(loadRoles(prefs))
    }
    var histories by remember { mutableStateOf(loadHistories(prefs)) }
    val listState = rememberLazyListState()

    DisposableEffect(context) {
        val owner = context as? LifecycleOwner
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                histories = loadHistories(prefs)
                selectedRoleId = prefs.getString("selected_role", selectedRoleId) ?: selectedRoleId
            }
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    val selectedRole = roles.firstOrNull { it.id == selectedRoleId } ?: roles.firstOrNull() ?: Role("", "", "", "", "", "", "")
    val messages = histories[selectedRole.id] ?: emptyList()

    fun persist() {
        prefs.edit()
            .putString("lm_url", lmUrl)
            .putString("tts_url", ttsUrl)
            .putString("model", model)
            .putString("voice", voice)
            .putString("selected_role", selectedRoleId)
            .putString("roles", JSONArray().apply {
                roles.forEach { r -> put(JSONObject().apply {
                    put("id", r.id); put("name", r.name); put("background", r.background)
                    put("personality", r.personality); put("style", r.style)
                    put("relationship", r.relationship); put("scene", r.scene)
                }) }
            }.toString())
            .putString("histories", JSONObject().apply {
                histories.forEach { (id, list) ->
                    put(id, JSONArray().apply { list.forEach { m ->
                        put(JSONObject().put("role", m.role).put("content", m.content).put("id", m.id))
                    } })
                }
            }.toString())
            .apply()
    }

    suspend fun fetchModelList(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(lmUrl.trimEnd('/') + "/models").get().build()
        client.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("LM Studio 返回 ${response.code}: $body")
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            if (data.length() == 0) error("没有可用模型。请先在 LM Studio 加载模型。")
            data.getJSONObject(0).optString("id")
        }
    }

    suspend fun sendChat(userText: String) {
        if (busy) return
        busy = true
        status = "正在生成回复…"
        val role = selectedRole
        val before = histories[role.id] ?: emptyList()
        val updated = before + ChatMessage("user", userText)
        histories = histories + (role.id to updated)
        input = ""
        persist()
        try {
            val modelId = if (model.isBlank()) fetchModelList().also { model = it } else model
            val system = """
                你是沉浸式角色扮演对话引擎。始终以当前角色身份自然地和用户交谈，不要频繁跳出角色解释规则。
                角色名称：${role.name}
                身份背景：${role.background}
                性格：${role.personality}
                说话风格：${role.style}
                与用户的关系：${role.relationship}
                当前场景：${role.scene}
                保持对话连续性，记住当前角色的聊天历史。不要一味迎合，允许角色有自己的想法和情绪。
                默认使用自然口语中文，回复简洁有生活感。不要声称执行了现实世界中并未执行的操作。
            """.trimIndent()
            val arr = JSONArray().put(JSONObject().put("role", "system").put("content", system))
            // Keep latest 30 messages per role to control context length.
            updated.takeLast(30).forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
            val payload = JSONObject()
                .put("model", modelId)
                .put("messages", arr)
                .put("temperature", 0.85)
                .put("stream", false)
                .put("max_tokens", 700)
            val request = Request.Builder()
                .url(lmUrl.trimEnd('/') + "/chat/completions")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val answer = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("模型请求失败 ${response.code}: $body")
                    JSONObject(body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content").trim()
                }
            }
            histories = histories + (role.id to (updated + ChatMessage("assistant", answer)))
            status = "已连接 · ${role.name}"
            persist()
        } catch (e: Exception) {
            status = "请求失败：${e.message ?: "未知错误"}"
        } finally {
            busy = false
        }
    }

    suspend fun speak(text: String) {
        status = "正在生成语音…"
        try {
            val audio = withContext(Dispatchers.IO) {
                val json = JSONObject().put("text", text).put("voice", voice).toString()
                val request = Request.Builder().url(ttsUrl)
                    .post(json.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("语音服务返回 ${response.code}: ${response.body?.string()}")
                    response.body?.bytes() ?: error("语音服务没有返回音频")
                }
            }
            val temp = File(context.cacheDir, "role_voice.mp3")
            temp.writeBytes(audio)
            withContext(Dispatchers.Main) {
                MediaPlayer().apply {
                    setDataSource(temp.absolutePath)
                    setOnPreparedListener { it.start(); status = "正在播放语音" }
                    setOnCompletionListener { it.release(); status = "语音播放完成" }
                    setOnErrorListener { mp, _, _ -> mp.release(); status = "音频播放失败"; true }
                    prepareAsync()
                }
            }
        } catch (e: Exception) {
            status = "语音失败：${e.message ?: "请检查 TTS 服务"}"
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) status = "需要麦克风权限才能语音输入"
        else startSpeechRecognition(context) { recognized -> input = (input + " " + recognized).trim() }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB6C8FF),
            secondary = Color(0xFFB7DCCB),
            background = Color(0xFF14161C),
            surface = Color(0xFF20232B),
            onBackground = Color(0xFFE8EAF0),
            onSurface = Color(0xFFE8EAF0)
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(selectedRole.name, fontWeight = FontWeight.Bold)
                            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp)
                                .background(Color(0xFF30343E), RoundedCornerShape(24.dp)).padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { voiceMode = false },
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (!voiceMode) Color(0xFFB6C8FF) else Color.Transparent,
                                    contentColor = if (!voiceMode) Color(0xFF14161C) else Color.White
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) { Text("对话") }
                            TextButton(
                                enabled = roles.isNotEmpty(),
                                onClick = { voiceMode = true },
                                colors = ButtonDefaults.textButtonColors(
                                    containerColor = if (voiceMode) Color(0xFFB6C8FF) else Color.Transparent,
                                    contentColor = if (voiceMode) Color(0xFF14161C) else Color.White
                                ),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Default.Call, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("电话")
                            }
                        }
                        IconButton(onClick = { showRoleEditor = true; editingRole = null }) { Icon(Icons.Default.Add, "新建角色") }
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "设置") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF14161C))
                )
            },
            bottomBar = {
                if (voiceMode) {
                    key(selectedRole.id) {
                        VoiceCallScreen(
                            initialRoleId = selectedRole.id,
                            initialRoleName = selectedRole.name,
                            initialRolePrompt = rolePromptFor(selectedRole),
                            onHangup = { voiceMode = false },
                            embedded = true,
                            onHistoryChanged = { histories = loadHistories(prefs) }
                        )
                    }
                } else {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF20232B)).padding(10.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(enabled = roles.isNotEmpty(), onClick = {
                        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                            startSpeechRecognition(context) { recognized -> input = (input + " " + recognized).trim() }
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(Icons.Default.Mic, "语音输入") }
                    Box(
                        Modifier.weight(1f).background(Color(0xFF30343E), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (input.isEmpty()) Text("和角色说点什么…", color = Color.LightGray)
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                            maxLines = 5
                        )
                    }
                    IconButton(enabled = roles.isNotEmpty() && input.isNotBlank() && !busy, onClick = { val text = input.trim(); scope.launch { sendChat(text) } }) {
                        Icon(Icons.Default.Send, "发送")
                    }
                }
                }
            },
            containerColor = Color(0xFF14161C)
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    roles.forEach { role ->
                        FilterChip(
                            selected = role.id == selectedRole.id,
                            onClick = { selectedRoleId = role.id; persist() },
                            label = { Text(role.name) },
                            trailingIcon = if (role.id == selectedRole.id) ({ Icon(Icons.Default.Settings, null, Modifier.size(14.dp).clickable { editingRole = role; showRoleEditor = true }) }) else null
                        )
                    }
                }
                if (roles.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("还没有角色", style = MaterialTheme.typography.titleLarge)
                            Text("从空白开始创建，或使用模板作为起点。模板本身不会成为角色。", color = Color.LightGray)
                            Button(onClick = { editingRole = null; showRoleEditor = true }) { Text("创建自定义角色") }
                        }
                    }
                } else if (messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("开始和 ${selectedRole.name} 对话", style = MaterialTheme.typography.titleLarge)
                            Text("角色记忆彼此独立，切换角色不会混合聊天记录。", color = Color.LightGray)
                            Button(onClick = { editingRole = selectedRole; showRoleEditor = true }) { Text("编辑当前角色") }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            val isUser = msg.role == "user"
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                                Column(
                                    Modifier.widthIn(max = 320.dp)
                                        .background(if (isUser) Color(0xFF33445E) else Color(0xFF252932), RoundedCornerShape(16.dp))
                                        .padding(12.dp)
                                ) {
                                    Text(msg.content, color = Color.White)
                                    if (!isUser) {
                                        Spacer(Modifier.height(4.dp))
                                        TextButton(onClick = { scope.launch { speak(msg.content) } }, contentPadding = PaddingValues(0.dp)) {
                                            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text("朗读")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text("连接设置") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("填写电脑的局域网 IPv4 地址。LM Studio 默认端口 1234。")
                        OutlinedTextField(lmUrl, { lmUrl = it }, label = { Text("LM Studio API 地址") }, singleLine = true)
                        OutlinedTextField(ttsUrl, { ttsUrl = it }, label = { Text("语音服务地址") }, singleLine = true)
                        OutlinedTextField(model, { model = it }, label = { Text("模型 ID（留空自动获取）") }, singleLine = true)
                        OutlinedTextField(voice, { voice = it }, label = { Text("语音音色") }, singleLine = true)
                        Text("示例：LM Studio http://192.168.1.20:1234/v1；TTS http://192.168.1.20:8765/tts", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        persist(); showSettings = false
                        scope.launch {
                            try { val id = fetchModelList(); model = id; persist(); status = "连接成功 · $id" }
                            catch (e: Exception) { status = "连接测试失败：${e.message}" }
                        }
                    }) { Text("保存并测试") }
                },
                dismissButton = { TextButton(onClick = { showSettings = false }) { Text("取消") } }
            )
        }
        if (showRoleEditor) {
            RoleEditorDialog(
                role = editingRole,
                onDismiss = { showRoleEditor = false },
                onSave = { role ->
                    roles = if (roles.any { it.id == role.id }) roles.map { if (it.id == role.id) role else it } else roles + role
                    selectedRoleId = role.id
                    showRoleEditor = false
                    persist()
                },
                onDelete = { role ->
                    if (roles.size > 1) {
                        roles = roles.filterNot { it.id == role.id }
                        histories = histories - role.id
                        if (selectedRoleId == role.id) selectedRoleId = roles.first().id
                        showRoleEditor = false
                        persist()
                    }
                }
            )
        }
    }
}

@Composable
fun RoleEditorDialog(role: Role?, onDismiss: () -> Unit, onSave: (Role) -> Unit, onDelete: (Role) -> Unit) {
    var name by remember(role?.id) { mutableStateOf(role?.name ?: "") }
    var background by remember(role?.id) { mutableStateOf(role?.background ?: "") }
    var personality by remember(role?.id) { mutableStateOf(role?.personality ?: "") }
    var style by remember(role?.id) { mutableStateOf(role?.style ?: "") }
    var relationship by remember(role?.id) { mutableStateOf(role?.relationship ?: "") }
    var scene by remember(role?.id) { mutableStateOf(role?.scene ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (role == null) "创建角色" else "编辑角色") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (role == null) {
                    Text("可选模板（点击后可继续任意修改）", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            name = "知心朋友"; background = "一位熟悉用户的朋友，有自己的生活经历。"
                            personality = "温柔、聪明、幽默，偶尔会调侃用户。"; style = "自然口语化，像日常聊天，不要长篇说教。"
                            relationship = "亲密且互相信任的朋友"; scene = "在一个安静舒适的地方聊天。"
                        }) { Text("知心朋友") }
                        TextButton(onClick = {
                            name = "人生导师"; background = "阅历丰富、务实的导师。"
                            personality = "冷静、睿智、坦诚，不说空话。"; style = "清晰、直接，善于追问和启发。"
                            relationship = "值得信任的顾问"; scene = "正在讨论最近遇到的问题。"
                        }) { Text("人生导师") }
                        TextButton(onClick = {
                            name = "奇幻旅伴"; background = "来自奇幻世界的冒险者。"
                            personality = "勇敢、好奇、富有想象力。"; style = "有画面感，但保持对话自然。"
                            relationship = "并肩旅行的伙伴"; scene = "你们正在一座古城的旅店里休息。"
                        }) { Text("奇幻旅伴") }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("角色名称") }, singleLine = true)
                OutlinedTextField(background, { background = it }, label = { Text("身份背景") })
                OutlinedTextField(personality, { personality = it }, label = { Text("性格") })
                OutlinedTextField(style, { style = it }, label = { Text("说话风格") })
                OutlinedTextField(relationship, { relationship = it }, label = { Text("与用户的关系") })
                OutlinedTextField(scene, { scene = it }, label = { Text("当前场景") })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(Role(role?.id ?: UUID.randomUUID().toString(), name.trim(), background, personality, style, relationship, scene))
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (role != null) TextButton(onClick = { onDelete(role) }) { Text("删除") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

fun loadRoles(prefs: android.content.SharedPreferences): List<Role> {
    val raw = prefs.getString("roles", null)
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Role(o.getString("id"), o.getString("name"), o.optString("background"), o.optString("personality"),
                o.optString("style"), o.optString("relationship"), o.optString("scene"))
        }.filter { it.id.isNotBlank() }.filterNot { role ->
            val isUntouchedSeed = when (role.id) {
                "friend" -> role.name == "知心朋友"
                "mentor" -> role.name == "人生导师"
                "fantasy" -> role.name == "奇幻旅伴"
                else -> false
            }
            val savedHistories = try { JSONObject(prefs.getString("histories", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
            val hasHistory = (savedHistories.optJSONArray(role.id)?.length() ?: 0) > 0
            isUntouchedSeed && !hasHistory
        }
    } catch (_: Exception) { emptyList() }
}

fun loadHistories(prefs: android.content.SharedPreferences): Map<String, List<ChatMessage>> {
    val raw = prefs.getString("histories", null) ?: return emptyMap()
    return try {
        val obj = JSONObject(raw)
        val map = mutableMapOf<String, List<ChatMessage>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val arr = obj.getJSONArray(key)
            map[key] = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(m.getString("role"), m.getString("content"), m.optString("id", UUID.randomUUID().toString()))
            }
        }
        map
    } catch (_: Exception) { emptyMap() }
}

fun startSpeechRecognition(context: android.content.Context, onResult: (String) -> Unit) {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) return
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话")
    }
    try {
        val launcher = (context as? ComponentActivity)
        if (launcher != null) {
            // Use platform speech UI; its result is returned via activity result only in a dedicated launcher.
            // Fallback to SpeechRecognizer is implemented below through a small listener.
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) { recognizer.destroy() }
                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!text.isNullOrBlank()) onResult(text)
                    recognizer.destroy()
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        }
    } catch (_: Exception) {}
}
