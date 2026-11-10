package com.jordan.audioreader

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var phononEngine: PhononEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phononEngine = PhononEngine(applicationContext)

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
                        phononEngine.stop()
                    },
                    onOpenTtsSettings = {
                        startActivity(Intent("com.android.settings.TTS_SETTINGS"))
                    }
                )
            }
        }
    }

    private fun processArticle(url: String, updateStatus: (String, Boolean) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            updateStatus("1/2 Extraction de l'article...", true)
            val article = WolParser.extractArticle(url)
            if (article == null) {
                updateStatus("❌ Impossible d'extraire l'article WOL.", false)
                return@launch
            }

            updateStatus("2/2 Lecture : ${article.title}", true)
            val started = phononEngine.speakText(article.text) { status ->
                updateStatus(status, false)
            }

            if (!started) {
                updateStatus("❌ ${phononEngine.lastErrorMessage}", false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        phononEngine.shutdown()
    }
}

@Composable
fun AppScreen(
    initialUrl: String,
    phononEngine: PhononEngine,
    onConvertAndPlay: (String, (String, Boolean) -> Unit) -> Unit,
    onStop: () -> Unit,
    onOpenTtsSettings: () -> Unit
) {
    var url by remember { mutableStateOf(initialUrl) }
    var statusText by remember { mutableStateOf("Prêt. Collez une URL pour écouter.") }
    var isLoading by remember { mutableStateOf(false) }

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
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Lecteur vocal pour articles wol.jw.org",
                fontSize = 13.sp,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(28.dp))

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
                Text(if (isLoading) "Chargement..." else "Écouter l'article")
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

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = onOpenTtsSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("⚙️ Paramètres du moteur vocal (TTS)", fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(20.dp))

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
