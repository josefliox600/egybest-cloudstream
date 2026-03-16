package com.anime3rb

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb Josef"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime)

    private fun String.toAbs(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }
        if (href.isBlank()) return null

        val title = attr("title").trim()
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }

        if (title.isBlank()) return null

        val poster = selectFirst("img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.toAbs()

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/titles/list?page=" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document

        val items = doc.select("a[href*=/titles/]")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("a[href*=/titles/]")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.toAbs()

        val plot = doc.selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()

        val episodes = doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href").toAbs() }
                val text = a.text().trim()

                if (href.isBlank()) return@mapNotNull null
                if (!text.contains("الحلقة") && !text.contains("episode", ignoreCase = true)) return@mapNotNull null

                newEpisode(href) {
                    name = text
                    episode = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
            .distinctBy { it.data }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
