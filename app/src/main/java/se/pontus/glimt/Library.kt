package se.pontus.glimt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

enum class Kind { MOVIE, EPISODE, LIVE }

data class Entry(
    val title: String,
    val group: String,
    val logo: String?,
    val url: String,
    val kind: Kind,
    val series: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    /** Xtream-id: stream_id för filmer, seriens series_id för avsnitt. */
    val xtreamId: String? = null,
    /** När titeln lades till hos leverantören (unix-sekunder), 0 om okänt. */
    val added: Long = 0,
    /** TMDB-id om leverantören uppger det (används för genrer). */
    val tmdb: String? = null,
    /** Betyg 0–10 från leverantören, 0 om okänt. */
    val rating: Float = 0f,
    /** Utgivningsår, 0 om okänt. */
    val year: Int = 0,
)

data class Series(
    val name: String,
    val group: String,
    val logo: String?,
    val episodes: List<Entry>,
    /** Satt för Xtream-serier; avsnitten hämtas då när serien öppnas. */
    val xtreamId: String? = null,
    /** Senast uppdaterad hos leverantören (unix-sekunder), 0 om okänt. */
    val added: Long = 0,
    /** TMDB-id om leverantören uppger det (används för genrer). */
    val tmdb: String? = null,
    /** Betyg 0–10 från leverantören, 0 om okänt. */
    val rating: Float = 0f,
    /** Utgivningsår, 0 om okänt. */
    val year: Int = 0,
)

class Library(
    val movieGroups: List<String>,
    val movies: Map<String, List<Entry>>,
    val seriesGroups: List<String>,
    val series: Map<String, List<Series>>,
    /** Kategorier med vuxeninnehåll (enligt namn eller leverantörens is_adult-flagga). */
    val adultGroups: Set<String> = emptySet(),
    /** Live-TV: kanaler (Entry med kind = LIVE) per kategori. */
    val liveGroups: List<String> = emptyList(),
    val live: Map<String, List<Entry>> = emptyMap(),
) {
    val allMovies: List<Entry> by lazy { movies.values.flatten() }
    val allSeries: List<Series> by lazy { series.values.flatten() }

    /** Senast tillagda filmer först. */
    val newestMovies: List<Entry> by lazy {
        if (allMovies.any { it.added > 0 }) allMovies.sortedByDescending { it.added }.take(60)
        else allMovies.takeLast(60).reversed()
    }

    /** Senast tillagda/uppdaterade serier först. */
    val newestSeries: List<Series> by lazy {
        if (allSeries.any { it.added > 0 }) allSeries.sortedByDescending { it.added }.take(60)
        else allSeries.takeLast(60).reversed()
    }
}

/**
 * Läser en M3U-spellista rad för rad och behåller bara filmer och serier.
 * Live-kanaler hoppas över helt, vilket håller minnet nere även för enorma listor.
 */
// ---------------------------------------------------------------- Dölja kategorier

private val categoryPrefixRe by lazy { Regex("""^\s*[\|\[\(]?\s*([A-Za-z]{2,4})\s*[\|\]\)\-:]""") }

