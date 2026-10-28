package com.keyiflerolsun

import android.content.Context
import com.keyiflerolsun.extractors.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WebteizlePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Webteizle())
        registerExtractorAPI(VidMolyExtractor())
    }
}