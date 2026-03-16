package com.anime3rb

import com.lagradost.cloudstream3.*

class Anime3rb : MainAPI() {

    override var mainUrl = "https://example.com"
    override var name = "Anime3rb Josef"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        mainUrl to "Home"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return newHomePageResponse(request.name, listOf())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return listOf()
    }
}