/** Språkord i kategorinamn utan kod, t.ex. "ARABIC MOVIES" eller "BOLLYWOOD". Första träffen vinner. */
private val languageWords: List<Pair<String, Regex>> by lazy {
    listOf(
        "EXYU" to "ex-?yu|balkan|serbia|croat|bosn|hrvat|srpsk",
        "NORDIC" to "nordic|nordisk|scandinav|skandinav",
        "SE" to "swed|svensk|sverige",
        "NO" to "norw|norsk|norge",
        "DK" to "danish|dansk|danmark|denmark",
        "FI" to "finnish|suomi|finland",
        "AR" to "arab|egypt|gulf|khaleej|ramadan|islam|maghreb|morocc|tunis|alger|leban|syria|iraq|عرب",
        "TR" to "turk|türk",
        "KU" to "kurd",
        "IR" to "persian|iran|farsi",
        "AF" to "afghan|pashto",
        "PK" to "pakistan|urdu",
        "IN" to "india|hindi|bollywood|tamil|telugu|malayalam|punjabi|bengali|marathi|kannada|desi",
        "AL" to "alban|shqip",
        "GR" to "greek|greece|ελλην",
        "RU" to "russia|русск",
        "UA" to "ukrain",
        "PL" to "polish|polsk|polska",
        "RO" to "romania|român",
        "BG" to "bulgar",
        "HU" to "hungar|magyar",
        "CZ" to "czech|cesk",
        "DE" to "german|deutsch",
        "NL" to "dutch|nederland|vlaams",
        "FR" to "french|français|francais",
        "IT" to "italian|italia",
        "ES" to "spanish|españ|espan|castellano",
        "LAT" to "latino|latin america",
        "BR" to "brazil|brasil",
        "PT" to "portug",
        "CN" to "china|chinese|mandarin|cantonese",
        "JP" to "japan",
        "KR" to "korea",
        "PH" to "filipino|philippin|pinoy|tagalog",
        "ASIA" to "asia",
        "AFRICA" to "africa|nollywood",
        "SO" to "somal",
        "IL" to "israel|hebrew",
        "EN" to "english|\\busa\\b|\\buk\\b|british|america",
    ).map { (code, words) -> code to Regex("(?i)($words)") }
}

private val prefixCache = HashMap<String, String>()

/**
 * Språkgrupp för en kategori: "SE | Filmer" -> "SE", "|AR| Drama" -> "AR",
 * "ARABIC MOVIES" -> "AR", "EX-YU | Filmovi" -> "EXYU". Okänt hamnar i "Övrigt".
 */
fun categoryPrefix(name: String): String = synchronized(prefixCache) {
    prefixCache.getOrPut(name) {
        val code = categoryPrefixRe.find(name)?.groupValues?.get(1)?.uppercase()
        when {
            code == "EX" || code == "EXYU" -> "EXYU"
            code != null && code != "TOP" && code != "NEW" && code != "VOD" && code != "ALL" -> code
            else -> languageWords.firstOrNull { it.second.containsMatchIn(name) }?.first ?: "Övrigt"
        }
    }
}

/** Språkkod i början av en titel: "AR - Film" -> "AR", "|TR| Film" -> "TR". Snabb, utan regex. */
fun titlePrefix(title: String): String? {
    var i = 0
    while (i < title.length && (title[i] == ' ' || title[i] == '|' || title[i] == '[' || title[i] == '(')) i++
    val start = i
    while (i < title.length && title[i].isLetter() && title[i].code < 128) i++
    val len = i - start
    if (len !in 2..4) return null
    while (i < title.length && title[i] == ' ') i++
    if (i >= title.length || title[i] !in "-|:])") return null
    return title.substring(start, start + len).uppercase()
}

private val prefixNames = mapOf(
    "SE" to "Svenska", "SV" to "Svenska", "SWE" to "Svenska", "NO" to "Norska", "NOR" to "Norska",
    "DK" to "Danska", "DA" to "Danska", "FI" to "Finska", "FIN" to "Finska", "IS" to "Isländska",
    "NORDIC" to "Nordiskt", "EN" to "Engelska", "ENG" to "Engelska", "UK" to "Engelska (UK)", "US" to "Engelska (US)",
    "AR" to "Arabiska", "TR" to "Turkiska", "DE" to "Tyska", "GER" to "Tyska", "FR" to "Franska",
    "ES" to "Spanska", "IT" to "Italienska", "NL" to "Holländska", "PL" to "Polska", "GR" to "Grekiska",
    "PT" to "Portugisiska", "BR" to "Portugisiska (BR)", "RU" to "Ryska", "IN" to "Indiska", "HI" to "Hindi",
    "KR" to "Koreanska", "JP" to "Japanska", "CN" to "Kinesiska", "AL" to "Albanska", "KU" to "Kurdiska",
    "FA" to "Persiska", "IR" to "Persiska", "SO" to "Somaliska", "EXYU" to "Ex-Jugoslavien", "YU" to "Ex-Jugoslavien",
    "RO" to "Rumänska", "HU" to "Ungerska", "CZ" to "Tjeckiska", "BG" to "Bulgariska", "UA" to "Ukrainska",
    "PK" to "Pakistan", "AF" to "Afghanistan", "LAT" to "Latinamerika", "LA" to "Latinamerika", "MULTI" to "Flerspråkigt",
    "PH" to "Filippinska", "QC" to "Québec", "BN" to "Bengali", "MT" to "Maltesiska", "BE" to "Belgien",
    "IL" to "Hebreiska", "ASIA" to "Asiatiskt", "AFRICA" to "Afrikanskt",
)

