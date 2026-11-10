package com.jordan.audioreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class ExtractedArticle(val title: String, val text: String)

object WolParser {
    suspend fun extractArticle(url: String): ExtractedArticle? = withContext(Dispatchers.IO) {
        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .timeout(12000)
                .get()

            val root = doc.selectFirst("article")
                ?: doc.selectFirst("div.bodyTxt")
                ?: doc.selectFirst("div#content")
                ?: doc.body()

            // Suppression systématique des renvois, notes, légendes et médias
            root.select("sup, aside, figure, nav, footer, .caption, .footnote, .pubData, div.imageLayout").remove()

            val titleEl = doc.selectFirst("h1")
            val title = titleEl?.text()?.trim() ?: "Article"

            val paragraphs = mutableListOf<String>()
            paragraphs.add("$title.")

            for (el in root.select("h2, h3, p")) {
                val txt = el.text().trim()
                if (txt.isNotEmpty() && !txt.startsWith("[Photo") && !txt.startsWith("[Illustration") && !txt.startsWith("[Cadre")) {
                    paragraphs.add(txt)
                }
            }

            ExtractedArticle(
                title = title,
                text = paragraphs.joinToString("\n\n")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
