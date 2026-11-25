package se.pontus.glimt

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder

/**
 * Genrer (Skräck, Komedi …) i stället för leverantörens kategorier.
 * 1. Kategorinamn med genreord ("EN | Horror", "SE | Komedi") ger genren direkt.
 * 2. Övriga titlar slås upp hos TMDB i bakgrunden (kräver gratis API-nyckel) och sparas.
 */
object Genres {
    private class G(val name: String, val words: String, val tmdb: Set<Int>) {
        val re by lazy { Regex("(?i)($words)") }
    }

    private val list = listOf(
        G("Action", "action|akcija|aksiyon|acción", setOf(28, 10759)),
        G("Äventyr", "adventure|äventyr|eventyr|abenteuer|aventura", setOf(12, 10759)),
        G("Komedi", "comed|komedi|komödie|comedia|commedia|humor", setOf(35)),
        G("Skräck", "horror|skräck|gyser|kauhu|terror|grusel", setOf(27)),
        G("Thriller", "thriller|spänning|suspense", setOf(53)),
        G("Drama", "drama", setOf(18)),
        G("Kriminal", "crime|krimi|kriminal|deckare|polis", setOf(80)),
        G("Sci-fi", "sci-fi|scifi|sci fi|science", setOf(878, 10765)),
        G("Fantasy", "fantasy|fantasi", setOf(14, 10765)),
        G("Animerat", "animat|anime|cartoon|tecknat|tegnefilm|zeichentrick", setOf(16)),
        G("Barn & familj", "kids|barn|children|family|familj|familie|disney|junior|kinder", setOf(10751, 10762)),
        G("Romantik", "romance|romantik|romantic|romantisk|love", setOf(10749)),
        G("Dokumentär", "docu|dokument", setOf(99)),
        G("Mysterium", "myster", setOf(9648)),
        G("Krig", "\\bwar\\b|krig|guerra|krieg", setOf(10752, 10768)),
        G("Historia", "histor", setOf(36)),
        G("Western", "western", setOf(37)),
        G("Musik", "music|musik|concert|konsert", setOf(10402)),
        G("Reality", "reality", setOf(10764)),
    )
    const val OTHER = "Övrigt"
    val order: List<String> = list.map { it.name } + OTHER

    private val categoryCache = HashMap<String, Set<String>>()

    /** Genrer som kan läsas ut ur ett kategorinamn. */
    fun fromCategory(category: String): Set<String> = synchronized(categoryCache) {
        categoryCache.getOrPut(category) { list.filter { it.re.containsMatchIn(category) }.map { it.name }.toSet() }
    }

    fun fromTmdb(ids: IntArray): Set<String> = list.filter { g -> ids.any { it in g.tmdb } }.map { it.name }.toSet()

    fun movieKey(e: Entry) = "m:" + (e.xtreamId ?: e.url)
    fun seriesKey(s: Series) = "s:" + (s.xtreamId ?: "${s.group}/${s.name}")

    /** Bygger ett "bibliotek" där grupperna är genrer, så att samma vy kan visa det. */
    fun build(lib: Library): Library {
        val cache = Tmdb.snapshot()
        val movies = LinkedHashMap<String, MutableList<Entry>>()
        val series = LinkedHashMap<String, MutableList<Series>>()
        order.forEach { movies[it] = ArrayList(); series[it] = ArrayList() }
        for (e in lib.allMovies) {
            val g = fromCategory(e.group).ifEmpty { cache[movieKey(e)]?.let { fromTmdb(it) }.orEmpty() }.ifEmpty { setOf(OTHER) }
            g.forEach { movies.getValue(it).add(e) }
        }
        for (s in lib.allSeries) {
            val g = fromCategory(s.group).ifEmpty { cache[seriesKey(s)]?.let { fromTmdb(it) }.orEmpty() }.ifEmpty { setOf(OTHER) }
            g.forEach { series.getValue(it).add(s) }
        }
        movies.values.forEach { l -> l.sortByDescending { it.added } }
        series.values.forEach { l -> l.sortByDescending { it.added } }
        movies.entries.removeAll { it.value.isEmpty() }
        series.entries.removeAll { it.value.isEmpty() }
        return Library(movies.keys.toList(), movies, series.keys.toList(), series, lib.adultGroups, lib.liveGroups, lib.live)
    }
}

