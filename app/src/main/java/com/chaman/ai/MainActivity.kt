package com.chaman.ai

import android.content.Context
import android.os.Bundle
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

private const val GROQ_MODEL = "openai/gpt-oss-120b"
private const val NVIDIA_MODEL = "nvidia/nemotron-3.5-super-vl-120b-a12b"
private const val GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"
private const val NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChamanApp() }
    }
}

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String,
    val text: String,
    val model: String? = null
)

data class ImageAttachment(val dataUrl: String, val label: String)

class KeyVault(context: Context) {
    private val prefs = context.getSharedPreferences("chaman_secure_keys", Context.MODE_PRIVATE)
    private val alias = "chaman_api_key_aes"

    private fun key(): SecretKey {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(provider: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(provider).apply()
            return
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(provider, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun read(provider: String): String {
        val packed = prefs.getString(provider, null) ?: return ""
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val payload = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun has(provider: String): Boolean = prefs.contains(provider)
}

class ChamanViewModel : ViewModel() {
    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf("Ready")
        private set
    var settingsOpen by mutableStateOf(false)
    var groqConfigured by mutableStateOf(false)
    var nvidiaConfigured by mutableStateOf(false)
    var activeModel by mutableStateOf("Auto")
    private var vault: KeyVault? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun initialize(context: Context) {
        if (vault != null) return
        vault = KeyVault(context.applicationContext)
        groqConfigured = vault!!.has("groq")
        nvidiaConfigured = vault!!.has("nvidia")
    }

    fun saveKey(provider: String, value: String) {
        vault?.save(provider, value.trim())
        groqConfigured = vault?.has("groq") == true
        nvidiaConfigured = vault?.has("nvidia") == true
        status = "$provider key saved"
    }

    fun send(text: String, image: ImageAttachment? = null) {
        if (busy || (text.isBlank() && image == null)) return
        val prompt = text.trim()
        messages = messages + ChatMessage(role = "user", text = if (image != null) {
            if (prompt.isBlank()) " [Image: ${image.label}]" else "$prompt\n[Image: ${image.label}]"
        } else prompt)
        val assistant = ChatMessage(role = "assistant", text = "")
        messages = messages + assistant
        busy = true
        val provider = if (image != null) "nvidia" else "groq"
        val model = if (provider == "groq") GROQ_MODEL else NVIDIA_MODEL
        activeModel = if (provider == "groq") "Groq · GPT-OSS 120B" else "NVIDIA · Nemotron VL"
        val apiKey = vault?.read(provider).orEmpty()
        if (apiKey.isBlank()) {
            updateLastAssistant("API key missing. Settings kholo aur $provider key add karo.")
            busy = false
            status = "$provider API key required"
            return
        }
        status = "Connecting to $provider…"
        viewModelScope.launch {
            try {
                streamChat(provider, apiKey, model, prompt, image) { delta ->
                    val last = messages.lastOrNull()
                    if (last?.role == "assistant") {
                        messages = messages.dropLast(1) + last.copy(text = last.text + delta, model = model)
                    }
                }
                status = "Completed · $provider"
            } catch (e: Exception) {
                updateLastAssistant("Request failed: ${e.message ?: "Unknown error"}")
                status = "Request failed"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun streamChat(
        provider: String,
        apiKey: String,
        model: String,
        prompt: String,
        image: ImageAttachment?,
        onDelta: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val history = messages.dropLast(1).takeLast(20)
        val jsonMessages = JSONArray()
        jsonMessages.put(JSONObject().put("role", "system").put("content",
            "You are CHAMAN, a helpful personal AI assistant. Reply clearly and naturally."))
        history.forEach { msg ->
            if (msg.role == "user") {
                if (image != null && msg.id == history.lastOrNull { it.role == "user" }?.id) {
                    val content = JSONArray()
                    if (prompt.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", prompt))
                    content.put(JSONObject().put("type", "image_url").put("image_url",
                        JSONObject().put("url", image.dataUrl)))
                    jsonMessages.put(JSONObject().put("role", "user").put("content", content))
                } else {
                    jsonMessages.put(JSONObject().put("role", "user").put("content", msg.text))
                }
            } else {
                jsonMessages.put(JSONObject().put("role", "assistant").put("content", msg.text))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", jsonMessages)
            .put("stream", true)
        val url = if (provider == "groq") GROQ_URL else NVIDIA_URL
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val error = response.body?.string().orEmpty().take(1200)
                throw IOException("HTTP ${response.code}: $error")
            }
            val source = response.body?.source() ?: throw IOException("Empty response")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                try {
                    val delta = JSONObject(payload).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta")
                        ?.optString("content").orEmpty()
                    if (delta.isNotEmpty()) withContext(Dispatchers.Main) { onDelta(delta) }
                } catch (_: Exception) { }
            }
        }
    }

    private fun updateLastAssistant(text: String) {
        val last = messages.lastOrNull()
        if (last?.role == "assistant") messages = messages.dropLast(1) + last.copy(text = text)
    }

    fun clearChat() { messages = emptyList(); status = "New chat" }
}

@Composable
fun ChamanApp(vm: ChamanViewModel = viewModel()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.initialize(context) }
    var input by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<ImageAttachment?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null) {
                    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    image = ImageAttachment("data:$mime;base64,$encoded", "attached image")
                }
            } catch (_: Exception) { vm.status = "Could not read image" }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.messages.size, vm.messages.lastOrNull()?.text) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF55745F),
            onPrimary = Color.White,
            background = Color(0xFFF7F8F5),
            surface = Color(0xFFF7F8F5),
            secondary = Color(0xFF8CA391)
        )
    ) {
        Scaffold(
            containerColor = Color(0xFFF7F8F5),
            topBar = {
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFFF7F8F5)).padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("CHAMAN", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Color(0xFF26372C), letterSpacing = 1.2.sp)
                        Text(vm.activeModel, fontSize = 11.sp, color = Color(0xFF718176))
                    }
                    IconButton(onClick = { vm.settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color(0xFF536B59))
                    }
                }
            },
            bottomBar = {
                Column(Modifier.fillMaxWidth().background(Color(0xFFF7F8F5)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                    if (image != null) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Image attached", Modifier.weight(1f), color = Color(0xFF55745F), fontSize = 12.sp)
                            IconButton(onClick = { image = null }) { Icon(Icons.Default.Close, contentDescription = "Remove image") }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(24.dp))
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { picker.launch("image/*") }, enabled = !vm.busy) {
                            Icon(Icons.Default.Add, contentDescription = "Attach image", tint = Color(0xFF55745F))
                        }
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF26372C), fontSize = 15.sp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            decorationBox = { inner ->
                                if (input.isEmpty()) Text("Message CHAMAN…", color = Color(0xFF9AA59C), fontSize = 14.sp)
                                inner()
                            }
                        )
                        IconButton(
                            enabled = !vm.busy && (input.isNotBlank() || image != null),
                            onClick = {
                                vm.send(input, image)
                                input = ""
                                image = null
                            }
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = "Send", tint = Color(0xFF55745F))
                        }
                    }
                    Text(vm.status, Modifier.padding(start = 12.dp, top = 5.dp), fontSize = 10.sp, color = Color(0xFF89958B))
                }
            }
        ) { padding ->
            if (vm.messages.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("CHAMAN", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color(0xFF354C3C), letterSpacing = 2.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("How can I help you today?", color = Color(0xFF849187), fontSize = 14.sp)
                    Spacer(Modifier.height(20.dp))
                    Text("Your personal AI assistant", color = Color(0xFFABB4AC), fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(vm.messages, key = { it.id }) { msg ->
                        if (msg.role == "user") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Text(
                                    msg.text,
                                    Modifier.widthIn(max = 300.dp).background(Color(0xFFE5ECE5), RoundedCornerShape(18.dp))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    color = Color(0xFF26372C), fontSize = 14.sp
                                )
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                Text(msg.text.ifBlank { if (vm.busy) "Thinking…" else "" },
                                    color = Color(0xFF26372C), fontSize = 15.sp, lineHeight = 23.sp)
                            }
                        }
                    }
                }
            }
        }

        if (vm.settingsOpen) {
            SettingsDialog(
                vm = vm,
                onDismiss = { vm.settingsOpen = false }
            )
        }
    }
}

@Composable
fun SettingsDialog(vm: ChamanViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val vault = remember { KeyVault(context) }
    var groqKey by remember { mutableStateOf("") }
    var nvidiaKey by remember { mutableStateOf("") }
    var showGroq by remember { mutableStateOf(false) }
    var showNvidia by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI Providers", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Groq · GPT-OSS 120B", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = groqKey, onValueChange = { groqKey = it },
                    label = { Text(if (vault.has("groq")) "Groq key saved · enter to replace" else "Groq API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("NVIDIA · Nemotron 3.5 Super VL", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = nvidiaKey, onValueChange = { nvidiaKey = it },
                    label = { Text(if (vault.has("nvidia")) "NVIDIA key saved · enter to replace" else "NVIDIA API key") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Text requests use Groq. Image requests use NVIDIA.", fontSize = 11.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (groqKey.isNotBlank()) vm.saveKey("groq", groqKey)
                if (nvidiaKey.isNotBlank()) vm.saveKey("nvidia", nvidiaKey)
                onDismiss()
            }) { Text("Save keys") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
