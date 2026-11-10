package com.jordan.audioreader

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class PhononEngine(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isInitialized: Boolean = false
        private set

    var lastErrorMessage: String = ""
        private set

    private var onProgressCallback: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.FRENCH)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                lastErrorMessage = "La langue française n'est pas installée sur le moteur TTS."
                isInitialized = false
            } else {
                isInitialized = true
                setupProgressListener()
            }
        } else {
            lastErrorMessage = "Échec de l'initialisation du moteur TTS système ($status)."
            isInitialized = false
        }
    }

    private fun setupProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onProgressCallback?.invoke("Lecture en cours...")
            }

            override fun onDone(utteranceId: String?) {
                onProgressCallback?.invoke("Lecture terminée.")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onProgressCallback?.invoke("Erreur lors de la lecture vocale.")
            }
        })
    }

    fun isModelReady(): Boolean = isInitialized

    suspend fun speakText(text: String, onStatusUpdate: (String) -> Unit): Boolean = withContext(Dispatchers.Main) {
        if (!isInitialized || tts == null) {
            lastErrorMessage = "Le moteur vocal n'est pas encore prêt."
            return@withContext false
        }

        onProgressCallback = onStatusUpdate
        tts?.stop()

        // Découpage par phrases pour une élocution fluide
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            lastErrorMessage = "Aucun texte à prononcer."
            return@withContext false
        }

        for ((index, sentence) in sentences.withIndex()) {
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(sentence, queueMode, null, "sentence_$index")
        }

        true
    }

    fun stop() {
        tts?.stop()
        onProgressCallback?.invoke("Lecture arrêtée.")
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }
}
