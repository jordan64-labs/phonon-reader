package com.jordan.audioreader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class PhononEngine(private val context: Context) {

    companion object {
        init {
            System.loadLibrary("phonon_jni")
        }
    }

    private val baseReleaseUrl = "https://github.com/jordan64-labs/phonon-reader/releases/download/V1.0"

    val modelFile: File get() = File(context.filesDir, "model.q8.gguf")
    val tokenizerFile: File get() = File(context.filesDir, "tokenizer.json")
    val voiceFile: File get() = File(context.filesDir, "default-voice.safetensors")

    var lastErrorMessage: String = ""
    private var isNativeInitialized = false

    // Fonctions C++ JNI
    private external fun nativeInit(modelPath: String, tokenizerPath: String, voicePath: String): Boolean
    private external fun nativeSynthesize(text: String): ShortArray?

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 50_000_000 &&
               tokenizerFile.exists() && tokenizerFile.length() > 1_000 &&
               voiceFile.exists() && voiceFile.length() > 1_000
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val filesToDownload = listOf(
            Triple("$baseReleaseUrl/model.q8.gguf", modelFile, 50_000_000L),
            Triple("$baseReleaseUrl/tokenizer.json", tokenizerFile, 1_000L),
            Triple("$baseReleaseUrl/default-voice.safetensors", voiceFile, 1_000L)
        )

        for ((index, item) in filesToDownload.withIndex()) {
            val (fileUrl, targetFile, minSize) = item

            // Si le fichier est déjà présent et complet, on passe au suivant
            if (targetFile.exists() && targetFile.length() > minSize) {
                continue
            }

            val ok = downloadSingleFile(fileUrl, targetFile) { fileProgress ->
                // Progression globale basée principalement sur le gros fichier GGUF
                val globalProgress = if (index == 0) (fileProgress * 0.9f).toInt() else 90 + (index * 5)
                onProgress(globalProgress.coerceIn(0, 100))
            }

            if (!ok) return@withContext false
        }

        withContext(Dispatchers.Main) { onProgress(100) }
        initSession()
    }

    private suspend fun downloadSingleFile(
        urlStr: String,
        targetFile: File,
        onProgress: suspend (Int) -> Unit
    ): Boolean {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = urlStr
            var redirectCount = 0

            while (true) {
                val url = URL(currentUrl)
                val conn = url.openConnection() as HttpURLConnection
                connection = conn
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.instanceFollowRedirects = false

                val responseCode = conn.responseCode
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 || responseCode == 308) {
                    val newUrl = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (newUrl != null && redirectCount < 5) {
                        currentUrl = newUrl
                        redirectCount++
                        continue
                    }
                }

                if (responseCode !in 200..299) {
                    lastErrorMessage = "HTTP $responseCode sur ${targetFile.name} (Vérifiez qu'il est bien dans la Release V1.0)"
                    conn.disconnect()
                    return false
                }
                break
            }

            val finalConn = connection ?: return false
            val fileLength = finalConn.contentLength
            val input = finalConn.inputStream
            val tempFile = File(context.filesDir, "${targetFile.name}.tmp")
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
            finalConn.disconnect()

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    lastErrorMessage = "Impossible d'écrire ${targetFile.name}"
                    return false
                }
                return true
            } else {
                lastErrorMessage = "Fichier temporaire introuvable pour ${targetFile.name}"
                return false
            }
        } catch (e: Exception) {
            connection?.disconnect()
            e.printStackTrace()
            lastErrorMessage = e.localizedMessage ?: e.toString()
            return false
        }
    }

    fun initSession(): Boolean {
        if (!isModelReady()) {
            lastErrorMessage = "Fichiers du modèle incomplets"
            return false
        }
        return try {
            if (!isNativeInitialized) {
                isNativeInitialized = nativeInit(
                    modelFile.absolutePath,
                    tokenizerFile.absolutePath,
                    voiceFile.absolutePath
                )
            }
            if (!isNativeInitialized) {
                lastErrorMessage = "Échec d'initialisation C++ du fichier GGUF"
            }
            isNativeInitialized
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMessage = "Erreur JNI : ${e.localizedMessage}"
            false
        }
    }

    suspend fun synthesizeToWav(text: String, outputFile: File): Boolean = withContext(Dispatchers.Default) {
        if (!isNativeInitialized) {
            if (!initSession()) return@withContext false
        }

        try {
            val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
            if (sentences.isEmpty()) {
                lastErrorMessage = "Le texte extrait de l'article est vide"
                return@withContext false
            }

            val pcmAudioChunks = mutableListOf<ShortArray>()

            for (sentence in sentences) {
                val pcmChunk = nativeSynthesize(sentence.trim())
                if (pcmChunk != null && pcmChunk.isNotEmpty()) {
                    pcmAudioChunks.add(pcmChunk)
                }
            }

            if (pcmAudioChunks.isEmpty()) {
                lastErrorMessage = "Aucun échantillon audio généré"
                return@withContext false
            }

            writeWavFile(pcmAudioChunks, outputFile, sampleRate = 24000)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            lastErrorMessage = e.localizedMessage ?: "Erreur inconnue lors de la synthèse"
            false
        }
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

            val buffer = ByteArray(8192)
            var bufIdx = 0
            for (chunk in chunks) {
                for (sample in chunk) {
                    buffer[bufIdx++] = (sample.toInt() and 0xFF).toByte()
                    buffer[bufIdx++] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    if (bufIdx >= buffer.size) {
                        out.write(buffer, 0, bufIdx)
                        bufIdx = 0
                    }
                }
            }
            if (bufIdx > 0) {
                out.write(buffer, 0, bufIdx)
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
