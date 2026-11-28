package se.pontus.glimt

import kotlinx.coroutines.delay

/**
 * Kollar om serier man sett ikapp har fått nya avsnitt. Körs när biblioteket är inläst
 * (och sedan var sjätte timme medan appen är öppen). Hittas ett nytt avsnitt hamnar
 * det först under Fortsätt titta, märkt "Nytt avsnitt".
 */
object NewEpisodes {
    private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000 // följ serier i ett halvår

    suspend fun checkAll() {
        val now = System.currentTimeMillis()
        for (f in Store.following.value) {
            if (now - f.updated > MAX_AGE_MS) continue
            val last = f.lastEpisode
            val name = last.series ?: continue
            val episodes = runCatching {
                val acc = Repo.account
                val id = last.xtreamId
                if (acc != null && id != null) {
                    Xtream.seriesInfo(acc, Series(name, last.group, last.logo, emptyList(), id), fresh = true).second
                } else {
                    Current.library?.allSeries?.firstOrNull { it.name == name && it.group == last.group }?.episodes.orEmpty()
                }
            }.getOrDefault(emptyList())
            val next = episodes.firstOrNull {
                it.season > last.season || (it.season == last.season && it.episode > last.episode)
            }
            if (next != null) Store.newEpisodeFound(f, next)
            delay(300) // skona leverantören
        }
    }
}
