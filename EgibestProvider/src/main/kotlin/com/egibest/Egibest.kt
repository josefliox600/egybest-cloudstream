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

    override var mainUrl = "https://i-egybest.com"
    override var name = "EgyBest Josef"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href") ?: return null
        val title = this.text().trim()
        return newMovieSearchResponse(title, href, TvType.Movie)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/movies/?page=" to "الأفلام",
        "$mainUrl/series/?page=" to "المسلسلات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val doc = app.get(request.data + page).document

        val home = doc.select("a.postBlock")
            .mapNotNull {
                it.toSearchResult()
            }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("a.postBlock")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text() ?: "Unknown"

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.plot = doc.selectFirst("p")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        val iframe = doc.selectFirst("iframe")?.attr("src") ?: return false

        loadExtractor(iframe, data, subtitleCallback, callback)

        return true
    }
}
