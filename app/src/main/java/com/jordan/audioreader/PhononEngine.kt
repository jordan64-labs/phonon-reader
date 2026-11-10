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

    // URL officielle du modèle Phonon optimisé au format ONNX
    private val modelUrl = "https://huggingface.co/gradium-ai/phonon/resolve/main/model.onnx"
    val modelFile: File get() = File(context.filesDir, "phonon_model.onnx")

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 100_000
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL(modelUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.connect()

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

            if (tempFile.renameTo(modelFile)) {
                initSession()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                    out.write((sample.toInt() shr 8) and 0xFF)
                }
            }
        }
    }

    private fun intToByteArray(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )

    private fun shortToByteArray(v: Short) = byteArrayOf(
        (v.toInt() and 0xFF).toByte(),
        ((v.toInt() shr 8) and 0xFF).toByte()
    )
}
