package se.pontus.glimt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

enum class Kind { MOVIE, EPISODE }

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
)

class Library(
    val movieGroups: List<String>,
    val movies: Map<String, List<Entry>>,
    val seriesGroups: List<String>,
    val series: Map<String, List<Series>>,
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

/** "SE | Filmer" -> "SE", "|AR| Drama" -> "AR". Kategorier utan språkprefix hamnar i "Övrigt". */
fun categoryPrefix(name: String): String =
    categoryPrefixRe.find(name)?.groupValues?.get(1)?.uppercase() ?: "Övrigt"

private val prefixNames = mapOf(
    "SE" to "Svenska", "SV" to "Svenska", "SWE" to "Svenska", "NO" to "Norska", "NOR" to "Norska",
    "DK" to "Danska", "DA" to "Danska", "FI" to "Finska", "FIN" to "Finska", "IS" to "Isländska",
    "EN" to "Engelska", "ENG" to "Engelska", "UK" to "Engelska (UK)", "US" to "Engelska (US)",
    "AR" to "Arabiska", "TR" to "Turkiska", "DE" to "Tyska", "GER" to "Tyska", "FR" to "Franska",
    "ES" to "Spanska", "IT" to "Italienska", "NL" to "Holländska", "PL" to "Polska", "GR" to "Grekiska",
    "PT" to "Portugisiska", "BR" to "Portugisiska (BR)", "RU" to "Ryska", "IN" to "Indiska", "HI" to "Hindi",
    "KR" to "Koreanska", "JP" to "Japanska", "CN" to "Kinesiska", "AL" to "Albanska", "KU" to "Kurdiska",
    "FA" to "Persiska", "IR" to "Persiska", "SO" to "Somaliska", "EXYU" to "Ex-Jugoslavien", "YU" to "Ex-Jugoslavien",
    "RO" to "Rumänska", "HU" to "Ungerska", "CZ" to "Tjeckiska", "BG" to "Bulgariska", "UA" to "Ukrainska",
    "PK" to "Pakistan", "AF" to "Afghanistan", "LAT" to "Latinamerika", "MULTI" to "Flerspråkigt",
)

fun prefixLabel(prefix: String): String = prefixNames[prefix]?.let { "$it ($prefix)" } ?: prefix

/** Biblioteket utan dolda kategorier. Nycklar: "p:SE" = hela språkgruppen, "c:<namn>" = en kategori. */
fun Library.filtered(hidden: Set<String>): Library {
    if (hidden.isEmpty()) return this
    fun visible(g: String) = "c:$g" !in hidden && "p:${categoryPrefix(g)}" !in hidden
    val mg = movieGroups.filter { visible(it) }
    val sg = seriesGroups.filter { visible(it) }
    return Library(mg, mg.associateWith { movies[it].orEmpty() }, sg, sg.associateWith { series[it].orEmpty() })
}

object M3u {
    private val attrRe = Regex("""([\w-]+)="([^"]*)"""")
    private val episodeRe = Regex("""(?i)^(.*?)[\s._\-]*S(\d{1,2})[\s._\-]*E(\d{1,4})(?!\d)""")
    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm")

    fun parse(reader: BufferedReader): Library {
        val movies = LinkedHashMap<String, MutableList<Entry>>()
        val episodes = LinkedHashMap<String, LinkedHashMap<String, MutableList<Entry>>>()
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
                    if (inf != null) add(inf, extGroup, line, movies, episodes)
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
        return Library(movies.keys.toList(), movies, series.keys.toList(), series)
    }

    private fun add(
        inf: String,
        extGroup: String?,
        url: String,
        movies: MutableMap<String, MutableList<Entry>>,
        episodes: MutableMap<String, LinkedHashMap<String, MutableList<Entry>>>,
    ) {
        val path = url.substringBefore('?').lowercase()
        val ext = path.substringAfterLast('.', "")
        val seriesUrl = "/series/" in path
        val movieUrl = "/movie/" in path
        if (!seriesUrl && !movieUrl && ext !in vodExtensions) return // live-tv o.d.

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

    suspend fun load(context: Context, url: String, forceDownload: Boolean): Unit = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, "playlist.m3u")
        try {
            val acc = Xtream.accountFrom(url)
            account = acc
            val library = if (acc != null) {
                Xtream.load(context.filesDir, acc, forceDownload) { _state.value = LoadState.Loading(it) }
            } else {
                if (forceDownload || !file.exists() || file.length() == 0L) download(url, file)
                _state.value = LoadState.Loading("Läser spellistan…")
                file.bufferedReader(Charsets.UTF_8).use { M3u.parse(it) }
            }
            _state.value = LoadState.Ready(library)
        } catch (e: Exception) {
            _state.value = LoadState.Failed(e.message ?: e.toString())
        }
    }

    private fun download(url: String, target: File) {
        _state.value = LoadState.Loading("Laddar ner spellistan…")
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
                            _state.value = LoadState.Loading("Laddar ner spellistan… ${total / 1_000_000} MB")
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
