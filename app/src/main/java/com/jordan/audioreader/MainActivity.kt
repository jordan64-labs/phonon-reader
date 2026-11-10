package com.jordan.audioreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var phononEngine: PhononEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exoPlayer = ExoPlayer.Builder(this).build()
        phononEngine = PhononEngine(applicationContext)

        // Traiter une éventuelle URL partagée depuis Chrome
        val sharedUrl = if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: ""
        } else ""

        setContent {
            MaterialTheme {
                AppScreen(
                    initialUrl = sharedUrl,
                    phononEngine = phononEngine,
                    onConvertAndPlay = { url, updateStatus ->
                        processArticle(url, updateStatus)
                    },
                    onStop = {
                        exoPlayer.stop()
                    }
                )
            }
        }
    }

    private fun processArticle(url: String, updateStatus: (String, Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            updateStatus("1/3 Téléchargement de l'article...", true)
            val article = WolParser.extractArticle(url)
            if (article == null) {
                updateStatus("❌ Erreur : Impossible d'extraire l'article.", false)
                return@launch
            }

            updateStatus("2/3 Synthèse vocale Phonon : ${article.title}...", true)
            val audioFile = File(cacheDir, "current_article.wav")
            val success = phononEngine.synthesizeToWav(article.text, audioFile)

            if (success && audioFile.exists()) {
                updateStatus("3/3 Lecture...", true)
                exoPlayer.setMediaItem(MediaItem.fromUri(audioFile.toURI().toString()))
                exoPlayer.prepare()
                exoPlayer.play()
                updateStatus("▶️ En lecture : ${article.title}", false)
            } else {
                updateStatus("❌ Erreur lors de la synthèse : ${phononEngine.lastErrorMessage}", false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer.release()
    }
}

@Composable
fun AppScreen(
    initialUrl: String,
    phononEngine: PhononEngine,
    onConvertAndPlay: (String, (String, Boolean) -> Unit) -> Unit,
    onStop: () -> Unit
) {
    var isModelReady by remember { mutableStateOf(phononEngine.isModelReady()) }
    var downloadProgress by remember { mutableStateOf(0) }
    var isDownloading by remember { mutableStateOf(false) }

    var url by remember { mutableStateOf(initialUrl) }
    var statusText by remember { mutableStateOf(if (isModelReady) "Prêt" else "Modèle vocal requis au premier lancement.") }
    var isLoading by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Phonon WOL Reader",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Synthèse vocale locale pour wol.jw.org",
                fontSize = 13.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Écran de téléchargement initial si le modèle est absent
            if (!isModelReady) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "Initialisation du modèle Phonon",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Pour fonctionner hors-ligne, l'application doit télécharger une seule fois les poids du modèle (~120 Mo).",
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        if (isDownloading) {
                            LinearProgressIndicator(
                                progress = { downloadProgress / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Téléchargement : $downloadProgress %",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Button(
                                onClick = {
                                    isDownloading = true
                                    coroutineScope.launch {
                                        val ok = phononEngine.downloadModel { progress ->
                                            downloadProgress = progress
                                        }
                                        isDownloading = false
                                        if (ok) {
                                            isModelReady = true
                                            statusText = "Modèle prêt ! Collez une URL pour commencer."
                                        } else {
                                            statusText = "❌ Échec : ${phononEngine.lastErrorMessage}"
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Télécharger le modèle vocal")
                            }
                        }
                    }
                }
            } else {
                // Écran principal une fois le modèle téléchargé
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Lien de l'article") },
                    placeholder = { Text("https://wol.jw.org/fr/...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        isLoading = true
                        onConvertAndPlay(url) { status, loading ->
                            statusText = status
                            isLoading = loading
                        }
                    },
                    enabled = url.isNotBlank() && !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(if (isLoading) "Génération..." else "Écouter l'article")
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        onStop()
                        statusText = "Lecture arrêtée."
                        isLoading = false
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Arrêter la lecture")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = statusText,
                    modifier = Modifier.padding(14.dp),
                    fontSize = 13.sp
                )
            }
        }
    }
}
