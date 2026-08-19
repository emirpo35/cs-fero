// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class HDPlayerSystem : ExtractorApi() {
    override val name            = "HDPlayerSystem"
    override val mainUrl         = "https://hdplayersystem.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = if (referer.isNullOrEmpty()) "$mainUrl/" else referer
        
        // Hash / ID değerini ayıkla
        val vidId = when {
            url.contains("video/") -> url.substringAfter("video/").substringBefore("?").substringBefore("/")
            url.contains("?data=") -> url.substringAfter("?data=").substringBefore("&")
            else -> url.substringAfterLast("/")
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest"
        )

        var m3uLink: String? = null

        // --- YÖNTEM 1: API (POST İsteği) Üzerinden Link Alma ---
        try {
            val postUrl = "$mainUrl/player/index.php?data=$vidId&do=getVideo"
            Log.d("Kekik_${this.name}", "postUrl » $postUrl")

            val response = app.post(
                postUrl,
                data = mapOf(
                    "hash" to vidId,
                    "r"    to extRef
                ),
                referer = url,
                headers = headers
            )

            val videoResponse = response.parsedSafe<SystemResponse>()
            m3uLink = videoResponse?.securedLink?.takeIf { it.isNotBlank() }
                ?: videoResponse?.hls?.takeIf { it.isNotBlank() }
                ?: videoResponse?.videoSource?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "POST isteği başarısız, HTML taramasına geçiliyor: ${e.message}")
        }

        // --- YÖNTEM 2: API Başarısız Olursa Sayfa İçi HTML/JS Taraması (Yedek) ---
        if (m3uLink.isNullOrEmpty()) {
            try {
                val pageText = app.get(url, referer = extRef, headers = headers).text
                val unpacked = if (pageText.contains("eval(function(p,a,c,k,e,d)")) {
                    JsUnpacker.unpack(pageText) ?: pageText
                } else {
                    pageText
                }

                m3uLink = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""["'](https?://[^\s"']+\.m3u8[^\s"']*)["']""").find(unpacked)?.groupValues?.get(1)
                    ?: Regex("""file\s*:\s*["']([^"']+\.mp4[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            } catch (e: Exception) {
                Log.e("Kekik_${this.name}", "HTML taraması da başarısız oldu: ${e.message}")
            }
        }

        val finalLink = fixUrlNull(m3uLink) ?: throw ErrorLoadingException("M3U8 / Video linki bulunamadı!")

        // --- LİNKİ ÇÖZÜMLE VE PLAYER'A GÖNDER ---
        if (finalLink.contains(".m3u8")) {
            // M3u8Helper otomatik olarak 1080p, 720p, 480p kalitelerini ayrıştırır
            M3u8Helper.generateM3u8(
                source = this.name,
                streamUrl = finalLink,
                referer = url,
                headers = mapOf("Referer" to url)
            ).forEach(callback)
        } else {
            // Doğrudan MP4 ise
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = finalLink,
                    referer = url,
                    quality = Qualities.Unknown.value
                )
            )
        }
    }

    // Değişkenler Nullable (? = null) yapıldı ki JSON eksik gelse bile çökmesin.
    data class SystemResponse(
        @JsonProperty("hls")         val hls: String? = null,
        @JsonProperty("videoImage")  val videoImage: String? = null,
        @JsonProperty("videoSource") val videoSource: String? = null,
        @JsonProperty("securedLink") val securedLink: String? = null
    )
}

