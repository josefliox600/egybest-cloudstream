package com.anime3rb

import com.lagradost.cloudstream3.*

class Anime3rb : MainAPI() {

    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb Josef"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?page=" to "Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document

        val home = doc.select("a.poster")
            .map {
                newAnimeSearchResponse(
                    it.attr("title"),
                    it.attr("href")
                ) {
                    posterUrl = it.select("img").attr("src")
                }
            }

        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("a.poster")
            .map {
                newAnimeSearchResponse(
                    it.attr("title"),
                    it.attr("href")
                ) {
                    posterUrl = it.select("img").attr("src")
                }
            }
    }
}
