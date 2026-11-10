package com.jordan.audioreader

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.LongBuffer
import java.util.Collections

class PhononEngine(private val context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // URL officielle du modèle Phonon français sur Hugging Face
    private val modelUrl = "https://huggingface.co/gradium/phonon-40m-fr/resolve/main/model.onnx"
    val modelFile: File get() = File(context.filesDir, "phonon_model.onnx")

    var lastErrorMessage: String = ""

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 100_000
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            var currentUrl = modelUrl
            var redirectCount = 0
            var connection: HttpURLConnection

            while (true) {
                val url = URL(currentUrl)
                connection = url.openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                
                // Envoi du jeton d'authentification uniquement vers les serveurs Hugging Face (évite les conflits avec le stockage CDN)
                if (url.host.contains("huggingface.co")) {
                    connection.setRequestProperty("Authorization", "Bearer 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")
                }

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == 307 || responseCode == 308) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl != null && redirectCount < 5) {
                        currentUrl = newUrl
                        redirectCount++
                        continue
                    }
                }

                if (responseCode !in 200..299) {
                    lastErrorMessage = "Erreur serveur : HTTP \$responseCode"
                    return@withContext false
                }
                break
            }

            val fileLength = connection.contentLength
            val input = connection.inputStream
            val tempFile = File(context.filesDir, "phonon_model.tmp")
            val output = FileOutputStream(tempFile)

            val data = ByteArray(8192)
            var total: Long = 0
            var count: Int

            while (input.read(data).also { count = it } != -1) {
                total += count
                if (fileLength > 0) {
                    val progress = ((total * 100) / fileLength).toInt()
                    withContext(Dispatchers.Main) {
                        onProgress(progress)
                    }
                }
                output.write(data, 0, count)
            }

            output.flush()
            output.close()
            input.close()
            connection.disconnect()

            if (tempFile.renameTo(modelFile)) {
                initSession()
                true
            } else {
                lastErrorMessage = "Erreur d'écriture sur le stockage local"
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMessage = e.localizedMessage ?: e.toString()
            false
        }
    }

    fun initSession(): Boolean {
        if (!isModelReady()) return false
        return try {
            if (ortEnv == null) {
                ortEnv = OrtEnvironment.getEnvironment()
            }
            if (ortSession == null) {
                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                }
                ortSession = ortEnv?.createSession(modelFile.absolutePath, sessionOptions)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun synthesizeToWav(text: String, outputFile: File): Boolean = withContext(Dispatchers.Default) {
        if (ortSession == null) {
            if (!initSession()) return@withContext false
        }

        val session = ortSession ?: return@withContext false
        val env = ortEnv ?: return@withContext false

        try {
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            val pcmAudioChunks = mutableListOf<ShortArray>()

            for (sentence in sentences) {
                val tokenIds = phonemize(sentence)
                if (tokenIds.isEmpty()) continue

                val shape = longArrayOf(1, tokenIds.size.toLong())
                val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)
                val inputs = Collections.singletonMap("input_ids", tensor)
                val results = session.run(inputs)

                @Suppress("UNCHECKED_CAST")
                val outputFloats = (results[0].value as Array<FloatArray>)[0]

                val pcm = ShortArray(outputFloats.size) { i ->
                    (outputFloats[i].coerceIn(-1.0f, 1.0f) * 32767).toInt().toShort()
                }
                pcmAudioChunks.add(pcm)

                tensor.close()
                results.close()
            }

            writeWavFile(pcmAudioChunks, outputFile, sampleRate = 24000)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun phonemize(text: String): LongArray {
        return text.map { it.code.toLong() }.toLongArray()
    }

    private fun writeWavFile(chunks: List<ShortArray>, file: File, sampleRate: Int) {
        val totalSamples = chunks.sumOf { it.size }
        val byteRate = sampleRate * 2

        FileOutputStream(file).use { out ->
            out.write("RIFF".toByteArray())
            out.write(intToByteArray(36 + totalSamples * 2))
            out.write("WAVEfmt ".toByteArray())
            out.write(intToByteArray(16))
            out.write(shortToByteArray(1))
            out.write(shortToByteArray(1))
            out.write(intToByteArray(sampleRate))
            out.write(intToByteArray(byteRate))
            out.write(shortToByteArray(2))
            out.write(shortToByteArray(16))
            out.write("data".toByteArray())
            out.write(intToByteArray(totalSamples * 2))

            for (chunk in chunks) {
                for (sample in chunk) {
                    out.write(sample.toInt() and 0xFF)
                    out.
