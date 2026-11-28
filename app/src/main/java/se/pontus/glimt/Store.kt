package se.pontus.glimt

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Hur långt man har kommit i en film eller ett avsnitt. */
data class Progress(
    val entry: Entry,
    val position: Long,
    val duration: Long,
    val updated: Long,
    /** Ett nytt avsnitt som kommit ut sedan man såg ikapp serien. */
    val isNew: Boolean = false,
) {
    val fraction: Float get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val minutesLeft: Long get() = if (duration > 0) ((duration - position) / 60_000).coerceAtLeast(1) else 0
    /** Nästa avsnitt i en serie man just sett klart (inte påbörjat än). */
    val upNext: Boolean get() = duration == 0L && position == 0L
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

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())
    /** Dolda språkgrupper ("p:DK") och kategorier ("c:<namn>"). */
    val hidden: StateFlow<Set<String>> = _hidden

    fun setHidden(keys: Set<String>) {
        _hidden.value = keys
        prefs.edit().putStringSet("hidden", HashSet(keys)).apply()
    }

    fun toggleHidden(key: String) = setHidden(if (key in _hidden.value) _hidden.value - key else _hidden.value + key)

    private val _showAdult = MutableStateFlow(false)
    /** Visa kategorier med vuxeninnehåll (av som standard). */
    val showAdult: StateFlow<Boolean> = _showAdult

    fun setShowAdult(v: Boolean) {
        _showAdult.value = v
        prefs.edit().putBoolean("show_adult", v).apply()
    }

    private val _genreMode = MutableStateFlow(true)
    /** Filmer/Serier sorterade efter genre (på) eller leverantörens kategorier (av). */
    val genreMode: StateFlow<Boolean> = _genreMode
    fun setGenreMode(v: Boolean) {
        _genreMode.value = v
        prefs.edit().putBoolean("genre_mode", v).apply()
    }

    private val _tmdbKey = MutableStateFlow("")
    val tmdbKey: StateFlow<String> = _tmdbKey
    fun setTmdbKey(v: String) {
        _tmdbKey.value = v.trim()
        prefs.edit().putString("tmdb_key", v.trim()).apply()
    }

    private val _sort = MutableStateFlow(Sort.NEWEST)
    val sort: StateFlow<Sort> = _sort
    fun setSort(v: Sort) {
        _sort.value = v
        prefs.edit().putString("sort", v.name).apply()
    }

    // ---------- Ljud och undertext per serie ----------

    /** Sparat val för en serie: ljudspråk och om undertexter ska vara av. */
    data class SeriesPref(val audio: String?, val subsOff: Boolean)

    private val seriesPrefs = HashMap<String, SeriesPref>()

    fun seriesPrefKey(e: Entry): String? =
        if (e.kind == Kind.EPISODE && e.series != null) "s:" + (e.xtreamId ?: e.series) else null

    fun seriesPref(e: Entry): SeriesPref? = seriesPrefKey(e)?.let { synchronized(seriesPrefs) { seriesPrefs[it] } }

    fun setSeriesAudio(e: Entry, lang: String?) = updateSeriesPref(e) { it.copy(audio = lang) }
    fun setSeriesSubsOff(e: Entry, off: Boolean) = updateSeriesPref(e) { it.copy(subsOff = off) }

    private fun updateSeriesPref(e: Entry, change: (SeriesPref) -> SeriesPref) {
        val key = seriesPrefKey(e) ?: return
        val o = JSONObject()
        synchronized(seriesPrefs) {
            seriesPrefs[key] = change(seriesPrefs[key] ?: SeriesPref(null, false))
            seriesPrefs.forEach { (k, v) -> o.put(k, JSONObject().put("audio", v.audio ?: "").put("off", v.subsOff)) }
        }
        prefs.edit().putString("series_prefs", o.toString()).apply()
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("glimt_store", Context.MODE_PRIVATE)
        _showAdult.value = prefs.getBoolean("show_adult", false)
        runCatching {
            val o = JSONObject(prefs.getString("series_prefs", "{}") ?: "{}")
            for (k in o.keys()) {
                val v = o.getJSONObject(k)
                seriesPrefs[k] = SeriesPref(v.optString("audio").ifBlank { null }, v.optBoolean("off", false))
            }
        }
        _sort.value = runCatching { Sort.valueOf(prefs.getString("sort", "NEWEST") ?: "NEWEST") }.getOrDefault(Sort.NEWEST)
        _genreMode.value = prefs.getBoolean("genre_mode", true)
        _tmdbKey.value = prefs.getString("tmdb_key", "").orEmpty()
        _hidden.value = prefs.getStringSet("hidden", emptySet())?.toSet() ?: emptySet()
        _favChannels.value = readArray("fav_channels") { entryFromJson(it) }
        _favMovies.value = readArray("fav_movies") { entryFromJson(it) }
        _favSeries.value = readArray("fav_series") { seriesFromJson(it) }
        _history.value = readArray("history") { o ->
            Progress(entryFromJson(o.getJSONObject("entry")), o.optLong("pos"), o.optLong("dur"), o.optLong("t"), o.optBoolean("new", false))
        }
        _following.value = readArray("following") { o -> Follow(entryFromJson(o.getJSONObject("entry")), o.optLong("t")) }
    }

    // ---------- Favoritkanaler (live-TV) ----------

    private val _favChannels = MutableStateFlow<List<Entry>>(emptyList())
    val favChannels: StateFlow<List<Entry>> = _favChannels

    fun toggleFavoriteChannel(e: Entry) {
        _favChannels.value =
            if (_favChannels.value.any { it.url == e.url }) _favChannels.value.filterNot { it.url == e.url }
            else _favChannels.value + e
        writeArray("fav_channels", _favChannels.value) { entryToJson(it) }
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

    /**
     * Sparar hur långt man kommit. Är titeln färdigsedd och det finns ett [next] avsnitt
     * läggs det in under "Fortsätt titta" i stället, så att serien ligger kvar där.
     * Varje serie visas bara en gång (senaste avsnittet).
     */
    fun saveProgress(
        e: Entry,
        position: Long,
        duration: Long,
        finished: Boolean = duration > 0 && position > duration * 0.93,
        next: Entry? = null,
    ) {
        if (e.kind == Kind.LIVE) return // live-TV har ingen position att fortsätta från
        // Bara öppnad några sekunder: ändra ingenting
        if (!finished && position < 10_000) return
        var rest = _history.value.filterNot { it.entry.url == e.url }
        if (e.kind == Kind.EPISODE && e.series != null) {
            rest = rest.filterNot { it.entry.kind == Kind.EPISODE && it.entry.series == e.series && it.entry.xtreamId == e.xtreamId }
        }
        val now = System.currentTimeMillis()
        _history.value = when {
            finished && next != null -> (listOf(Progress(next, 0, 0, now)) + rest).take(MAX_HISTORY)
            finished -> rest
            else -> (listOf(Progress(e, position, duration, now)) + rest).take(MAX_HISTORY)
        }
        writeHistory()
        // Sett sista avsnittet som finns: vänta på nästa, så att serien kommer tillbaka när det släpps
        if (e.kind == Kind.EPISODE && e.series != null) {
            val others = _following.value.filterNot { sameSeries(it.lastEpisode, e) }
            setFollowing(if (finished && next == null) (listOf(Follow(e, now)) + others).take(60) else others)
        }
    }

    /** Tar bort alla avsnitt av en serie från "Fortsätt titta". */
    fun removeSeries(seriesName: String, xtreamId: String?) {
        _history.value = _history.value.filterNot {
            it.entry.kind == Kind.EPISODE && it.entry.series == seriesName && it.entry.xtreamId == xtreamId
        }
        writeHistory()
        // Bortplockad av användaren: sluta också vänta på nya avsnitt
        setFollowing(_following.value.filterNot { it.lastEpisode.series == seriesName && it.lastEpisode.xtreamId == xtreamId })
    }

    /** Tar bort en post från "Fortsätt titta" (hela serien om det är ett avsnitt). */
    fun remove(p: Progress) {
        val s = p.entry.series
        if (p.entry.kind == Kind.EPISODE && s != null) removeSeries(s, p.entry.xtreamId) else removeProgress(p.entry.url)
    }

    fun removeProgress(url: String) {
        _history.value = _history.value.filterNot { it.entry.url == url }
        writeHistory()
    }

    private fun writeHistory() = writeArray("history", _history.value) { p ->
        JSONObject().put("entry", entryToJson(p.entry)).put("pos", p.position).put("dur", p.duration).put("t", p.updated)
            .put("new", p.isNew)
    }

    // ---------- Serier man sett ikapp (väntar på nästa avsnitt) ----------

    /** Senaste avsnittet man sett av en serie där det inte fanns något nästa avsnitt än. */
    data class Follow(val lastEpisode: Entry, val updated: Long)

    private val _following = MutableStateFlow<List<Follow>>(emptyList())
    val following: StateFlow<List<Follow>> = _following

    private fun sameSeries(a: Entry, b: Entry) = a.series == b.series && a.xtreamId == b.xtreamId

    private fun setFollowing(list: List<Follow>) {
        _following.value = list
        writeArray("following", list) { f -> JSONObject().put("entry", entryToJson(f.lastEpisode)).put("t", f.updated) }
    }

    /** Ett nytt avsnitt har kommit ut: lägg det först under Fortsätt titta och sluta vänta. */
    fun newEpisodeFound(f: Follow, next: Entry) {
        setFollowing(_following.value.filterNot { sameSeries(it.lastEpisode, f.lastEpisode) })
        val rest = _history.value.filterNot { it.entry.kind == Kind.EPISODE && sameSeries(it.entry, next) }
        _history.value = (listOf(Progress(next, 0, 0, System.currentTimeMillis(), isNew = true)) + rest).take(MAX_HISTORY)
        writeHistory()
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
        kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.MOVIE),
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
