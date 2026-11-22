package se.pontus.glimt

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Hur långt man har kommit i en film eller ett avsnitt. */
data class Progress(val entry: Entry, val position: Long, val duration: Long, val updated: Long) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val minutesLeft: Long get() = if (duration > 0) ((duration - position) / 60_000).coerceAtLeast(1) else 0
}

/** Favoriter och "Fortsätt titta", sparat lokalt på TV:n. */
object Store {
    private const val MAX_HISTORY = 60

    private lateinit var prefs: SharedPreferences

    private val _favMovies = MutableStateFlow<List<Entry>>(emptyList())
    val favMovies: StateFlow<List<Entry>> = _favMovies

    private val _favSeries = MutableStateFlow<List<Series>>(emptyList())
    val favSeries: StateFlow<List<Series>> = _favSeries

    private val _history = MutableStateFlow<List<Progress>>(emptyList())
    /** Senast sedda först. */
    val history: StateFlow<List<Progress>> = _history

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("glimt_store", Context.MODE_PRIVATE)
        _favMovies.value = readArray("fav_movies") { entryFromJson(it) }
        _favSeries.value = readArray("fav_series") { seriesFromJson(it) }
        _history.value = readArray("history") { o ->
            Progress(entryFromJson(o.getJSONObject("entry")), o.optLong("pos"), o.optLong("dur"), o.optLong("t"))
        }
    }

    // ---------- Favoriter ----------

    fun isFavorite(e: Entry) = _favMovies.value.any { it.url == e.url }
    fun isFavorite(s: Series) = _favSeries.value.any { seriesKey(it) == seriesKey(s) }

    fun toggleFavorite(e: Entry) {
        _favMovies.value = if (isFavorite(e)) _favMovies.value.filterNot { it.url == e.url } else listOf(e) + _favMovies.value
        writeArray("fav_movies", _favMovies.value) { entryToJson(it) }
    }

    fun toggleFavorite(s: Series) {
        val light = s.copy(episodes = emptyList())
        _favSeries.value =
            if (isFavorite(s)) _favSeries.value.filterNot { seriesKey(it) == seriesKey(s) } else listOf(light) + _favSeries.value
        writeArray("fav_series", _favSeries.value) { seriesToJson(it) }
    }

    private fun seriesKey(s: Series) = s.xtreamId ?: "${s.group}/${s.name}"

    // ---------- Fortsätt titta ----------

    fun progressFor(url: String): Progress? = _history.value.firstOrNull { it.entry.url == url }

    /** Senaste avsnittet man tittat på i en serie. */
    fun lastEpisodeOf(seriesName: String): Progress? =
        _history.value.firstOrNull { it.entry.kind == Kind.EPISODE && it.entry.series == seriesName }

    fun saveProgress(e: Entry, position: Long, duration: Long) {
        val rest = _history.value.filterNot { it.entry.url == e.url }
        val finished = duration > 0 && position > duration * 0.93
        _history.value = if (finished || position < 10_000) {
            // Färdigsedd (eller knappt påbörjad) – visas inte under "Fortsätt titta"
            if (finished) rest else _history.value
        } else {
            (listOf(Progress(e, position, duration, System.currentTimeMillis())) + rest).take(MAX_HISTORY)
        }
        writeHistory()
    }

    fun removeProgress(url: String) {
        _history.value = _history.value.filterNot { it.entry.url == url }
        writeHistory()
    }

    private fun writeHistory() = writeArray("history", _history.value) { p ->
        JSONObject().put("entry", entryToJson(p.entry)).put("pos", p.position).put("dur", p.duration).put("t", p.updated)
    }

    // ---------- JSON ----------

    fun entryToJson(e: Entry): JSONObject = JSONObject()
        .put("title", e.title)
        .put("group", e.group)
        .put("logo", e.logo ?: "")
        .put("url", e.url)
        .put("kind", e.kind.name)
        .put("series", e.series ?: "")
        .put("season", e.season)
        .put("episode", e.episode)
        .put("xid", e.xtreamId ?: "")
        .put("added", e.added)

    fun entryFromJson(o: JSONObject) = Entry(
        title = o.optString("title"),
        group = o.optString("group"),
        logo = o.optString("logo").ifBlank { null },
        url = o.optString("url"),
        kind = if (o.optString("kind") == Kind.EPISODE.name) Kind.EPISODE else Kind.MOVIE,
        series = o.optString("series").ifBlank { null },
        season = o.optInt("season"),
        episode = o.optInt("episode"),
        xtreamId = o.optString("xid").ifBlank { null },
        added = o.optLong("added"),
    )

    private fun seriesToJson(s: Series): JSONObject = JSONObject()
        .put("name", s.name)
        .put("group", s.group)
        .put("logo", s.logo ?: "")
        .put("xid", s.xtreamId ?: "")

    private fun seriesFromJson(o: JSONObject) = Series(
        name = o.optString("name"),
        group = o.optString("group"),
        logo = o.optString("logo").ifBlank { null },
        episodes = emptyList(),
        xtreamId = o.optString("xid").ifBlank { null },
    )

    private fun <T> readArray(key: String, parse: (JSONObject) -> T): List<T> = try {
        val arr = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { runCatching { parse(it) }.getOrNull() } }
    } catch (e: Exception) {
        emptyList()
    }

    private fun <T> writeArray(key: String, items: List<T>, toJson: (T) -> JSONObject) {
        val arr = JSONArray()
        items.forEach { arr.put(toJson(it)) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
