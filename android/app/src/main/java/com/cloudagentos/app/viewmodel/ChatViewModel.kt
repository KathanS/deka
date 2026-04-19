package com.cloudagentos.app.viewmodel

import android.app.Application
import android.app.PictureInPictureParams
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Rational
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cloudagentos.app.data.*
import com.cloudagentos.app.agent.DekaAgent
import com.cloudagentos.app.agent.ModelBackend
import com.cloudagentos.app.auth.ApiKeyAuth
import com.cloudagentos.app.config.Flags
import com.cloudagentos.app.engine.UserContextDB
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

private const val TAG = "ChatViewModel"
private const val SAMPLE_RATE = 16000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val MIN_RECORD_MS = 500L

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("deka", android.content.Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // Connection state — always "connected" in v3 (no server needed)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _pendingAction = MutableStateFlow<ActionConfirm?>(null)
    val pendingAction: StateFlow<ActionConfirm?> = _pendingAction.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var recordingStartTime = 0L
    private val activePlayers = mutableListOf<MediaPlayer>()

    // v3 — Direct API agent (no server needed)
    private var dekaAgent: DekaAgent? = null
    private val userContextDB = UserContextDB(application)
    val auth = ApiKeyAuth(application)

    val userId: String
        get() {
            var id = prefs.getString("user_id", null)
            if (id == null) {
                id = "deka-${java.util.UUID.randomUUID().toString().take(8)}"
                prefs.edit().putString("user_id", id).apply()
            }
            return id
        }

    val isAuthenticated: Boolean
        get() = auth.isAuthenticated

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        initAgent()
    }

    private var currentRequestId: String? = null

    private fun initAgent() {
        val savedBackend = prefs.getString("model_backend", Flags.DEFAULT_MODEL.name) ?: Flags.DEFAULT_MODEL.name
        val backend = try { ModelBackend.valueOf(savedBackend) } catch (_: Exception) { Flags.DEFAULT_MODEL }
        dekaAgent = DekaAgent(getToken = { auth.getValidToken() }, backend = backend)
        setupAgentCallbacks()
    }

    fun switchModel(backend: ModelBackend) {
        prefs.edit().putString("model_backend", backend.name).apply()
        dekaAgent?.backend = backend
        dekaAgent?.clearConversation()
    }

    private fun setupAgentCallbacks() {
        dekaAgent?.onStatusUpdate = { status ->
            viewModelScope.launch(Dispatchers.Main) {
                val reqId = currentRequestId ?: return@launch
                // Update the existing status bubble (same request only)
                addOrUpdateAgentMessage(
                    messageId = reqId,
                    content = "⏳ $status",
                    agentTag = "deka",
                    isStreaming = true
                )
            }
        }
        dekaAgent?.onComplete = { response ->
            viewModelScope.launch(Dispatchers.Main) {
                _isProcessing.value = false
                val reqId = currentRequestId
                // Remove the status bubble, add final response as new message
                if (reqId != null) {
                    removeMessage(reqId)
                }
                addAgentMessage(content = response, agentTag = "deka")
                currentRequestId = null
            }
        }
        dekaAgent?.onError = { error ->
            viewModelScope.launch(Dispatchers.Main) {
                _isProcessing.value = false
                val reqId = currentRequestId
                if (reqId != null) {
                    removeMessage(reqId)
                }
                currentRequestId = null
                addAgentMessage(content = "❌ $error", agentTag = "system")
            }
        }
    }

    private fun removeMessage(messageId: String) {
        val current = _messages.value.toMutableList()
        current.removeAll { it.id == messageId }
        _messages.value = current
    }

    private fun addOrUpdateAgentMessage(messageId: String?, content: String, agentTag: String?, isStreaming: Boolean) {
        val current = _messages.value.toMutableList()
        if (messageId != null) {
            val idx = current.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(content = content, isStreaming = isStreaming)
                _messages.value = current
                return
            }
        }
        current.add(ChatMessage(
            id = messageId ?: java.util.UUID.randomUUID().toString(),
            role = MessageRole.AGENT,
            content = content,
            agentTag = agentTag,
            isStreaming = isStreaming
        ))
        _messages.value = current
    }

    private fun appendStreamingToken(messageId: String?, token: String, agentTag: String?, done: Boolean) {
        val current = _messages.value.toMutableList()
        if (messageId != null) {
            val idx = current.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                val updated = current[idx].copy(
                    content = current[idx].content + token,
                    isStreaming = !done,
                    agentTag = agentTag ?: current[idx].agentTag
                )
                current[idx] = updated
                _messages.value = current
                return
            }
        }
        current.add(ChatMessage(
            id = messageId ?: java.util.UUID.randomUUID().toString(),
            role = MessageRole.AGENT,
            content = token,
            agentTag = agentTag,
            isStreaming = !done
        ))
        _messages.value = current
    }

    private fun addAgentMessage(content: String, agentTag: String?) {
        val current = _messages.value.toMutableList()
        current.add(ChatMessage(
            role = MessageRole.AGENT,
            content = content,
            agentTag = agentTag
        ))
        _messages.value = current
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        if (_isProcessing.value) return

        val userMsg = ChatMessage(role = MessageRole.USER, content = text)
        _messages.value = _messages.value + userMsg

        if (dekaAgent == null) {
            initAgent()
        }

        // Each request gets a unique ID so messages don't overwrite each other
        currentRequestId = "req-${System.currentTimeMillis()}"
        _isProcessing.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                dekaAgent?.run(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _isProcessing.value = false
                    addAgentMessage(content = "❌ Error: ${e.message}", agentTag = "system")
                }
            }
        }
    }

    fun onAuthComplete() {
        initAgent()
    }

    fun logout() {
        auth.logout()
        dekaAgent = null
    }

    fun startRecording() {
        if (_isRecording.value) return
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Bad audio config")
            return
        }
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return
            }
            pcmBuffer = ByteArrayOutputStream()
            recordingStartTime = System.currentTimeMillis()
            audioRecord?.startRecording()
            _isRecording.value = true

            recordingJob = viewModelScope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isActive && _isRecording.value) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        pcmBuffer.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
        }
    }

    fun stopRecording() {
        if (!_isRecording.value) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        _isRecording.value = false
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        if (elapsed < MIN_RECORD_MS) {
            Log.d(TAG, "Recording too short, discarding")
            return
        }

        val pcmData = pcmBuffer.toByteArray()
        viewModelScope.launch(Dispatchers.IO) {
            // TODO: Add on-device STT (Whisper) for voice → text transcription
            // For now, voice is not supported in serverless mode
            withContext(Dispatchers.Main) {
                addAgentMessage(content = "Voice input coming soon! Please type your message.", agentTag = "system")
            }
        }
    }

    fun clearHistory() {
        _messages.value = emptyList()
        dekaAgent?.clearConversation()
    }

    fun approveAction(actionId: String) {
        _pendingAction.value = null
    }

    fun denyAction(actionId: String) {
        _pendingAction.value = null
    }

    private fun playAudio(base64Mp3: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tmpFile = java.io.File(getApplication<Application>().cacheDir, "tts_${System.currentTimeMillis()}.mp3")
            try {
                val bytes = Base64.decode(base64Mp3, Base64.DEFAULT)
                tmpFile.writeBytes(bytes)
                withContext(Dispatchers.Main) {
                    val player = MediaPlayer()
                    activePlayers.add(player)
                    player.setDataSource(tmpFile.absolutePath)
                    player.setOnCompletionListener {
                        activePlayers.remove(it)
                        it.release()
                        tmpFile.delete()
                    }
                    player.prepare()
                    player.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to play audio", e)
                tmpFile.delete()
            }
        }
    }

    private fun pcmToWav(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)
        val dos = DataOutputStream(out)

        fun writeIntLE(v: Int) { dos.writeInt(Integer.reverseBytes(v)) }
        fun writeShortLE(v: Short) { dos.writeShort(java.lang.Short.reverseBytes(v).toInt()) }

        dos.writeBytes("RIFF")
        writeIntLE(36 + dataSize)
        dos.writeBytes("WAVE")
        dos.writeBytes("fmt ")
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels.toShort())
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample.toShort())
        dos.writeBytes("data")
        writeIntLE(dataSize)
        dos.write(pcm)
        dos.flush()
        return out.toByteArray()
    }

    override fun onCleared() {
        super.onCleared()
        activePlayers.forEach { it.release() }
        activePlayers.clear()
        getApplication<Application>().cacheDir
            .listFiles { f -> f.name.startsWith("tts_") && f.name.endsWith(".mp3") }
            ?.forEach { it.delete() }
    }
}