fun prefixLabel(prefix: String): String = prefixNames[prefix]?.let { "$it ($prefix)" } ?: prefix

/** Biblioteket utan dolda kategorier. Nycklar: "p:SE" = hela språkgruppen, "c:<namn>" = en kategori. */
private val adultRe by lazy { Regex("""(?i)(xxx|adult|porn|erotic|erotik|vuxen|18\+|\+18)""") }

/** Ser kategorinamnet ut som vuxeninnehåll? */
fun looksAdult(categoryName: String): Boolean = adultRe.containsMatchIn(categoryName)

fun Library.filtered(hidden: Set<String>, showAdult: Boolean = false): Library {
    if (hidden.isEmpty() && showAdult) return this
    fun visible(g: String) = "c:$g" !in hidden && "p:${categoryPrefix(g)}" !in hidden &&
        (showAdult || (g !in adultGroups && !looksAdult(g)))
    // Titlar med dold språkkod i namnet ("AR - Film") tas bort även i blandade kategorier
    val hiddenCodes = hidden.filter { it.startsWith("p:") }.map { it.substring(2) }.toSet()
    fun keepTitle(t: String) = hiddenCodes.isEmpty() || titlePrefix(t)?.let { it !in hiddenCodes } ?: true
    val mg = movieGroups.filter { visible(it) }
    val sg = seriesGroups.filter { visible(it) }
    val mm = LinkedHashMap<String, List<Entry>>()
    for (g in mg) movies[g].orEmpty().filter { keepTitle(it.title) }.takeIf { it.isNotEmpty() }?.let { mm[g] = it }
    val sm = LinkedHashMap<String, List<Series>>()
    for (g in sg) series[g].orEmpty().filter { keepTitle(it.name) }.takeIf { it.isNotEmpty() }?.let { sm[g] = it }
    val lm = LinkedHashMap<String, List<Entry>>()
    for (g in liveGroups.filter { visible(it) }) live[g].orEmpty().filter { keepTitle(it.title) }.takeIf { it.isNotEmpty() }?.let { lm[g] = it }
    return Library(mm.keys.toList(), mm, sm.keys.toList(), sm, adultGroups, lm.keys.toList(), lm)
}

object M3u {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val episodeRe = Regex("""(?i)^(.*?)[\s._\-]*S(\d{1,2})[\s._\-]*E(\d{1,4})(?!\d)""")
    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm")

