package com.shahiidanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ShahiidAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShahiidAnime())
    }
}