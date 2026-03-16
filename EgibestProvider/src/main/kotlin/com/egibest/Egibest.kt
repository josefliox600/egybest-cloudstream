package com.egibest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class Egibest : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://i-egybest.com"
    override var name = "EgyBest Josef Strong"
    override val hasMainPage = true
    override val usesWebView = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun String.toAbs(): String = when {
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$mainUrl$this"
        else -> "$mainUrl/$this"
    }

    private fun normalizeImage(url: String?): String? {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return null
        return raw.toAbs().replace(" ", "%20")
    }

    private fun isMovie(href: String, rawTitle: String): Boolean {
        val title = rawTitle.lowercase()
        val link = href.lowercase()
        return title.contains("فيلم") || title.contains("film") ||
            link.contains("/movies/") || link.contains("movie") ||
            link.contains("مشاهدة-فيلم") || link.contains("%d9%81%d9%8a%d9%84%d9%85")
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("^مشاهدة\\s+"), "")
        .replace(Regex("^(فيلم|مسلسل|انمي|أنمي|برنامج|كرتون)\\s+"), "")
        .replace(Regex("\\s+(مترجم|مدبلج|اون لاين|أون لاين|اونلاين|كامل|كاملة|على أكثر من سيرفر).*$"), "")
        .trim()

    private fun Element.bestTitle(): String? {
        return listOf(
            selectFirst("h1")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".title")?.text(),
            selectFirst(".movieTitle")?.text(),
            selectFirst(".epTitle")?.text(),
            attr("title"),
            selectFirst("img")?.attr("alt"),
            text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun Element.bestImage(): String? {
        val img = selectFirst("img") ?: return null
        return normalizeImage(
            listOf(
                img.attr("data-src"),
                img.attr("data-lazy-src"),
                img.attr("data-original"),
                img.attr("data-image"),
                img.attr("src")
            ).firstOrNull { it.isNotBlank() }
        )
    }

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        val rawTitle = bestTitle() ?: return null
        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val poster = bestImage()

        return if (isMovie(href, rawTitle)) {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/latest-movies/page/" to "أفلام جديدة",
        "$mainUrl/series/page/" to "مسلسلات جديدة",
        "$mainUrl/episodes/page/" to "حلقات جديدة",
        "$mainUrl/category/anime/" to "انمي مترجم"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.endsWith("/") && !request.data.contains("page/") && page > 1 -> "${request.data}page/$page/"
            request.data.contains("page/") -> request.data + page + "/"
            else -> request.data
        }
        val doc = app.get(url, referer = mainUrl).document
        val selectors = listOf(
            "a.postBlockCol", "a.postBlock", ".block-posts a", ".movie a", ".Grid--MycimaPosts a",
            ".MovieBlock a", ".one_newstitle a", "article a"
        )
        val items = selectors.flatMap { sel -> doc.select(sel) }
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
        return doc.select("a.postBlockCol, a.postBlock, .block-posts a, .movie a, .Grid--MycimaPosts a, .MovieBlock a, article a")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document
        val rawTitle = listOf(
            doc.selectFirst("meta[property=og:title]")?.attr("content"),
            doc.selectFirst("h1")?.text(),
            doc.selectFirst(".postTitle")?.text(),
            doc.selectFirst(".entry-title")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null

        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val poster = normalizeImage(
            listOf(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.selectFirst("img.postCoverImg")?.attr("src"),
                doc.selectFirst(".postCover img")?.attr("src"),
                doc.selectFirst("article img")?.attr("src")
            ).firstOrNull { !it.isNullOrBlank() }
        )
        val plot = listOf(
            doc.selectFirst("meta[property=og:description]")?.attr("content"),
            doc.selectFirst(".postDesc")?.text(),
            doc.selectFirst(".storyLine")?.text(),
            doc.selectFirst(".entry-content p")?.text()
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select("a[href*=genre], a[href*=category]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val trailer = doc.selectFirst("a[href*=youtube.com/watch], a[href*=youtu.be/]")?.attr("href")

        if (isMovie(url, rawTitle)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                addTrailer(trailer)
            }
        }

        val episodes = mutableListOf<Episode>()
        doc.select("a.postBlock, div.postBlock a, .episodes a, .epAll a, article a")
            .forEach { ep ->
                val epHref = ep.absUrl("href").ifBlank { ep.attr("href").toAbs() }.ifBlank { return@forEach }
                if (epHref == url) return@forEach
                val epText = ep.bestTitle()?.trim().orEmpty()
                if (epText.isBlank()) return@forEach
                val epNum = Regex("(\\d+)").find(epText)?.value?.toIntOrNull()
                val epPoster = ep.bestImage()
                episodes.add(newEpisode(epHref) {
                    name = epText
                    episode = epNum
                    posterUrl = epPoster
                })
            }

        if (episodes.isEmpty()) episodes.add(newEpisode(url) { name = title })

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }.sortedBy { it.episode }) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = mainUrl).document
        var found = false
        val embedUrls = linkedSetOf<String>()

        val loadIframeRegex = Regex("""loadIframe\\s*\\(\\s*this\\s*,\\s*['\"]([^'\"]+)['\"]""")
        val windowOpenRegex = Regex("""window\\.open\\s*\\(\\s*['\"]([^'\"]+)['\"]""")

        doc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            loadIframeRegex.find(onclick)?.groupValues?.getOrNull(1)?.let { embedUrls.add(it.toAbs()) }
            windowOpenRegex.find(onclick)?.groupValues?.getOrNull(1)?.let { embedUrls.add(it.toAbs()) }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src").toAbs() }
            if (src.isNotBlank() && !src.contains("disqus") && !src.contains("facebook")) embedUrls.add(src)
        }

        doc.select("script").forEach { script ->
            val html = script.html()
            loadIframeRegex.findAll(html).forEach { embedUrls.add(it.groupValues[1].toAbs()) }
            windowOpenRegex.findAll(html).forEach { embedUrls.add(it.groupValues[1].toAbs()) }
            Regex("""["'](https?://[^"']+\\.(m3u8|mp4)[^"']*)["']""").findAll(html).forEach { embedUrls.add(it.groupValues[1]) }
        }

        for (embedUrl in embedUrls) {
            try {
                when {
                    embedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach {
                            callback(it)
                            found = true
                        }
                    }
                    else -> {
                        if (loadExtractor(embedUrl, referer = data, subtitleCallback, callback)) found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\\.(m3u8|mp4).*""")
                ).resolveUsingWebView(
                    requestCreator("GET", data, referer = mainUrl)
                ).first

                val videoUrl = resolved?.url?.toString()
                if (!videoUrl.isNullOrBlank()) {
                    if (videoUrl.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(name, videoUrl, referer = data).forEach {
                            callback(it)
                            found = true
                        }
                    } else {
                        callback(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                referer = data,
                                quality = Qualities.Unknown.value,
                                isM3u8 = false
                            )
                        )
                        found = true
                    }
                }
            } catch (_: Exception) {
            }
        }

        return found
    }
}
