// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class HDPlayerSystem : ExtractorApi() {

    override val name = "HDPlayerSystem"
    override val mainUrl = "https://hdplayersystem.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val extRef = referer ?: ""

        val vidId = if (url.contains("video/")) {
            url.substringAfter("video/")
        } else {
            url.substringAfter("?data=")
        }

        val postUrl =
            "${mainUrl}/player/index.php?data=${vidId}&do=getVideo"

        Log.d("Kekik_${this.name}", "URL » $url")
        Log.d("Kekik_${this.name}", "REFERER » $extRef")
        Log.d("Kekik_${this.name}", "VIDEO ID » $vidId")
        Log.d("Kekik_${this.name}", "POST URL » $postUrl")

        val response = app.post(
            postUrl,
            data = mapOf(
                "hash" to vidId,
                "r" to extRef
            ),
            referer = extRef,
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        )

        val responseText = response.text

        Log.d(
            "Kekik_${this.name}",
            "RESPONSE » $responseText"
        )

        val videoResponse = response.parsedSafe<SystemResponse>()
            ?: throw ErrorLoadingException(
                "JSON parse failed: $responseText"
            )

        Log.d(
            "Kekik_${this.name}",
            "HLS » ${videoResponse.hls}"
        )

        Log.d(
            "Kekik_${this.name}",
            "VIDEO SOURCE » ${videoResponse.videoSource}"
        )

        Log.d(
            "Kekik_${this.name}",
            "SECURED LINK » ${videoResponse.securedLink}"
        )

        val m3uLink = videoResponse.securedLink
            ?: videoResponse.hls
            ?: videoResponse.videoSource
            ?: throw ErrorLoadingException(
                "Video URL bulunamadı"
            )

        Log.d(
            "Kekik_${this.name}",
            "VIDEO URL » $m3uLink"
        )

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = m3uLink,
                type = ExtractorLinkType.M3U8
            ) {

                quality = Qualities.Unknown.value

                headers = mapOf(
                    "Referer" to extRef,
                    "Origin" to mainUrl
                )
            }
        )
    }

    data class SystemResponse(

        @JsonProperty("hls")
        val hls: String? = null,

        @JsonProperty("videoImage")
        val videoImage: String? = null,

        @JsonProperty("videoSource")
        val videoSource: String? = null,

        @JsonProperty("securedLink")
        val securedLink: String? = null
    )
}