package se.pontus.glimt

/** En textrad i en undertextfil. Tider i millisekunder. */
data class Cue(val start: Long, val end: Long, val text: String)

/** Enkel SRT-läsare. Appen visar texten själv så att synken kan justeras direkt. */
object Srt {
    private val timeRe by lazy {
        Regex("""(\d+):(\d+):(\d+)[,.](\d+)\s*-->\s*(\d+):(\d+):(\d+)[,.](\d+)""")
    }
    private val tagRe by lazy { Regex("""<[^>]*>|\{\\[^\}]*\}""") }

    fun parse(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = timeRe.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (timeRe.containsMatchIn(lines[i])) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i].trim())
                i++
            }
            val clean = tagRe.replace(body.toString(), "").trim()
            if (clean.isNotEmpty() && end > start) out += Cue(start, end, clean)
        }
        return out.sortedBy { it.start }
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val f = frac.padEnd(3, '0').take(3).toLong()
        return ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f
    }

    /** Raden som ska visas vid [pos]. [offset] > 0 visar texten senare. */
    fun at(cues: List<Cue>, pos: Long, offset: Long): String? {
        val t = pos - offset
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].start <= t) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0) return null
        // Överlappande rader: visa alla som är aktiva
        val active = (maxOf(0, found - 3)..found).map { cues[it] }.filter { t >= it.start && t < it.end }
        return active.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text }
    }
}
