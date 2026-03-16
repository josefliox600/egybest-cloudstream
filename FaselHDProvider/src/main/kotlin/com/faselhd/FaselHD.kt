package com.faselhd

import com.lagradost.cloudstream3.*

class FaselHD : MainAPI() {

    override var mainUrl = "https://example.com"
    override var name = "FaselHD Josef"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie
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