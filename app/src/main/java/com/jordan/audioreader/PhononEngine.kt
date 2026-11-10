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

    // Lien direct vers votre Release GitHub publique
    private val modelUrl = "https://github.com/jordan64-labs/phonon-reader/releases/download/v1.0/model.q8.gguf"
    val modelFile: File get() = File(context.filesDir, "model.q8.gguf")

    var lastErrorMessage: String = ""

    fun isModelReady(): Boolean {
        return modelFile.exists() && modelFile.length() > 50_000_000 // Fichier complet (~74 Mo)
    }

    suspend fun downloadModel(onProgress: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            var currentUrl = modelUrl
            var redirectCount = 0

            // Suivre les redirections de GitHub vers le stockage AWS sans en-tête superflu
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
                    lastErrorMessage = "Erreur serveur : HTTP $responseCode"
                    conn.disconnect()
                    return@withContext false
                }
                break
            }

            val finalConn = connection ?: return@withContext false
            val fileLength = finalConn.contentLength
            val input = finalConn.inputStream
            val tempFile = File(context.filesDir, "model.tmp")
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
                if (modelFile.exists()) {
                    modelFile.delete()
                }
                if (tempFile.renameTo(modelFile)) {
                    true
                } else {
                    lastErrorMessage = "Erreur d'écriture sur le stockage local"
                    false
                }
            } else {
                lastErrorMessage = "Fichier temporaire introuvable"
                false
            }
        } catch (e: Exception) {
            connection?.disconnect()
            e.printStackTrace()
            lastErrorMessage = e.localizedMessage ?: e.toString()
            false
        }
    }

    suspend fun synthesizeToWav(text: String, outputFile: File): Boolean = withContext(Dispatchers.Default) {
        // En attente d'exécution vocale locale
        true
    }
}
