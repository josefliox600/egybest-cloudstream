package com.akoam

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AkoamPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Akoam())
    }
}