/** Slår upp genrer hos TMDB i bakgrunden och sparar resultatet på TV:n. */
object Tmdb {
    data class Progress(val done: Int, val total: Int, val error: String? = null)

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    /** Ökar när nya genrer har sparats, så att vyerna byggs om. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var file: File? = null
    private val cache = HashMap<String, IntArray>()
    private var loaded = false

    fun snapshot(): Map<String, IntArray> = synchronized(cache) { HashMap(cache) }

    private fun load(context: Context) = synchronized(cache) {
        if (loaded) return@synchronized
        loaded = true
        val f = File(context.filesDir, "genres.json").also { file = it }
        if (!f.exists()) return@synchronized
        runCatching {
            val o = JSONObject(f.readText())
            for (k in o.keys()) {
                val arr = o.getJSONArray(k)
                cache[k] = IntArray(arr.length()) { arr.getInt(it) }
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
        synchronized(cache) { cache.forEach { (k, v) -> o.put(k, JSONArray().apply { v.forEach { put(it) } }) } }
        runCatching { f.writeText(o.toString()) }
    }

    /** Startar (om) uppslagningen för titlar som saknar genre. */
    fun start(context: Context, lib: Library, key: String) {
        load(context.applicationContext)
        _version.value++
        job?.cancel()
        if (key.isBlank()) {
            _progress.value = null
            return
        }
        job = scope.launch {
            val known = snapshot()
            val movies = lib.allMovies.filter { Genres.fromCategory(it.group).isEmpty() && Genres.movieKey(it) !in known }
                .distinctBy { Genres.movieKey(it) }
            val series = lib.allSeries.filter { Genres.fromCategory(it.group).isEmpty() && Genres.seriesKey(it) !in known }
                .distinctBy { Genres.seriesKey(it) }
            val jobs: List<Pair<String, suspend () -> IntArray>> =
                movies.map { e -> Genres.movieKey(e) to suspend { lookup(key, false, e.tmdb, e.title) } } +
                    series.map { s -> Genres.seriesKey(s) to suspend { lookup(key, true, s.tmdb, s.name) } }
            if (jobs.isEmpty()) {
                _progress.value = null
                return@launch
            }
            var done = 0
            _progress.value = Progress(0, jobs.size)
            for (chunk in jobs.chunked(8)) {
                if (!isActive) break
                val results = chunk.map { (k, fn) ->
                    async {
                        k to try {
                            fn()
                        } catch (e: AuthError) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
                val got = try {
                    results.awaitAll()
                } catch (e: AuthError) {
                    _progress.value = Progress(done, jobs.size, "TMDB-nyckeln fungerar inte")
                    return@launch
                }
                synchronized(cache) { got.forEach { (k, v) -> if (v != null) cache[k] = v } }
                done += chunk.size
                _progress.value = Progress(done, jobs.size)
                if (done % 400 < 8) {
                    save()
                    _version.value++
                }
                delay(120) // håll oss väl under TMDB:s gräns
            }
            save()
            _version.value++
            _progress.value = null
        }
    }

    // ---------- Skådespelare, trailer och rekommendationer för detaljsidan ----------

    data class Person(val name: String, val role: String, val photo: String?)
    data class Extras(
        val cast: List<Person>,
        val trailerKey: String?,
        /** Rekommenderade titlar (titel, år) att matcha mot biblioteket. */
        val recommendations: List<Pair<String, Int>>,
    )

    private val extrasCache = HashMap<String, Extras>()

    /** null om ingen nyckel finns eller titeln inte hittas. */
    suspend fun extras(key: String, tv: Boolean, tmdbId: String?, rawTitle: String): Extras? {
        if (key.isBlank()) return null
        val cacheKey = (if (tv) "tv:" else "m:") + (tmdbId ?: rawTitle)
        synchronized(extrasCache) { extrasCache[cacheKey] }?.let { return it }
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            val type = if (tv) "tv" else "movie"
            val id = tmdbId ?: run {
                val (title, year) = Subtitles.clean(rawTitle)
                val q = URLEncoder.encode(title, "UTF-8")
                val yearParam = if (year == null) "" else if (tv) "&first_air_date_year=$year" else "&year=$year"
                get(key, "/search/$type?query=$q$yearParam&include_adult=false").optJSONArray("results")
                    ?.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }?.toString()
            } ?: return@withContext null
            val o = get(key, "/$type/$id?append_to_response=credits,videos,recommendations&language=sv-SE&include_video_language=sv,en")
            val cast = ArrayList<Person>()
            o.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 14)) {
                    val p = arr.optJSONObject(i) ?: continue
                    val path = p.optString("profile_path").takeIf { it.startsWith("/") }
                    cast += Person(p.optString("name"), p.optString("character"), path?.let { "https://image.tmdb.org/t/p/w185$it" })
                }
            }
            var trailer: String? = null
            o.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    if (v.optString("site") == "YouTube" && v.optString("type") == "Trailer") {
                        trailer = v.optString("key")
                        if (v.optString("iso_639_1") == "sv") break
                    }
                }
            }
            val recs = ArrayList<Pair<String, Int>>()
            o.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val t = r.optString(if (tv) "name" else "title").ifBlank { r.optString(if (tv) "original_name" else "original_title") }
                    val d = r.optString(if (tv) "first_air_date" else "release_date")
                    if (t.isNotBlank()) recs += t to (d.take(4).toIntOrNull() ?: 0)
                }
            }
            Extras(cast, trailer, recs).also { synchronized(extrasCache) { extrasCache[cacheKey] = it } }
        }
    }

    private class AuthError : IOException("TMDB 401")

    private fun get(key: String, path: String): JSONObject {
        val bearer = key.length > 40
        val sep = if ('?' in path) '&' else '?'
        val url = "https://api.themoviedb.org/3$path" + if (bearer) "" else "${sep}api_key=$key"
        val headers = if (bearer) mapOf("Authorization" to "Bearer $key", "Accept" to "application/json") else mapOf("Accept" to "application/json")
        return try {
            JSONObject(Net.bytes(url, headers = headers).decodeToString())
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            when {
                msg.startsWith("HTTP 401") -> throw AuthError()
                msg.startsWith("HTTP 429") -> {
                    Thread.sleep(2000)
                    JSONObject(Net.bytes(url, headers = headers).decodeToString())
                }
                else -> throw e
            }
        }
    }

    /** Genre-id:n för en titel. Tom lista = hittades inte (sparas så att den inte slås upp igen). */
    private fun lookup(key: String, tv: Boolean, tmdbId: String?, rawTitle: String): IntArray {
        val type = if (tv) "tv" else "movie"
        if (tmdbId != null) {
            val o = runCatching { get(key, "/$type/$tmdbId") }.getOrNull()
            val arr = o?.optJSONArray("genres")
            if (arr != null && arr.length() > 0) return IntArray(arr.length()) { arr.getJSONObject(it).optInt("id") }
        }
        val (title, year) = Subtitles.clean(rawTitle)
        if (title.isBlank()) return IntArray(0)
        val q = URLEncoder.encode(title, "UTF-8")
        val yearParam = if (year == null) "" else if (tv) "&first_air_date_year=$year" else "&year=$year"
        var res = get(key, "/search/$type?query=$q$yearParam&include_adult=false").optJSONArray("results")
        if ((res == null || res.length() == 0) && year != null) {
            res = get(key, "/search/$type?query=$q&include_adult=false").optJSONArray("results")
        }
        val first = res?.optJSONObject(0) ?: return IntArray(0)
        val ids = first.optJSONArray("genre_ids") ?: return IntArray(0)
        return IntArray(ids.length()) { ids.optInt(it) }
    }
}
