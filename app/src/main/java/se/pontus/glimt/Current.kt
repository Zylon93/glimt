package se.pontus.glimt

/**
 * Det bibliotek som visas just nu (utan dolda kategorier), så att detaljsidorna
 * kan hitta "Mer som detta" utan att allt måste skickas som parametrar.
 */
object Current {
    @Volatile var library: Library? = null
    @Volatile var genreLibrary: Library? = null

    private var indexFor: Library? = null
    private var titleIndex: Map<String, List<Entry>> = emptyMap()
    private var seriesIndex: Map<String, List<Series>> = emptyMap()

    private fun norm(t: String) = displayTitle(t).lowercase().filter { it.isLetterOrDigit() }

    @Synchronized
    private fun ensureIndex() {
        val lib = library ?: return
        if (indexFor === lib) return
        titleIndex = lib.allMovies.groupBy { norm(it.title) }
        seriesIndex = lib.allSeries.groupBy { norm(it.name) }
        indexFor = lib
    }

    /** Filmer som liknar [e]: först TMDB:s rekommendationer som finns i biblioteket, sedan samma genre. */
    fun similarMovies(e: Entry, recs: List<Pair<String, Int>>): List<Entry> {
        ensureIndex()
        val out = LinkedHashMap<String, Entry>()
        for ((t, y) in recs) {
            val hits = titleIndex[norm(t)] ?: continue
            val best = hits.firstOrNull { y == 0 || it.year == 0 || it.year == y } ?: continue
            if (best.url != e.url) out[best.url] = best
        }
        val g = genresOf(e.group, Genres.movieKey(e))
        val gl = genreLibrary
        if (gl != null) {
            for (genre in g) for (m in gl.movies[genre].orEmpty().take(60)) {
                if (out.size >= 30) break
                if (m.url != e.url) out.putIfAbsent(m.url, m)
            }
        }
        return out.values.take(30)
    }

    fun similarSeries(s: Series, recs: List<Pair<String, Int>>): List<Series> {
        ensureIndex()
        val key = { x: Series -> x.xtreamId ?: "${x.group}/${x.name}" }
        val out = LinkedHashMap<String, Series>()
        for ((t, _) in recs) {
            val best = seriesIndex[norm(t)]?.firstOrNull() ?: continue
            if (key(best) != key(s)) out[key(best)] = best
        }
        val gl = genreLibrary
        if (gl != null) {
            for (genre in genresOf(s.group, Genres.seriesKey(s))) for (x in gl.series[genre].orEmpty().take(60)) {
                if (out.size >= 30) break
                if (key(x) != key(s)) out.putIfAbsent(key(x), x)
            }
        }
        return out.values.take(30)
    }

    private fun genresOf(group: String, cacheKey: String): Set<String> =
        Genres.fromCategory(group).ifEmpty { Tmdb.snapshot()[cacheKey]?.let { Genres.fromTmdb(it) }.orEmpty() }
}
