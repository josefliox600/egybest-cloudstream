package com.egibest

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.requestCreator
import org.jsoup.nodes.Element

class Egibest : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://i-egybest.com"
    override var name = "EgyBest Josef"
    override val usesWebView = true
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    private fun String.toAbs(): String =
        when {
            startsWith("http") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> "$mainUrl/$this"
        }

    private fun isMovie(href: String, rawTitle: String): Boolean {
        return rawTitle.contains("فيلم") ||
            rawTitle.contains("film", ignoreCase = true) ||
            href.contains("مشاهدة-فيلم") ||
            href.contains("%d9%81%d9%8a%d9%84%d9%85")
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("^مشاهدة (فيلم|مسلسل|انمي|أنمي|كرتون|برنامج)\\s+"), "")
        .replace(Regex("\\s+(مترجم|مدبلج|حصرى|حصريا|اون لاين|اونلاين|كامل|على أكثر من سيرفر|كاملة).*$"), "")
        .trim()

    private fun Element.pickImage(): String? {
        val img = selectFirst("img") ?: return null
        return listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("src")
        ).firstOrNull { it.isNotBlank() }?.toAbs()
    }

    private fun Element.toCard(): SearchResponse? {
        val href = absUrl("href").ifBlank { attr("href").toAbs() }.ifBlank { return null }
        val rawTitle = (
            selectFirst("h3.title, h3, span.title, .movieTitle, .epTitle")?.text()?.trim()
                ?: selectFirst("img")?.attr("alt")?.trim()
                ?: text().trim()
        ).ifBlank { return null }

        val title = cleanTitle(rawTitle).ifBlank { rawTitle }
        val poster = pickImage()

        return if (isMovie(href, rawTitle)) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trends/?page=" to "التريند",
        "$mainUrl/last/?page=" to "المضاف حديثاً",
        "$mainUrl/movies/?page=" to "أحدث الأفلام",
        "$mainUrl/series/?page=" to "أحدث المسلسلات",
        "$mainUrl/episodes/?page=" to "أحدث الحلقات",
        "$mainUrl/category/anime/?page=" to "انمي مترجم"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val doc = app.get(url, referer = mainUrl).document

        val items = doc.select("a.postBlockCol, a.postBlock, .postGrid a, .movie a, .block-posts a")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.trim().replace(" ", "+")}", referer = mainUrl).document
        return doc.select("a.postBlockCol, a.postBlock, .postGrid a, .movie a, .block-posts a")
            .mapNotNull { it.toCard() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, referer = mainUrl).document

        val rawTitle = doc.selectFirst("h1, .postTitle, .entry-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val title = cleanTitle(rawTitle).ifBlank { rawTitle }

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("img.postCoverImg, .postCover img, article img, .poster img")
                ?.let {
                    listOf(
                        it.attr("data-src"),
                        it.attr("data-lazy-src"),
                        it.attr("src")
                    ).firstOrNull { s -> s.isNotBlank() }?.toAbs()
                }

        val plot = doc.selectFirst(".postDesc, .entry-content p, .storyLine, .summary")?.text()?.trim()
        val year = Regex("(19|20)\\d{2}").find(rawTitle)?.value?.toIntOrNull()
        val tags = doc.select("a[href*=/category/], a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
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

        doc.select("a.postBlock, div.postBlock a, .episodes a, .epAll a, a[href*='الحلقه'], a[href*='episode']")
            .forEach { ep ->
                val epHref = ep.absUrl("href").ifBlank { ep.attr("href").toAbs() }.ifBlank { return@forEach }
                if (epHref == url) return@forEach

                val epText = ep.text().trim().ifBlank { ep.attr("title").trim() }
                val epNum = Regex("(\\d+)").find(epText)?.value?.toIntOrNull()
                val epPoster = ep.selectFirst("img")?.let { img ->
                    listOf(
                        img.attr("data-src"),
                        img.attr("data-lazy-src"),
                        img.attr("src")
                    ).firstOrNull { it.isNotBlank() }?.toAbs()
                }

                episodes.add(
                    newEpisode(epHref) {
                        name = epText.ifBlank { if (epNum != null) "الحلقة $epNum" else "حلقة" }
                        episode = epNum
                        posterUrl = epPoster
                    }
                )
            }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) { name = title })
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.distinctBy { it.data }.sortedBy { it.episode }
        ) {
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
        val embedUrls = mutableSetOf<String>()

        val loadIframeRegex = Regex("""loadIframe\s*\(\s*this\s*,\s*['"]([^'"]+)['"]""")
        val windowOpenRegex = Regex("""window\.open\s*\(\s*['"]([^'"]+)['"]""")

        doc.select("[onclick]").forEach { el ->
            val onclick = el.attr("onclick")
            loadIframeRegex.find(onclick)?.groupValues?.getOrNull(1)?.let { embedUrls.add(it.toAbs()) }
            windowOpenRegex.find(onclick)?.groupValues?.getOrNull(1)?.let { embedUrls.add(it.toAbs()) }
        }

        doc.select("iframe[src]").forEach { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src").toAbs() }
            if (src.isNotBlank() && !src.contains("disqus") && !src.contains("facebook")) {
                embedUrls.add(src)
            }
        }

        doc.select("script").forEach { script ->
            val html = script.html()

            loadIframeRegex.findAll(html).forEach {
                embedUrls.add(it.groupValues[1].toAbs())
            }

            windowOpenRegex.findAll(html).forEach {
                embedUrls.add(it.groupValues[1].toAbs())
            }

            Regex("""["'](https?://[^"']+\.(m3u8|mp4)[^"']*)["']""")
                .findAll(html)
                .forEach { embedUrls.add(it.groupValues[1]) }
        }

        for (embedUrl in embedUrls.distinct()) {
            if (embedUrl.isBlank()) continue

            try {
                when {
                    embedUrl.contains(".m3u8") -> {
                        M3u8Helper.generateM3u8(name, embedUrl, referer = data).forEach {
                            callback(it)
                            found = true
                        }
                    }

                    else -> {
                        if (loadExtractor(embedUrl, referer = data, subtitleCallback, callback)) {
                            found = true
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        if (!found) {
            try {
                val resolved = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mp4).*""")
                ).resolveUsingWebView(
                    requestCreator(
                        "GET",
                        data,
                        referer = mainUrl
                    )
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
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl
                            ) {
                                referer = data
                            }
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
