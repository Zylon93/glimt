package se.pontus.glimt

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Sparar det färdigtolkade biblioteket i ett kompakt binärt format,
 * så att appen startar direkt i stället för att tolka om stora JSON-filer.
 */
object LibraryCache {
    private const val MAGIC = 0x474C4233 // "GLB3"

    fun write(lib: Library, file: File, sourceUrl: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        DataOutputStream(BufferedOutputStream(tmp.outputStream(), 256 * 1024)).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(sourceUrl.take(2000))
            out.writeInt(lib.movieGroups.size)
            for (g in lib.movieGroups) {
                out.writeUTF(g)
                val list = lib.movies[g].orEmpty()
                out.writeInt(list.size)
                for (e in list) writeEntry(out, e)
            }
            out.writeInt(lib.seriesGroups.size)
            for (g in lib.seriesGroups) {
                out.writeUTF(g)
                val list = lib.series[g].orEmpty()
                out.writeInt(list.size)
                for (s in list) {
                    out.writeUTF(s.name)
                    out.writeUTF(s.logo.orEmpty())
                    out.writeUTF(s.xtreamId.orEmpty())
                    out.writeLong(s.added)
                    out.writeUTF(s.tmdb.orEmpty())
                    out.writeFloat(s.rating)
                    out.writeInt(s.year)
                    out.writeInt(s.episodes.size)
                    for (e in s.episodes) writeEntry(out, e)
                }
            }
            out.writeInt(lib.adultGroups.size)
            lib.adultGroups.forEach { out.writeUTF(it) }
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    /** null om filen saknas, är trasig eller hör till en annan spellista. */
    fun read(file: File, sourceUrl: String): Library? {
        if (!file.exists()) return null
        DataInputStream(BufferedInputStream(file.inputStream(), 256 * 1024)).use { inp ->
            if (inp.readInt() != MAGIC) return null
            if (inp.readUTF() != sourceUrl.take(2000)) return null
            val movieGroups = ArrayList<String>()
            val movies = LinkedHashMap<String, List<Entry>>()
            repeat(inp.readInt()) {
                val g = inp.readUTF()
                val n = inp.readInt()
                val list = ArrayList<Entry>(n)
                repeat(n) { list += readEntry(inp, g) }
                movieGroups += g
                movies[g] = list
            }
            val seriesGroups = ArrayList<String>()
            val series = LinkedHashMap<String, List<Series>>()
            repeat(inp.readInt()) {
                val g = inp.readUTF()
                val n = inp.readInt()
                val list = ArrayList<Series>(n)
                repeat(n) {
                    val name = inp.readUTF()
                    val logo = inp.readUTF().ifEmpty { null }
                    val xid = inp.readUTF().ifEmpty { null }
                    val added = inp.readLong()
                    val tmdb = inp.readUTF().ifEmpty { null }
                    val rating = inp.readFloat()
                    val year = inp.readInt()
                    val eps = ArrayList<Entry>()
                    repeat(inp.readInt()) { eps += readEntry(inp, g) }
                    list += Series(name, g, logo, eps, xid, added, tmdb, rating, year)
                }
                seriesGroups += g
                series[g] = list
            }
            val adult = HashSet<String>()
            repeat(inp.readInt()) { adult += inp.readUTF() }
            return Library(movieGroups, movies, seriesGroups, series, adult)
        }
    }

    private fun writeEntry(out: DataOutputStream, e: Entry) {
        out.writeUTF(e.title)
        out.writeUTF(e.logo.orEmpty())
        out.writeUTF(e.url)
        out.writeByte(e.kind.ordinal)
        out.writeUTF(e.series.orEmpty())
        out.writeShort(e.season)
        out.writeShort(e.episode)
        out.writeUTF(e.xtreamId.orEmpty())
        out.writeLong(e.added)
        out.writeUTF(e.tmdb.orEmpty())
        out.writeFloat(e.rating)
        out.writeShort(e.year)
    }

    private fun readEntry(inp: DataInputStream, group: String) = Entry(
        title = inp.readUTF(),
        group = group,
        logo = inp.readUTF().ifEmpty { null },
        url = inp.readUTF(),
        kind = Kind.entries[inp.readByte().toInt()],
        series = inp.readUTF().ifEmpty { null },
        season = inp.readShort().toInt(),
        episode = inp.readShort().toInt(),
        xtreamId = inp.readUTF().ifEmpty { null },
        added = inp.readLong(),
        tmdb = inp.readUTF().ifEmpty { null },
        rating = inp.readFloat(),
        year = inp.readShort().toInt(),
    )
}
