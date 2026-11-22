package se.pontus.glimt

import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Många leverantörer spärrar nedladdning av hela M3U-filen (HTTP 884) men tillåter
 * Xtream Codes-API:t (player_api.php). Det är så IPVIO gör. En M3U-länk av typen
 * http://server/get.php?username=…&password=… görs om automatiskt.
 */
data class XtreamAccount(val base: String, val user: String, val pass: String) {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun api(action: String, extra: String = "") =
        "$base/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action$extra"
    fun movieUrl(id: String, ext: String) = "$base/movie/$user/$pass/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$base/series/$user/$pass/$id.${ext.ifBlank { "mp4" }}"
}

object Xtream {
    private val urlRe = Regex("""^(https?://[^?#]+?)/(?:get|player_api)\.php\?(.*)$""", RegexOption.IGNORE_CASE)

    fun accountFrom(url: String): XtreamAccount? {
        val m = urlRe.find(url.trim()) ?: return null
        val params = HashMap<String, String>()
        for (part in m.groupValues[2].split('&')) {
            val i = part.indexOf('=')
            if (i > 0) params[part.substring(0, i).lowercase()] = URLDecoder.decode(part.substring(i + 1), "UTF-8")
        }
        val user = params["username"] ?: return null
        val pass = params["password"] ?: return null
        return XtreamAccount(m.groupValues[1], user, pass)
    }

    fun load(dir: File, acc: XtreamAccount, force: Boolean, status: (String) -> Unit): Library {
        val vodCatFile = fetch(acc, "get_vod_categories", File(dir, "xc_vod_cat.json"), force, status, "filmkategorier")
        val vodFile = fetch(acc, "get_vod_streams", File(dir, "xc_vod.json"), force, status, "filmer")
        val serCatFile = fetch(acc, "get_series_categories", File(dir, "xc_series_cat.json"), force, status, "seriekategorier")
        val serFile = fetch(acc, "get_series", File(dir, "xc_series.json"), force, status, "serier")

        status("Läser in biblioteket…")

        // Filmer, i leverantörens kategoriordning
        val vodCats = categories(vodCatFile)
        val movies = LinkedHashMap<String, MutableList<Entry>>()
        vodCats.values.forEach { movies[it] = ArrayList() }
        readObjects(vodFile) { o ->
            val id = o["stream_id"] ?: return@readObjects
            val group = vodCats[o["category_id"]] ?: "Övrigt"
            val title = o["name"].orEmpty().ifBlank { "Okänd titel" }
            val logo = o["stream_icon"]?.takeIf { it.startsWith("http") }
            movies.getOrPut(group) { ArrayList() }
                .add(
                    Entry(
                        title, group, logo, acc.movieUrl(id, o["container_extension"].orEmpty()), Kind.MOVIE,
                        xtreamId = id, added = o["added"]?.toLongOrNull() ?: 0L,
                    )
                )
        }
        movies.entries.removeAll { it.value.isEmpty() }

        // Serier – avsnitten hämtas först när man öppnar serien
        val serCats = categories(serCatFile)
        val series = LinkedHashMap<String, MutableList<Series>>()
        serCats.values.forEach { series[it] = ArrayList() }
        readObjects(serFile) { o ->
            val id = o["series_id"] ?: return@readObjects
            val group = serCats[o["category_id"]] ?: "Övrigt"
            val name = o["name"].orEmpty().ifBlank { "Okänd serie" }
            val logo = o["cover"]?.takeIf { it.startsWith("http") }
            series.getOrPut(group) { ArrayList() }.add(Series(name, group, logo, emptyList(), xtreamId = id))
        }
        series.entries.removeAll { it.value.isEmpty() }

        return Library(movies.keys.toList(), movies, series.keys.toList(), series)
    }

    private val seriesCache = HashMap<String, Pair<Details?, List<Entry>>>()

    /** Seriens info och alla avsnitt. Cachas i minnet. */
    suspend fun seriesInfo(acc: XtreamAccount, s: Series): Pair<Details?, List<Entry>> {
        val id = s.xtreamId ?: return null to emptyList()
        synchronized(seriesCache) { seriesCache[id] }?.let { return it }
        return fetchSeriesInfo(acc, s).also { r -> synchronized(seriesCache) { seriesCache[id] = r } }
    }

