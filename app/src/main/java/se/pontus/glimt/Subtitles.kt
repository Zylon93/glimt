package se.pontus.glimt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Collections
import java.util.zip.ZipInputStream

data class SubQuery(val title: String, val year: Int?, val season: Int?, val episode: Int?) {
    val isEpisode: Boolean get() = season != null && episode != null
}

/** Ett sökresultat. [fetch] laddar ner och returnerar SRT-texten som UTF-8. */
class SubResult(val label: String, val fetch: suspend () -> String)

object Subtitles {
    private const val OS_API = "https://api.opensubtitles.com/api/v1"

    // "SE - ", "|SE| ", "[EN] ", "NF: " m.m. som IPTV-leverantörer sätter före titeln
    private val prefixRe by lazy { Regex("""^\s*(?:[|\[\(]\s*[A-Za-z0-9+]{2,4}\s*[|\]\)]|[A-Z]{2,3}\s*[\-:|])\s*""") }
    private val parenYearRe by lazy { Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""") }
    private val trailingYearRe by lazy { Regex("""[\s\-.]((?:19|20)\d{2})\s*$""") }
    private val tagRe by lazy { Regex("""\[[^\]]*\]|\{[^\}]*\}""") }
    private val qualityRe by lazy { Regex("""(?i)\b(4k|uhd|fhd|hd|sd|hevc|h\.?26[45]|x26[45]|multi|dual|1080p|720p|2160p)\b""") }

    fun clean(raw: String): Pair<String, Int?> {
        var t = raw
        repeat(3) { t = prefixRe.replace(t, "") }
        var year: Int? = null
        val paren = parenYearRe.find(t)
        if (paren != null) {
            year = paren.groupValues[1].toInt()
            t = t.substring(0, paren.range.first)
        } else {
            val trailing = trailingYearRe.find(t)
            if (trailing != null) {
                year = trailing.groupValues[1].toInt()
                t = t.substring(0, trailing.range.first)
            }
        }
        t = tagRe.replace(t, " ")
        t = qualityRe.replace(t, " ")
        t = t.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().trim('-', ':', '|', '.').trim()
        return t.ifBlank { raw.trim() } to year
    }

    fun queryFor(title: String, series: String?, season: Int, episode: Int): SubQuery = try {
        buildQuery(title, series, season, episode)
    } catch (e: Exception) {
        SubQuery(series ?: title, null, if (season > 0) season else null, if (season > 0) episode else null)
    }

    private fun buildQuery(title: String, series: String?, season: Int, episode: Int): SubQuery =
        if (series != null && season > 0) {
            val (name, year) = clean(series)
            SubQuery(name, year, season, episode)
        } else {
            val (name, year) = clean(title)
            SubQuery(name, year, null, null)
        }

    /** Söker parallellt hos båda tjänsterna. Returnerar (resultat, felmeddelanden). */
    suspend fun search(q: SubQuery, lang: String, osKey: String, subdlKey: String): Pair<List<SubResult>, List<String>> =
        coroutineScope {
            val errors = Collections.synchronizedList(mutableListOf<String>())
            val os = async(Dispatchers.IO) {
                if (osKey.isBlank()) emptyList()
                else try { openSubtitles(q, lang, osKey) } catch (e: Exception) { errors += "OpenSubtitles: ${e.message}"; emptyList() }
            }
            val sd = async(Dispatchers.IO) {
                if (subdlKey.isBlank()) emptyList()
                else try { subdl(q, lang, subdlKey) } catch (e: Exception) { errors += "SubDL: ${e.message}"; emptyList() }
            }
            (os.await() + sd.await()) to errors.toList()
        }

    // ---------- OpenSubtitles ----------

    private fun osHeaders(key: String) = mapOf(
        "Api-Key" to key,
        "Accept" to "application/json",
        "User-Agent" to "Glimt v1.0",
    )

    private fun openSubtitles(q: SubQuery, lang: String, key: String): List<SubResult> {
        // OpenSubtitles vill ha parametrarna i bokstavsordning och med gemener.
        val params = sortedMapOf<String, String>()
        params["languages"] = lang.lowercase()
        params["query"] = q.title.lowercase()
        if (q.isEpisode) {
            params["season_number"] = q.season.toString()
            params["episode_number"] = q.episode.toString()
            params["type"] = "episode"
        } else {
            params["type"] = "movie"
            q.year?.let { params["year"] = it.toString() }
        }
        val url = "$OS_API/subtitles?" + params.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val json = JSONObject(Net.bytes(url, headers = osHeaders(key)).decodeToString())
        val data = json.optJSONArray("data") ?: return emptyList()

        val found = ArrayList<Pair<Int, SubResult>>()
        for (i in 0 until data.length()) {
            val a = data.getJSONObject(i).optJSONObject("attributes") ?: continue
            val files = a.optJSONArray("files") ?: continue
            if (files.length() == 0) continue
            val file = files.getJSONObject(0)
            val fileId = file.optLong("file_id")
            val release = a.optString("release").ifBlank { file.optString("file_name") }
            found += a.optInt("download_count") to SubResult("OpenSubtitles · $release") { osDownload(fileId, key) }
        }
        return found.sortedByDescending { it.first }.map { it.second }
    }

    private suspend fun osDownload(fileId: Long, key: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("file_id", fileId).put("sub_format", "srt").toString().toByteArray()
        val res = JSONObject(
            Net.bytes("$OS_API/download", "POST", osHeaders(key) + ("Content-Type" to "application/json"), body).decodeToString()
        )
        val link = res.optString("link")
        if (link.isBlank()) throw IOException(res.optString("message").ifBlank { "Ingen nedladdningslänk" })
        decode(Net.bytes(link))
    }

    // ---------- SubDL ----------

    private fun subdl(q: SubQuery, lang: String, key: String): List<SubResult> {
        val p = linkedMapOf(
            "api_key" to key,
            "film_name" to q.title,
            "languages" to lang.uppercase(),
            "type" to if (q.isEpisode) "tv" else "movie",
            "subs_per_page" to "30",
        )
        if (q.isEpisode) {
            p["season_number"] = q.season.toString()
            p["episode_number"] = q.episode.toString()
        } else {
            q.year?.let { p["year"] = it.toString() }
        }
        val url = "https://api.subdl.com/api/v1/subtitles?" + p.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val json = JSONObject(Net.bytes(url).decodeToString())
        if (!json.optBoolean("status", false)) {
            val err = json.optString("error")
            if (err.contains("api", ignoreCase = true)) throw IOException(err)
            return emptyList() // "hittade inget"
        }
        val subs = json.optJSONArray("subtitles") ?: return emptyList()
        val out = ArrayList<SubResult>()
        for (i in 0 until subs.length()) {
            val s = subs.getJSONObject(i)
            val path = s.optString("url")
            if (path.isBlank()) continue
            val name = s.optString("release_name").ifBlank { s.optString("name") }
            out += SubResult("SubDL · $name") { subdlDownload(path, q) }
        }
        return out
    }

    private suspend fun subdlDownload(path: String, q: SubQuery): String = withContext(Dispatchers.IO) {
        val url = when {
            path.startsWith("http") -> path
            path.startsWith("/") -> "https://dl.subdl.com$path"
            else -> "https://dl.subdl.com/$path"
        }
        val bytes = Net.bytes(url)
        val isZip = bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        decode(if (isZip) pickFromZip(bytes, q) else bytes)
    }

    private fun pickFromZip(zip: ByteArray, q: SubQuery): ByteArray {
        val files = ArrayList<Pair<String, ByteArray>>()
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase().endsWith(".srt")) files += e.name to z.readBytes()
            }
        }
        if (files.isEmpty()) throw IOException("Arkivet innehöll ingen .srt-fil")
        if (q.isEpisode) {
            val patterns = listOf(
                Regex("""(?i)S0*${q.season}\s*[._-]?\s*E0*${q.episode}(?!\d)"""),
                Regex("""(?i)(?<!\d)0*${q.season}x0*${q.episode}(?!\d)"""),
            )
            for (re in patterns) files.firstOrNull { re.containsMatchIn(it.first) }?.let { return it.second }
        }
        return files.maxBy { it.second.size }.second
    }

    // ---------- Hjälpfunktioner ----------

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Svenska .srt-filer är ofta Windows-1252. Gör om allt till korrekt text så att åäö blir rätt. */
    fun decode(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, Charsets.UTF_16LE).removePrefix("﻿")
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, Charsets.UTF_16BE).removePrefix("﻿")
        }
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            strict.decode(ByteBuffer.wrap(b)).toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1252"))
        }
    }
}
