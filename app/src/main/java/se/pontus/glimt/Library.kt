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
)

data class Series(
    val name: String,
    val group: String,
    val logo: String?,
    val episodes: List<Entry>,
    /** Satt för Xtream-serier; avsnitten hämtas då när serien öppnas. */
    val xtreamId: String? = null,
)

class Library(
    val movieGroups: List<String>,
    val movies: Map<String, List<Entry>>,
    val seriesGroups: List<String>,
    val series: Map<String, List<Series>>,
) {
    val allMovies: List<Entry> by lazy { movies.values.flatten() }
    val allSeries: List<Series> by lazy { series.values.flatten() }
}

/**
 * Läser en M3U-spellista rad för rad och behåller bara filmer och serier.
 * Live-kanaler hoppas över helt, vilket håller minnet nere även för enorma listor.
 */
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