    private suspend fun fetchSeriesInfo(acc: XtreamAccount, s: Series): Pair<Details?, List<Entry>> = withContext(Dispatchers.IO) {
        val json = JSONObject(Net.bytes(acc.api("get_series_info", "&series_id=${s.xtreamId}")).decodeToString())
        val details = json.optJSONObject("info")?.let { parseDetails(it) }
        val bySeason = json.optJSONObject("episodes") ?: return@withContext details to emptyList()
        val out = ArrayList<Entry>()
        for (key in bySeason.keys()) {
            val arr = bySeason.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optString("id")
                if (id.isBlank()) continue
                val season = e.optString("season").toIntOrNull() ?: key.toIntOrNull() ?: 0
                val num = e.optString("episode_num").toIntOrNull() ?: 0
                val title = e.optString("title").ifBlank { "Avsnitt $num" }
                out += Entry(
                    title, s.group, s.logo, acc.episodeUrl(id, e.optString("container_extension")),
                    Kind.EPISODE, s.name, season, num,
                    xtreamId = s.xtreamId, // för avsnitt: seriens id
                )
            }
        }
        details to out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private val vodCache = HashMap<String, Details>()

    /** Handling, bakgrundsbild, betyg m.m. för en film. Cachas i minnet. */
    suspend fun vodInfo(acc: XtreamAccount, vodId: String): Details? {
        synchronized(vodCache) { vodCache[vodId] }?.let { return it }
        return withContext(Dispatchers.IO) {
            val json = JSONObject(Net.bytes(acc.api("get_vod_info", "&vod_id=$vodId")).decodeToString())
            val info = json.optJSONObject("info") ?: return@withContext null
            parseDetails(info).also { d -> synchronized(vodCache) { vodCache[vodId] = d } }
        }
    }

    private fun parseDetails(info: JSONObject): Details {
        val backdrop = when (val b = info.opt("backdrop_path")) {
            is org.json.JSONArray -> if (b.length() > 0) b.optString(0) else ""
            is String -> b
            else -> ""
        }.takeIf { it.startsWith("http") }
        val date = info.optString("releasedate").ifBlank { info.optString("releaseDate") }
        val duration = info.optString("duration").let { d ->
            // "01:39:00" -> "1 h 39 min"
            val parts = d.split(":").mapNotNull { it.toIntOrNull() }
            if (parts.size >= 2 && (parts[0] > 0 || parts[1] > 0)) {
                if (parts[0] > 0) "${parts[0]} h ${parts[1]} min" else "${parts[1]} min"
            } else ""
        }
        return Details(
            plot = info.optString("plot").ifBlank { info.optString("description") }.trim(),
            backdrop = backdrop,
            rating = info.optString("rating").toDoubleOrNull()?.takeIf { it > 0 }?.let { "%.1f".format(it) },
            year = Regex("""(19|20)\d{2}""").find(date)?.value,
            duration = duration.ifBlank { null },
            genre = info.optString("genre").ifBlank { null },
            tmdbId = info.optString("tmdb_id").ifBlank { info.optString("tmdb") }
                .takeIf { (it.toIntOrNull() ?: 0) > 0 },
            imdbId = info.optString("imdb_id").takeIf { it.startsWith("tt") },
        )
    }

    // ---------- Hjälpfunktioner ----------

    /** Laddar ner ett API-svar till fil (cache). Används igen tills man trycker "Spara och ladda om". */
    private fun fetch(acc: XtreamAccount, action: String, file: File, force: Boolean, status: (String) -> Unit, what: String): File {
        if (!force && file.exists() && file.length() > 2) return file
        status("Hämtar $what…")
        val tmp = File(file.parentFile, file.name + ".tmp")
        val conn = Net.open(acc.api(action), readTimeoutMs = 120_000)
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
                            status("Hämtar $what… ${total / 1_000_000} MB")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        return file
    }

    private fun categories(file: File): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        readObjects(file) { o ->
            val id = o["category_id"] ?: return@readObjects
            map[id] = o["category_name"].orEmpty().ifBlank { "Kategori $id" }
        }
        return map
    }

    /** Läser en stor JSON-array objekt för objekt utan att hålla hela filen i minnet. */
    private fun readObjects(file: File, onObject: (Map<String, String>) -> Unit) {
        JsonReader(file.bufferedReader(Charsets.UTF_8)).use { r ->
            r.isLenient = true
            when (r.peek()) {
                JsonToken.BEGIN_ARRAY -> Unit
                JsonToken.BEGIN_OBJECT -> {
                    file.delete()
                    throw IOException("Servern nekade inloggningen. Kontrollera användarnamn och lösenord i länken, eller om kontot har gått ut.")
                }
                else -> return
            }
            r.beginArray()
            while (r.hasNext()) {
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue()
                    continue
                }
                val o = HashMap<String, String>()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    when (r.peek()) {
                        JsonToken.STRING, JsonToken.NUMBER -> o[name] = r.nextString()
                        JsonToken.BOOLEAN -> o[name] = r.nextBoolean().toString()
                        JsonToken.NULL -> r.nextNull()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                onObject(o)
            }
            r.endArray()
        }
    }
}
