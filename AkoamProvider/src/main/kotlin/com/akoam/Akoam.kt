package com.aksv

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class AkSvProvider : MainAPI() {
    override var mainUrl = "https://ak.sv"
    override var name = "AK.sv"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    companion object {
        private const val MOVIES = "أفلام"
        private const val SERIES = "مسلسلات"
        private const val ANIME = "انمي"
        private const val ASIAN = "آسيوي"
    }

    private fun String.toAbs(): String {
        return when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a") ?: return null
        val href = link.absUrl("href").ifBlank { link.attr("href").toAbs() }
        if (href.isBlank()) return null

        val title = selectFirst(".title, h2, h3")?.text()?.trim()
            ?: link.attr("title").trim()
            ?: link.text().trim()
        if (title.isBlank()) return null

        val poster = selectFirst("img")?.let { img ->
            img.absUrl("src").ifBlank { img.attr("src").toAbs() }
        }.takeIf { it.isNotBlank() }

        val type = when {
            href.contains("/series/") || href.contains("/مسلسل") -> TvType.TvSeries
            href.contains("/movie/") || href.contains("/فيلم") -> TvType.Movie
            href.contains("/anime/") || href.contains("/انمي") -> TvType.Anime
            href.contains("/asian/") || href.contains("/آسيوي") -> TvType.AsianDrama
            else -> TvType.Movie
        }

        return newTvSeriesSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/category/افلام-عربي/" to MOVIES,
        "$mainUrl/category/مسلسلات-عربية/" to SERIES,
        "$mainUrl/category/انمي/" to ANIME,
        "$mainUrl/category/اسيوي/" to ASIAN
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}page/$page/"
        }

        val doc = app.get(url).document

        val items = doc.select("article, .post-item, .movie-item")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(searchUrl).document

        return doc.select("article, .post-item, .movie-item")
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = doc.selectFirst("img.poster, .entry-image img, meta[property=og:image]")?.let {
            when {
                it.hasAttr("content") -> it.attr("content")
                it.hasAttr("src") -> it.absUrl("src")
                else -> null
            }
        }?.takeIf { it.isNotBlank() }?.toAbs()

        val plot = doc.selectFirst(".entry-content p, .description, meta[name=description]")?.let {
            when {
                it.hasAttr("content") -> it.attr("content")
                else -> it.text()
            }
        }?.trim()

        val year = doc.selectFirst(".year, .date")?.text()?.trim()
            ?.let { Regex("\\d{4}").find(it)?.value }?.toIntOrNull()

        val tags = doc.select(".genres a, .categories a")
            .mapNotNull { it.text().trim().takeIf { it.isNotBlank() } }
            .toSet()

        val rating = doc.selectFirst(".rating, .imdb-rate")?.text()?.trim()
            ?.let { Regex("[\\d.]+").find(it)?.value }?.toFloatOrNull()

        val isSeries = url.contains("/series/") || 
                       url.contains("/مسلسل") || 
                       doc.select(".episodes, .seasons").isNotEmpty()

        return if (isSeries) {
            loadTvSeries(url, title, poster, plot, year, tags, rating, doc)
        } else {
            loadMovie(url, title, poster, plot, year, tags, rating, doc)
        }
    }

    private suspend fun loadMovie(
        url: String,
        title: String,
        poster: String?,
        plot: String?,
        year: Int?,
        tags: Set<String>,
        rating: Float?,
        doc: org.jsoup.nodes.Document
    ): LoadResponse {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.toList()
            this.rating = rating
        }
    }

    private suspend fun loadTvSeries(
        url: String,
        title: String,
        poster: String?,
        plot: String?,
        year: Int?,
        tags: Set<String>,
        rating: Float?,
        doc: org.jsoup.nodes.Document
    ): LoadResponse {
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()

        // Try to find seasons and episodes
        doc.select(".season-item, .episode-item, .episode-list a").amap { element ->
            val episodeUrl = element.absUrl("href").ifBlank { element.attr("href").toAbs() }
            if (episodeUrl.isBlank()) return@amap

            val episodeText = element.selectFirst(".episode-title, .episode-number")?.text()
                ?: element.text()

            val season = Regex("الموسم[\\s]*(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("S(\\d+)", RegexOption.IGNORE_CASE)
                    .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val episode = Regex("الحلقة[\\s]*(\\d+)", RegexOption.IGNORE_CASE)
                .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("E(\\d+)", RegexOption.IGNORE_CASE)
                    .find(episodeText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val name = element.selectFirst(".episode-name")?.text()
                ?: episodeText

            newEpisode(episodeUrl) {
                this.name = name
                this.season = season ?: 1
                this.episode = episode
                this.posterUrl = poster
            }
        }

        // If no episodes found, try a different selector
        if (episodes.isEmpty()) {
            doc.select("a[href*=/episode/], a[href*=/الحلقة]").amap { element ->
                val episodeUrl = element.absUrl("href").ifBlank { element.attr("href").toAbs() }
                if (episodeUrl.isBlank()) return@amap

                val text = element.text()
                val episodeNum = Regex("(\\d+)").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(episodeUrl) {
                    this.name = text
                    this.season = 1
                    this.episode = episodeNum
                    this.posterUrl = poster
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags.toList()
            this.rating = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Look for video sources
        val videoSources = mutableListOf<String>()

        // Check for iframe sources
        doc.select("iframe").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src").toAbs() }
            if (src.isNotBlank()) {
                videoSources.add(src)
            }
        }

        // Check for video tags
        doc.select("video source").forEach { source ->
            val src = source.absUrl("src").ifBlank { source.attr("src").toAbs() }
            if (src.isNotBlank()) {
                videoSources.add(src)
            }
        }

        // Check for direct video links
        doc.select("a[href$=.mp4], a[href$=.m3u8], a[href$=.mkv]").forEach { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href").toAbs() }
            if (href.isNotBlank()) {
                videoSources.add(href)
            }
        }

        // Check for embedded scripts
        val html = doc.html()
        Regex("(https?://[^\"']+\\.(?:mp4|m3u8|mkv))").findAll(html).forEach {
            videoSources.add(it.value)
        }

        videoSources.forEach { source ->
            when {
                source.contains(".m3u8") -> {
                    M3u8Helper.generateM3u8(
                        name = name,
                        streamUrl = source,
                        referer = data
                    )?.forEach(callback)
                }
                source.contains(".mp4") || source.contains(".mkv") -> {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Direct",
                            url = source,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                }
                else -> {
                    // Try to extract from external players
                    extractFromExternalPlayer(source, data, subtitleCallback, callback)
                }
            }
        }

        return videoSources.isNotEmpty()
    }

    private suspend fun extractFromExternalPlayer(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val doc = app.get(url, referer = referer).document

            // Look for video sources in the external player
            doc.select("video source, iframe").forEach { element ->
                val src = when {
                    element.hasAttr("src") -> element.absUrl("src")
                    else -> null
                }?.takeIf { it.isNotBlank() }

                if (src != null) {
                    if (src.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            name = name,
                            streamUrl = src,
                            referer = url
                        )?.forEach(callback)
                    } else if (src.contains(".mp4") || src.contains(".mkv")) {
                        callback.invoke(
                            ExtractorLink(
                                source = name,
                                name = "External",
                                url = src,
                                referer = url,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }

            // Check for JavaScript variables
            val html = doc.html()
            val videoPatterns = listOf(
                Regex("file\\s*:\\s*[\"']([^\"']+\\.(?:mp4|m3u8|mkv))[\"']"),
                Regex("src\\s*:\\s*[\"']([^\"']+\\.(?:mp4|m3u8|mkv))[\"']"),
                Regex("url\\s*:\\s*[\"']([^\"']+\\.(?:mp4|m3u8|mkv))[\"']")
            )

            videoPatterns.forEach { pattern ->
                pattern.findAll(html).forEach {
                    val videoUrl = it.groupValues[1]
                    if (videoUrl.isNotBlank()) {
                        val fullUrl = if (videoUrl.startsWith("http")) {
                            videoUrl
                        } else {
                            url.substringBeforeLast("/") + "/" + videoUrl
                        }

                        if (fullUrl.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                name = name,
                                streamUrl = fullUrl,
                                referer = url
                            )?.forEach(callback)
                        } else {
                            callback.invoke(
                                ExtractorLink(
                                    source = name,
                                    name = "JS Video",
                                    url = fullUrl,
                                    referer = url,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = false
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but continue
            e.printStackTrace()
        }
    }
}
