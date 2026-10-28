package com.keyiflerolsun.extractors

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class VidMolyExtractor : ExtractorApi() {
    override val name = "VidMoly"
    override val mainUrl = "https://vidmoly.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer).text

        val dilLabel = when {
            referer?.contains("/dublaj/") == true -> "Türkçe Dublaj"
            else -> "Türkçe Altyazılı"
        }
        val displayName = "$name - $dilLabel"

        Log.d(name, "Dil >> $displayName")

        val hlsRegexes = listOf(
            Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
            Regex("""file\s*:\s*['"](https?://[^'"]+urlset/master[^'"]*)['"]""")
        )

        for (regex in hlsRegexes) {
            val match = regex.find(response)
            if (match != null) {
                val hlsUrl = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = hlsUrl,
                    ) {
                        this.referer = referer ?: url
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.M3U8
                    }
                )
                return
            }
        }
    }
}