package com.anime3rb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb Josef"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    private fun String.toAbs(): String =
        when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }

    private fun Element.getPoster(): String? {
        val img = selectFirst("img") ?: return null
        return listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull { it.isNotBlank() }?.toAbs()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }

        val title = attr("title").trim()
            .ifBlank { selectFirst("h2, h3, .title")?.text()?.trim().orEmpty() }
            .ifBlank { selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { text().trim() }
            .ifBlank { return null }

        val poster = getPoster()

        val type = if (href.contains("/movie") || href.contains("/titles/") && text().contains("فيلم")) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return if (type == TvType.AnimeMovie) {
            newAnimeSearchResponse(title, href, TvType.AnimeMovie) {
                posterUrl = poster
            }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) {
                posterUrl = poster
            }
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
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val candidates = listOf(
            "$mainUrl/titles/list?q=$encoded",
            "$mainUrl/?s=$encoded"
        )

        val results = mutableListOf<SearchResponse>()

        for (url in candidates) {
            try {
                val doc = app.get(url, referer = mainUrl).document
                results += doc.select("a[href*=/titles/]")
                    .mapNotNull { it.toSearchResponse() }
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
            ?: doc.selectFirst("img")?.let {
                listOf(
                    it.attr("data-src"),
                    it.attr("data-lazy-src"),
                    it.attr("src")
                ).firstOrNull { s -> s.isNotBlank() }?.toAbs()
            }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: doc.select("p").firstOrNull { it.text().length > 80 }?.text()?.trim()

        val year = Regex("(19|20)\\d{2}").find(doc.text())?.value?.toIntOrNull()

        val tags = doc.select("a[href*=/genre/], a[href*=/tag/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val trailer = doc.selectFirst("a[href*=youtube.com/watch], a[href*=youtu.be/]")?.attr("href")

        val epLinks = doc.select("a[href]")
            .filter {
                val href = it.absUrl("href").ifBlank { it.attr("href").toAbs() }
                href.contains(url) && it.text().contains("الحلقة")
            }
            .distinctBy { it.absUrl("href").ifBlank { it.attr("href").toAbs() } }

        if (epLinks.isEmpty()) {
            return newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
                posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                addTrailer(trailer)
            }
        }

        val episodes = epLinks.map { ep ->
            val epHref = ep.absUrl("href").ifBlank { ep.attr("href").toAbs() }
            val epTitle = ep.text().trim().ifBlank { "حلقة" }
            val epNum = Regex("(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(epHref) {
                name = epTitle
                episode = epNum
            }
        }.distinctBy { it.data }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addTrailer(trailer)
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
