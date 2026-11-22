package se.pontus.glimt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * TheIntroDB (theintrodb.org): tidsstämplar för intro, återblick och eftertexter.
 * Gratis API utan nyckel. GET /v4/media?tmdb_id=…&season=…&episode=…
 */
object IntroDb {
    enum class Type { INTRO, RECAP, CREDITS, PREVIEW }

    /** [end] = null betyder "till slutet". */
    data class Segment(val type: Type, val start: Long, val end: Long?)

    private const val BASE = "https://api.theintrodb.org/v4/media"

    suspend fun fetch(tmdbId: String?, imdbId: String?, season: Int?, episode: Int?, durationMs: Long?): List<Segment> =
        withContext(Dispatchers.IO) {
            val params = ArrayList<String>()
            when {
                tmdbId != null -> params += "tmdb_id=$tmdbId"
                imdbId != null -> params += "imdb_id=$imdbId"
                else -> return@withContext emptyList()
            }
            if (season != null && episode != null) {
                params += "season=$season"
                params += "episode=$episode"
            }
            if (durationMs != null && durationMs > 0) params += "duration_ms=$durationMs"
            val json = try {
                JSONObject(Net.bytes("$BASE?" + params.joinToString("&"), headers = mapOf("Accept" to "application/json")).decodeToString())
            } catch (e: Exception) {
                return@withContext emptyList() // 404 = inga tidsstämplar för titeln
            }
            val out = ArrayList<Segment>()
            for ((key, type) in listOf("intro" to Type.INTRO, "recap" to Type.RECAP, "credits" to Type.CREDITS, "preview" to Type.PREVIEW)) {
                val arr = json.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val start = if (o.isNull("start_ms")) 0L else o.optLong("start_ms")
                    val end = if (o.isNull("end_ms")) null else o.optLong("end_ms")
                    if (end != null && end <= start) continue
                    if (type != Type.CREDITS && end == null) continue
                    out += Segment(type, start, end)
                }
            }
            out.sortedBy { it.start }
        }
}
