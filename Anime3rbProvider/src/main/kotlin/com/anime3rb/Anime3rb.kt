package com.anime3rb

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb Josef"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun String.toAbs(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun Element.getPoster(): String? {
        val img = selectFirst("img") ?: return null
        val src = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull { it.isNotBlank() } ?: return null
        return src.toAbs()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }

        val title = attr("title").trim()
            .ifBlank { selectFirst("h2, h3, .title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }
            .ifBlank { return null }

        val poster = getPoster()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/titles/list?page=" to "قائمة الأنمي",
        "$mainUrl/titles/list/tv?page=" to "مسلسلات الأنمي",
        "$mainUrl/titles/list/movie?page=" to "أفلام الأنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page, referer = mainUrl).document

        val items = doc.select("a[href*=/titles/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/titles/list?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        val results = mutableListOf<SearchResponse>()

        urls.forEach { url ->
            try {
                val doc = app.get(url, referer = mainUrl).document
                results += doc.select("a[href*=/titles/]")
                    .mapNotNull { it.toSearchResult() }
            } catch (_: Exception) {
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img")?.let { img ->
                listOf(
                    img.attr("data-src"),
                    img.attr("data-lazy-src"),
                    img.attr("src")
                ).firstOrNull { it.isNotBlank() }?.toAbs()
            }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.select("p").firstOrNull { it.text().length > 80 }?.text()?.trim()

        val episodeLinks = doc.select("a[href]")
            .mapNotNull { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href").toAbs() }
                if (href.isBlank()) return@mapNotNull null
                if (!href.contains("/titles/")) return@mapNotNull null
                if (!a.text().contains("الحلقة") && !a.text().contains("episode", true)) return@mapNotNull null
                href to a.text().trim()
            }
            .distinctBy { it.first }

        if (episodeLinks.isEmpty()) {
            return newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                posterUrl = poster
                this.plot = plot
            }
        }

        val episodes = episodeLinks.map { (href, text) ->
            val epNum = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) {
                name = text.ifBlank { "حلقة" }
                episode = epNum
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.episodes = episodes
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
