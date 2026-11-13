package com.gab.bell

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bell's brain: a small AI language model that runs entirely on the phone.
 * The model file is downloaded once into the app's private storage, then
 * everything works offline. Nothing Bell thinks about leaves the phone.
 */
@CapacitorPlugin(name = "BellBrain")
class BellBrainPlugin : Plugin() {
    private val worker = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newSingleThreadExecutor()
    private val cancelDownload = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)
    private var engine: Engine? = null
    private var backendName = ""

    private fun modelFile() = File(context.filesDir, "bell-brain.litertlm")
    private fun partFile() = File(context.filesDir, "bell-brain.litertlm.part")
    private fun partUrlFile() = File(context.filesDir, "bell-brain.part-url")

    @PluginMethod
    fun status(call: PluginCall) {
        val ret = JSObject()
        ret.put("downloaded", modelFile().exists())
        ret.put("modelBytes", if (modelFile().exists()) modelFile().length() else 0L)
        ret.put("partialBytes", if (partFile().exists()) partFile().length() else 0L)
        ret.put("downloading", downloading.get())
        ret.put("loaded", engine != null)
        ret.put("backend", backendName)
        ret.put("freeBytes", context.filesDir.usableSpace)
        call.resolve(ret)
    }

    /** Downloads the model, resuming a partial download if there is one. */
    @PluginMethod
    fun download(call: PluginCall) {
        val url = call.getString("url") ?: return call.reject("No model address given")
        val expected = call.getLong("bytes") ?: 0L
        if (!downloading.compareAndSet(false, true)) return call.reject("Already downloading")
        cancelDownload.set(false)
        call.setKeepAlive(true)
        downloader.execute {
            try {
                var link = url
                var conn: HttpURLConnection
                var redirects = 0
                val part = partFile()
                // a half-finished download of a different brain can't be continued
                val partUrl = if (partUrlFile().exists()) partUrlFile().readText() else ""
                if (partUrl != url && part.exists()) part.delete()
                partUrlFile().writeText(url)
                while (true) {
                    conn = URL(link).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 30000
                    conn.readTimeout = 60000
                    if (part.exists() && part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                    val code = conn.responseCode
                    if (code in 300..399 && redirects < 8) {
                        link = URL(URL(link), conn.getHeaderField("Location")).toString()
                        conn.disconnect(); redirects++; continue
                    }
                    break
                }
                val code = conn.responseCode
                val resume = code == 206
                if (code != 200 && code != 206) throw Exception("The download server answered $code")
                if (!resume && part.exists()) part.delete()
                val already = if (resume) part.length() else 0L
                val total = if (expected > 0) expected else already + conn.contentLengthLong
                if (context.filesDir.usableSpace < (total - already) + 200_000_000L) throw Exception("not_enough_space")
                conn.inputStream.use { input ->
                    FileOutputStream(part, resume).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var got = already
                        var lastNotify = 0L
                        while (true) {
                            if (cancelDownload.get()) throw Exception("cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 400) {
                                lastNotify = now
                                val p = JSObject(); p.put("received", got); p.put("total", total)
                                notifyListeners("downloadProgress", p)
                            }
                        }
                    }
                }
                if (expected > 0 && part.length() != expected) throw Exception("The download was incomplete. Try again.")
                // put the old brain to sleep first, so the next load wakes up the new one
                worker.submit { try { engine?.close() } catch (_: Exception) {}; engine = null; backendName = "" }.get()
                if (!part.renameTo(modelFile())) throw Exception("Couldn't save the brain file")
                partUrlFile().delete()
                call.resolve(JSObject().put("bytes", modelFile().length()))
            } catch (e: Exception) {
                call.reject(e.message ?: "Download failed")
            } finally {
                downloading.set(false)
                call.setKeepAlive(false)
            }
        }
    }

    @PluginMethod
    fun cancelDownload(call: PluginCall) {
        cancelDownload.set(true)
        call.resolve()
    }

    /** Deletes the model from the phone. */
    @PluginMethod
    fun remove(call: PluginCall) {
        worker.execute {
            try { engine?.close() } catch (_: Exception) {}
            engine = null
            modelFile().delete(); partFile().delete()
            call.resolve()
        }
    }

    /** Wakes the brain up. Tries the graphics chip first, then the main processor. */
    @PluginMethod
    fun load(call: PluginCall) {
        worker.execute {
            if (engine != null) return@execute call.resolve(JSObject().put("backend", backendName))
            if (!modelFile().exists()) return@execute call.reject("not_downloaded")
            val attempts = listOf("GPU" to { Backend.GPU() }, "CPU" to { Backend.CPU() })
            var lastError: Exception? = null
            for ((name, make) in attempts) {
                try {
                    val e = Engine(EngineConfig(modelPath = modelFile().absolutePath, backend = make(), cacheDir = context.cacheDir.path))
                    e.initialize()
                    engine = e
                    backendName = name
                    return@execute call.resolve(JSObject().put("backend", name))
                } catch (e: Exception) {
                    lastError = e
                } catch (e: Error) {
                    lastError = Exception(e.message)
                }
            }
            call.reject("load_failed: " + (lastError?.message ?: "unknown"))
        }
    }

    /** Thinks about one prompt and streams the answer back piece by piece. */
    @PluginMethod
    fun generate(call: PluginCall) {
        val prompt = call.getString("prompt") ?: return call.reject("No prompt")
        val system = call.getString("system") ?: "You are Bell, a warm and helpful personal assistant."
        val temperature = (call.getDouble("temperature") ?: 0.6).toDouble()
        worker.execute {
            val e = engine ?: return@execute call.reject("not_loaded")
            try {
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
                )
                val conversation = e.createConversation(config)
                val sb = StringBuilder()
                try {
                    runBlocking {
                        conversation.sendMessageAsync(prompt).collect { piece ->
                            val text = piece.toString()
                            sb.append(text)
                            notifyListeners("token", JSObject().put("text", text))
                        }
                    }
                } finally {
                    try { conversation.close() } catch (_: Exception) {}
                }
                call.resolve(JSObject().put("text", sb.toString()))
            } catch (ex: Exception) {
                call.reject("generate_failed: " + (ex.message ?: "unknown"))
            }
        }
    }
}