    fun parse(reader: BufferedReader): Library {
        val movies = LinkedHashMap<String, MutableList<Entry>>()
        val episodes = LinkedHashMap<String, LinkedHashMap<String, MutableList<Entry>>>()
        val live = LinkedHashMap<String, MutableList<Entry>>()
        var info: String? = null
        var extGroup: String? = null

        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    extGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    val inf = info
                    info = null
                    if (inf != null) add(inf, extGroup, line, movies, episodes, live)
                }
            }
        }

        val series = LinkedHashMap<String, List<Series>>()
        for ((group, byName) in episodes) {
            series[group] = byName.map { (name, eps) ->
                Series(
                    name = name,
                    group = group,
                    logo = eps.firstNotNullOfOrNull { it.logo },
                    episodes = eps.sortedWith(compareBy({ it.season }, { it.episode })),
                )
            }
        }
        return Library(movies.keys.toList(), movies, series.keys.toList(), series, liveGroups = live.keys.toList(), live = live)
    }

    private fun add(
        inf: String,
        extGroup: String?,
        url: String,
        movies: MutableMap<String, MutableList<Entry>>,
        episodes: MutableMap<String, LinkedHashMap<String, MutableList<Entry>>>,
        live: MutableMap<String, MutableList<Entry>>,
    ) {
        val path = url.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        val seriesUrl = "/series/" in path
        val movieUrl = "/movie/" in path
        val isLive = !seriesUrl && !movieUrl && ext !in vodExtensions

        val lastQuote = inf.lastIndexOf('"')
        val comma = inf.indexOf(',', if (lastQuote >= 0) lastQuote else 0)
        val head = if (comma >= 0) inf.substring(0, comma) else inf
        val attrs = HashMap<String, String>()
        attrRe.findAll(head).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }

        val title = (if (comma >= 0) inf.substring(comma + 1).trim() else "")
            .ifBlank { attrs["tvg-name"].orEmpty() }
            .ifBlank { "Okänd titel" }
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup ?: "Övrigt"
        val logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http") }
        if (isLive) {
            live.getOrPut(group) { ArrayList() }.add(Entry(title, group, logo, url, Kind.LIVE))
            return
        }

        val match = episodeRe.find(title)
        if (seriesUrl || (match != null && !movieUrl)) {
            val name = match?.groupValues?.get(1)?.trim()?.trimEnd('-', ':', '|', ' ')?.ifBlank { null } ?: title
            val season = match?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val episode = match?.groupValues?.get(3)?.toIntOrNull() ?: 0
            episodes.getOrPut(group) { LinkedHashMap() }
                .getOrPut(name) { ArrayList() }
                .add(Entry(title, group, logo, url, Kind.EPISODE, name, season, episode))
        } else {
            movies.getOrPut(group) { ArrayList() }.add(Entry(title, group, logo, url, Kind.MOVIE))
        }
    }
}

sealed interface LoadState {
    data object Idle : LoadState
    data class Loading(val message: String) : LoadState
    data class Ready(val library: Library) : LoadState
    data class Failed(val message: String) : LoadState
}

object Repo {
    private val _state = MutableStateFlow<LoadState>(LoadState.Idle)
    val state: StateFlow<LoadState> = _state

    /** Satt när spellistan är ett Xtream-konto (get.php?username=…). */
    @Volatile
    var account: XtreamAccount? = null
        private set

    /** Hur gammal listan får bli innan den hämtas om i bakgrunden. */
    private const val REFRESH_AFTER_MS = 6 * 60 * 60 * 1000L

