package io.github.jarvisassist

import android.os.SystemClock
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Answers on the phone itself with Gemini Nano, through Android's AICore. No network and no PC needed.
 *
 * Google only lets an app use it while the app is on screen, so call [ask] from the visible ask screen.
 * This never starts a model download by itself: if Nano is not already on the phone, it says so and stops.
 * Calls arrive on a background thread.
 */
class NanoEngine {
    interface Listener {
        fun onStatus(text: String)
        fun onDelta(text: String)
        fun onDone(full: String, stats: Stats)
        fun onError(message: String)
    }

    interface StatusListener {
        /** [state]: 3 = ready, 2 = downloading, 1 = needs a download, 0 = unavailable, -1 = could not check. */
        fun onStatus(text: String, state: Int)
    }

    interface DownloadListener {
        fun onProgress(bytes: Long)
        fun onDone()
        fun onError(message: String)
    }

    /** Timings for the benchmark line: model load, time to the first word, total time, and the answer's length. */
    data class Stats(val loadMs: Long, val firstTokenMs: Long, val totalMs: Long, val words: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var model: GenerativeModel? = null
    private var downloadJob: Job? = null
    private var warmedUp = false

    /** Asks AICore whether Gemini Nano is on the phone. Never downloads anything. */
    fun checkStatus(listener: StatusListener) {
        scope.launch {
            try {
                val m = model ?: Generation.getClient().also { model = it }
                when (val s = m.checkStatus()) {
                    FeatureStatus.AVAILABLE -> listener.onStatus("Ready: Gemini Nano is on this phone.", 3)
                    FeatureStatus.DOWNLOADING -> listener.onStatus("Gemini Nano is downloading.", 2)
                    FeatureStatus.DOWNLOADABLE -> listener.onStatus("Not on this phone yet. It needs a download from Google.", 1)
                    FeatureStatus.UNAVAILABLE -> listener.onStatus("Gemini Nano is not available on this phone.", 0)
                    else -> listener.onStatus("Gemini Nano status $s.", -1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onStatus(explain(e), -1)
            }
        }
    }

    /** Starts the download of Gemini Nano. Only call this from an explicit tap by the owner, and on Wi-Fi. */
    fun download(listener: DownloadListener) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                val m = model ?: Generation.getClient().also { model = it }
                m.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> listener.onProgress(0)
                        is DownloadStatus.DownloadProgress -> listener.onProgress(status.totalBytesDownloaded)
                        is DownloadStatus.DownloadCompleted -> listener.onDone()
                        is DownloadStatus.DownloadFailed -> listener.onError(status.e.message ?: "The download failed.")
                        else -> {}
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onError(explain(e))
            }
        }
    }

    /** Stops watching the download. Google's service may keep downloading in the background. */
    fun stopDownload() {
        downloadJob?.cancel()
    }

    /** [past] is earlier turns as plain lines, for example "Owner: hello" and "Jarvis: hi there". */
    fun ask(past: List<String>, text: String, listener: Listener) {
        job?.cancel()
        job = scope.launch {
            try {
                val m = model ?: Generation.getClient().also { model = it }
                val status = m.checkStatus()
                if (status != FeatureStatus.AVAILABLE) {
                    listener.onError(describe(status))
                    return@launch
                }
                var loadMs = 0L
                if (!warmedUp) {
                    listener.onStatus("Loading Gemini Nano...")
                    val loadStart = SystemClock.elapsedRealtime()
                    m.warmup()
                    loadMs = SystemClock.elapsedRealtime() - loadStart
                    warmedUp = true
                }
                listener.onStatus("Thinking on the phone...")

                val request = generateContentRequest(TextPart(buildPrompt(past, text))) {
                    systemInstruction = SystemInstruction(SYSTEM_PROMPT)
                    temperature = 0.3f
                    maxOutputTokens = 256
                }
                val started = SystemClock.elapsedRealtime()
                var firstMs = -1L
                val answer = StringBuilder()
                m.generateContentStream(request).collect { chunk ->
                    val piece = chunk.candidates.firstOrNull()?.text ?: ""
                    if (piece.isEmpty()) return@collect
                    if (firstMs < 0) firstMs = SystemClock.elapsedRealtime() - started
                    // Streams normally send only the new words; tolerate one that repeats the answer so far.
                    val delta = if (answer.isNotEmpty() && piece.startsWith(answer)) piece.substring(answer.length) else piece
                    answer.append(delta)
                    if (delta.isNotEmpty()) listener.onDelta(delta)
                }
                val total = SystemClock.elapsedRealtime() - started
                val full = answer.toString().trim()
                if (full.isEmpty()) {
                    listener.onError("Gemini Nano gave no answer. Try rephrasing.")
                } else {
                    listener.onDone(full, Stats(loadMs, if (firstMs < 0) total else firstMs, total, full.split(Regex("\\s+")).size))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                listener.onError(explain(e))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun close() {
        scope.cancel()
        try {
            model?.close()
        } catch (ignored: Exception) {
            // already closed
        }
        model = null
    }

    private fun describe(status: Int): String = when (status) {
        FeatureStatus.UNAVAILABLE -> "Gemini Nano is not available on this phone."
        FeatureStatus.DOWNLOADABLE -> "Gemini Nano is not on the phone yet. Open Settings and tap " +
            "\"Download Gemini Nano\" (Wi-Fi only). Nothing downloads until you do."
        FeatureStatus.DOWNLOADING -> "Gemini Nano is still downloading. Try again later."
        else -> "Gemini Nano is not ready (status $status)."
    }

    private fun explain(e: Exception): String {
        val text = (e.message ?: e.javaClass.simpleName)
        val lower = text.lowercase()
        return when {
            "background" in lower -> "Gemini Nano only runs while Jarvis is on the screen."
            "quota" in lower || "busy" in lower -> "Gemini Nano is busy or over its limit. Try again in a moment."
            else -> "On-device error: $text"
        }
    }

    private fun buildPrompt(past: List<String>, text: String): String {
        val turns = past.takeLast(MAX_TURNS).joinToString("\n")
        val prompt = if (turns.isEmpty()) "Owner: $text\nJarvis:" else "Conversation so far:\n$turns\n\nOwner: $text\nJarvis:"
        return if (prompt.length > MAX_CHARS) prompt.takeLast(MAX_CHARS) else prompt
    }

    private companion object {
        const val MAX_TURNS = 8
        const val MAX_CHARS = 6000 // Nano's input limit is about 4000 tokens
        const val SYSTEM_PROMPT = "You are Jarvis, the owner's phone assistant. Your reply is read aloud, so use short, " +
            "natural spoken sentences with no markdown, lists or emoji. Use SI units, 24-hour time and British " +
            "spelling. Say plainly when you do not know. Never invent facts."
    }
}
