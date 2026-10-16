package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DizimagProvider : MainAPI() {
    override var mainUrl = "https://ddizimag.com"
    override var name = "Dizimag"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "tr"
    override val hasMainPage = true

    // -------- Yardımcı --------
    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href")
        if (href.isBlank()) return null
        // Bölüm linklerini atla
        if (href.contains("/sezon-") || href.contains("/bolum-")) return null
        if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/anime/")) return null
        // Liste sayfalarını atla
        if (Regex(".*/(diziler|filmler|animes)$").matches(href)) return null

        val img = selectFirst("img") ?: return null
        val name = selectFirst("h3")?.text()?.trim().orEmpty().ifBlank {
            img.attr("alt")
                .removeSuffix(" Dizi izle")
                .removeSuffix(" Film izle")
                .removeSuffix(" Anime izle")
                .trim()
        }
        if (name.isBlank()) return null

        val poster = img.attr("src").ifBlank { img.attr("data-src") }
        val type = when {
            href.contains("/film/") -> TvType.Movie
            href.contains("/anime/") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newSearchResponse(name, fixUrl(href), type) {
            this.posterUrl = fixUrl(poster)
        }
    }

    // -------- 1. Ana Sayfa --------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val document = Jsoup.parse(app.get(mainUrl).text)
        val homePageList = mutableListOf<HomePageList>()

        document.select("h2.section-title").forEach { titleEl ->
            val title = titleEl.text().trim()
            if (title.contains("Bölüm", ignoreCase = true)) return@forEach

            val section = titleEl.closest("section") ?: return@forEach

            val seen = mutableSetOf<String>()
            val items = section.select("a[href]").mapNotNull { a ->
                val href = a.attr("href")
                if (!seen.add(href)) return@mapNotNull null
                a.toSearchResponse()
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }
        return newHomePageResponse(homePageList)
    }

    // -------- 2. Arama --------
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/ara?q=$encoded"
        val document = Jsoup.parse(app.get(searchUrl).text)

        val seen = mutableSetOf<String>()
        return document.select("a[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (!seen.add(href)) return@mapNotNull null
            a.toSearchResponse()
        }
    }

    // -------- 3. Detay --------
    override suspend fun load(url: String): LoadResponse {
        val document = Jsoup.parse(app.get(url).text)

        // Başlık (— 1. Sezon... kısmını kes)
        val title = document.selectFirst("h1.font-display")?.text()?.trim().orEmpty()
            .substringBefore(" —").substringBefore(" -").trim()

        // Poster
        val poster = document.selectFirst("div[class*=shadow-poster] img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

        // Açıklama
        val plot = document.selectFirst("p.max-w-2xl.leading-relaxed")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content").orEmpty() }

        // Yıl
        val year = Regex("""\((\d{4})\)""").find(
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        )?.groupValues?.get(1)?.toIntOrNull()

        // Türler
        val tags = document.select("a.rounded-badge[href*=/tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        // FİLM
        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        // DİZİ / ANİME
        val episodes = mutableListOf<Episode>()
        val seenEp = mutableSetOf<String>()

        // Varsayılan sayfadaki bölümler
        document.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
            addEpisode(a, episodes, seenEp)
        }

        // Diğer sezonları da çek
        val baseUrl = url.substringBefore("?").substringBefore("#")
        val seasons = document.select("a[href*='?sezon=']").mapNotNull {
            Regex("""sezon=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()

        for (season in seasons) {
            try {
                val sDoc = Jsoup.parse(app.get("$baseUrl?sezon=$season").text)
                sDoc.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
                    addEpisode(a, episodes, seenEp)
                }
            } catch (_: Exception) { /* geç */ }
        }

        val type = if (url.contains("/anime/")) TvType.Anime else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = fixUrl(poster)
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun addEpisode(a: Element, episodes: MutableList<Episode>, seen: MutableSet<String>) {
        val href = a.attr("href")
        if (href.isBlank() || !seen.add(href)) return

        val epName = a.selectFirst("p.font-semibold")?.text()?.trim().orEmpty()
            .ifBlank { a.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
            .ifBlank { "Bölüm" }

        val season = Regex("""/sezon-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""/bolum-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()

        episodes.add(Episode(fixUrl(href), epName, season, episode))
    }

 // -------- 4. Video Kaynakları --------
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = Jsoup.parse(app.get(data).text)
    var found = false

    // Klasik iframe'ler (nadiren de olsa olabilir)
    document.select("iframe[src]").forEach { iframe ->
        val src = iframe.attr("src")
        if (src.isBlank()) return@forEach
        val fixed = if (src.startsWith("//")) "https:$src" else fixUrl(src)
        loadExtractor(fixed, data, subtitleCallback, callback)
        found = true
    }

    // Pilavyer core.js'inin yüklendiği domaini bul (BASE)
    var pilavBase = "https://play2.pilavyerplay.top"
    document.select("script[src*='pilavyerplay'], script[src*='/core.js']").firstOrNull()?.let { s ->
        val src = s.attr("src")
        // https://play2.pilavyerplay.top/assets/js/core.js → https://play2.pilavyerplay.top
        val m = Regex("""^(https?://[^/]+)""").find(src)
        if (m != null) pilavBase = m.groupValues[1]
    }

    // data-pv slug'larını topla
    val slugs = document.select("div[data-pv]").mapNotNull {
        it.attr("data-pv").takeIf { pv -> pv.isNotBlank() }
    }

    for (slug in slugs) {
        // c.js'in yaptığı tam URL:
        val sUrl = "$pilavBase/assets/js/s.php?s=" + java.net.URLEncoder.encode(slug, "UTF-8")

        try {
            // Referer ZORUNLU (origin kilidi)
            val playerHtml = app.get(
                sUrl,
                referer = data,
                headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                )
            ).text

            val pdoc = Jsoup.parse(playerHtml, sUrl)

            // 1) s.php içindeki iframe'ler
            pdoc.select("iframe[src]").forEach { ifr ->
                val s = ifr.attr("src")
                if (s.isNotBlank()) {
                    val fixed = if (s.startsWith("//")) "https:$s" else fixUrl(s, sUrl)
                    loadExtractor(fixed, sUrl, subtitleCallback, callback)
                    found = true
                }
            }

            // 2) <video><source> veya doğrudan m3u8/mp4
            val directPatterns = listOf(
                Regex("""["'](https?://[^"'\\\s]+\.m3u8[^"'\\\s]*)["']"""),
                Regex("""["'](https?://[^"'\\\s]+\.mp4[^"'\\\s]*)["']"""),
                Regex("""(?:file|source|src)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""),
            )
            var directFound = false
            directPatterns.forEach { p ->
                p.findAll(playerHtml).forEach { m ->
                    val u = m.groupValues[1]
                    if (u.isNotBlank()) {
                        callback.invoke(
                            newExtractorLink("Pilavyer", "Pilavyer", u) {
                                this.referer = sUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                        directFound = true
                    }
                }
            }

            // 3) Hiçbiri olmadıysa s.php URL'sini extractor'a bırak
            if (!directFound && pdoc.select("iframe[src]").isEmpty()) {
                loadExtractor(sUrl, data, subtitleCallback, callback)
                found = true
            }
        } catch (_: Exception) {
            // Sunucu yanıt vermezse yine de extractor dene
            try {
                loadExtractor(sUrl, data, subtitleCallback, callback)
                found = true
            } catch (_: Exception) { }
        }
    }

    return found
}
}