    /**
     * Läser in biblioteket. Finns en sparad, färdigtolkad kopia visas den direkt
     * och listan uppdateras sedan i bakgrunden om den är äldre än sex timmar.
     */
    suspend fun load(context: Context, url: String, forceDownload: Boolean): Unit = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, "library.bin")
        val acc = Xtream.accountFrom(url)
        account = acc
        if (!forceDownload) {
            val cached = runCatching { LibraryCache.read(cacheFile, url) }.getOrNull()
            if (cached != null) {
                _state.value = LoadState.Ready(cached)
                if (System.currentTimeMillis() - cacheFile.lastModified() > REFRESH_AFTER_MS) {
                    // Tyst uppdatering: behåll det gamla om något går fel
                    runCatching { fetch(context, url, acc, force = true, quiet = true) }
                        .onSuccess { lib ->
                            _state.value = LoadState.Ready(lib)
                            runCatching { LibraryCache.write(lib, cacheFile, url) }
                        }
                }
                return@withContext
            }
        }
        try {
            val library = fetch(context, url, acc, forceDownload, quiet = false)
            _state.value = LoadState.Ready(library)
            runCatching { LibraryCache.write(library, cacheFile, url) }
        } catch (e: Exception) {
            _state.value = LoadState.Failed(e.message ?: e.toString())
        }
    }

    private fun fetch(context: Context, url: String, acc: XtreamAccount?, force: Boolean, quiet: Boolean): Library {
        val status: (String) -> Unit = { if (!quiet) _state.value = LoadState.Loading(it) }
        return if (acc != null) {
            Xtream.load(context.filesDir, acc, force, status)
        } else {
            val file = File(context.filesDir, "playlist.m3u")
            if (force || !file.exists() || file.length() == 0L) download(url, file, quiet)
            status("Läser spellistan…")
            file.bufferedReader(Charsets.UTF_8).use { M3u.parse(it) }
        }
    }

    private fun download(url: String, target: File, quiet: Boolean = false) {
        if (!quiet) _state.value = LoadState.Loading("Laddar ner spellistan…")
        val tmp = File(target.parentFile, target.name + ".tmp")
        val conn = Net.open(url, readTimeoutMs = 120_000)
        try {
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total - reported > 2_000_000) {
                            reported = total
                            if (!quiet) _state.value = LoadState.Loading("Laddar ner spellistan… ${total / 1_000_000} MB")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}


/** Utgivningsår ur en titel som "Film (2019)" eller "Film 2019", utan regex (snabbt för stora listor). */
fun yearOf(title: String): Int {
    var i = title.length - 4
    while (i >= 0) {
        val c0 = title[i]
        if ((c0 == '1' || c0 == '2') && i + 3 < title.length &&
            title[i + 1].isDigit() && title[i + 2].isDigit() && title[i + 3].isDigit() &&
            (i == 0 || !title[i - 1].isDigit()) && (i + 4 >= title.length || !title[i + 4].isDigit())
        ) {
            val y = title.substring(i, i + 4).toInt()
            if (y in 1900..2100) return y
        }
        i--
    }
    return 0
}

/** Ser titeln ut att vara 4K/UHD? */
fun is4k(title: String, group: String): Boolean {
    fun has(s: String) = s.contains("4K", ignoreCase = true) || s.contains("UHD", ignoreCase = true) || s.contains("2160")
    return has(title) || has(group)
}

/** Har titeln uppenbart svenskt tal eller svensk text (SE-kategori, "SVENSK" i namnet)? */
fun isSwedish(title: String, group: String): Boolean {
    val p = categoryPrefix(group)
    return p == "SE" || p == "SV" || p == "SWE" || titlePrefix(title).let { it == "SE" || it == "SV" } ||
        title.contains("svensk", ignoreCase = true)
}

/** Sorteringar i Filmer och Serier. */
enum class Sort(val label: String) { NEWEST("Nyast"), AZ("A–Ö"), YEAR("År"), RATING("Betyg") }

/** Titel utan språkprefix ("EN - War" -> "war"), för A–Ö. */
private fun sortKey(title: String): String {
    val p = titlePrefix(title) ?: return title.trim().lowercase()
    val i = title.indexOfFirst { it == '-' || it == '|' || it == ':' || it == ']' || it == ')' }
    return (if (i >= 0 && i < p.length + 6) title.substring(i + 1) else title).trim().lowercase()
}

fun Library.sortedFor(sort: Sort): Library {
    if (sort == Sort.NEWEST && movies.values.none { l -> l.any { it.added > 0 } }) return this
    val me: Comparator<Entry> = when (sort) {
        Sort.NEWEST -> compareByDescending { it.added }
        Sort.AZ -> compareBy { sortKey(it.title) }
        Sort.YEAR -> compareByDescending<Entry> { it.year }.thenByDescending { it.added }
        Sort.RATING -> compareByDescending<Entry> { it.rating }.thenByDescending { it.year }
    }
    val se: Comparator<Series> = when (sort) {
        Sort.NEWEST -> compareByDescending { it.added }
        Sort.AZ -> compareBy { sortKey(it.name) }
        Sort.YEAR -> compareByDescending<Series> { it.year }.thenByDescending { it.added }
        Sort.RATING -> compareByDescending<Series> { it.rating }.thenByDescending { it.year }
    }
    return Library(
        movieGroups, movies.mapValues { it.value.sortedWith(me) },
        seriesGroups, series.mapValues { it.value.sortedWith(se) },
        adultGroups, liveGroups, live,
    )
}
