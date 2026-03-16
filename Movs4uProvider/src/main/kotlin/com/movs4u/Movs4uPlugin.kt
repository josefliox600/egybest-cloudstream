package com.movs4u

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Movs4uPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movs4u())
    }
}