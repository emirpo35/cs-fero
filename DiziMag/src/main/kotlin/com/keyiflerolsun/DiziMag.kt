package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziMag : MainAPI() {
    override var mainUrl = "https://ddizimag.com"
    override var name = "Dizimag"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    override var lang = "tr"
    override val hasMainPage = true

    // -------- Yardımcı --------
   private fun Element.toSearchResponse(): SearchResponse? {
    val href = attr("href")
    if (href.isBlank()) return null
    if (href.contains("/sezon-") || href.contains("/bolum-")) return null
    if (!href.contains("/dizi/") && !href.contains("/film/") && !href.contains("/anime/")) return null
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
    val fixedUrl = fixUrl(href)
    val fixedPoster = fixUrl(poster)

    return when {
        href.contains("/film/") -> newMovieSearchResponse(name, fixedUrl, TvType.Movie) {
            this.posterUrl = fixedPoster
        }
        href.contains("/anime/") -> newAnimeSearchResponse(name, fixedUrl, TvType.Anime) {
            this.posterUrl = fixedPoster
        }
        else -> newTvSeriesSearchResponse(name, fixedUrl, TvType.TvSeries) {
            this.posterUrl = fixedPoster
        }
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

        val title = document.selectFirst("h1.font-display")?.text()?.trim().orEmpty()
            .substringBefore(" —").substringBefore(" -").trim()

        val poster = document.selectFirst("div[class*=shadow-poster] img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()

        val plot = document.selectFirst("p.max-w-2xl.leading-relaxed")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("meta[name=description]")?.attr("content").orEmpty() }

        val year = Regex("""\((\d{4})\)""").find(
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        )?.groupValues?.get(1)?.toIntOrNull()

        val tags = document.select("a.rounded-badge[href*=/tur/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = mutableListOf<Episode>()
        val seenEp = mutableSetOf<String>()

        document.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
            addEpisode(a, episodes, seenEp)
        }

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
            } catch (_: Exception) { }
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

        // ✅ newEpisode(url, initializer: Episode.() -> Unit)
        episodes.add(newEpisode(fixUrl(href)) {
            this.name = epName
            this.season = season
            this.episode = episode
        })
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

        // Klasik iframe'ler
        document.select("iframe[src]").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isBlank()) return@forEach
            val fixed = if (src.startsWith("//")) "https:$src" else fixUrl(src)
            loadExtractor(fixed, data, subtitleCallback, callback)
            found = true
        }

        // Pilavyer base URL
        var pilavBase = "https://play2.pilavyerplay.top"
        document.select("script[src*='pilavyerplay']").firstOrNull()?.let { s ->
            Regex("""^(https?://[^/]+)""").find(s.attr("src"))?.let {
                pilavBase = it.groupValues[1]
            }
        }

        // data-pv slug'ları
        val slugs: List<String> = document.select("div[data-pv]").mapNotNull { el ->
            val pv = el.attr("data-pv")
            if (pv.isNotBlank()) pv else null
        }

        for (slug in slugs) {
            val sUrl = "$pilavBase/assets/js/s.php?s=" + URLEncoder.encode(slug, "UTF-8")
            try {
                val playerHtml = app.get(sUrl, referer = data).text

                // Altyazılar
                Regex(
                    """\{[^{}]*?"lang"\s*:\s*"([^"]+)"[^{}]*?"src"\s*:\s*"([^"]+sub\.php[^"]*)"[^{}]*?\}""",
                    RegexOption.DOT_MATCHES_ALL
                ).findAll(playerHtml).forEach { m ->
                    val lang = m.groupValues[1]
                    val subUrl = m.groupValues[2]
                        .replace("\\/", "/")
                        .replace("\\u0026", "&")
                    subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                }

                // Stream URL'i
                val streamUrl = Regex(""""stream"\s*:\s*"([^"]+)"""")
                    .find(playerHtml)?.groupValues?.get(1)
                    ?.replace("\\/", "/")
                    ?.replace("\\u0026", "&")

                if (!streamUrl.isNullOrBlank()) {
                    loadExtractor(streamUrl, data, subtitleCallback, callback)

                    callback.invoke(
                        newExtractorLink(
                            "Pilavyer",
                            "Pilavyer HLS",
                            streamUrl,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = data
                        }
                    )
                    found = true
                }
            } catch (_: Exception) {
                // sessizce geç
            }
        }

        return found
    }
}