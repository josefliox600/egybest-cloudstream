package com.cimaclub

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CimaClubPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaClub())
    }
}