package com.keyiflerolsun

import android.content.Context
import com.keyiflerolsun.extractors.OdnoklassnikiExtractor
import com.keyiflerolsun.extractors.SibnetExtractor
import com.keyiflerolsun.extractors.VidMolyExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SezonlukDiziPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SezonlukDizi())
        registerExtractorAPI(VidMolyExtractor())
        registerExtractorAPI(SibnetExtractor())
        registerExtractorAPI(OdnoklassnikiExtractor())
    }
}