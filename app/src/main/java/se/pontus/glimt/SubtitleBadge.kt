package se.pontus.glimt

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

/** Hur det ser ut med undertext för en titel, innan man ens trycker Spela. */
internal sealed interface SubState {
    data object Searching : SubState
    data class Found(val count: Int) : SubState
    /** Inget på valt språk. [fallback] är engelska om sådan finns. */
    data class Missing(val fallbackFound: Boolean) : SubState
}

/** Minns svaren under appens livstid, så att heron inte söker om varje gång den roterar. */
private val subCache = ConcurrentHashMap<String, SubState>()

/**
 * Söker undertext för [entry] i bakgrunden. Ger null om inga API-nycklar finns,
 * då visas inget märke alls.
 */
@Composable
internal fun rememberSubState(entry: Entry, prefs: Prefs): SubState? {
    val hasKeys = prefs.osKey.isNotBlank() || prefs.subdlKey.isNotBlank()
    val key = "${prefs.subLang}|${entry.url}"
    val state by produceState<SubState?>(if (hasKeys) subCache[key] ?: SubState.Searching else null, key, hasKeys) {
        if (!hasKeys) { value = null; return@produceState }
        subCache[key]?.let { value = it; return@produceState }
        value = SubState.Searching
        val q = Subtitles.queryFor(entry.title, entry.series, entry.season, entry.episode)
        val result = runCatching {
            val (list, errors) = Subtitles.search(q, prefs.subLang, prefs.osKey, prefs.subdlKey)
            when {
                list.isNotEmpty() -> SubState.Found(list.size)
                // Båda tjänsterna felade: visa inget hellre än ett felaktigt "saknas"
                errors.isNotEmpty() -> null
                prefs.subLang == "en" -> SubState.Missing(fallbackFound = false)
                else -> {
                    val (en, _) = Subtitles.search(q, "en", prefs.osKey, prefs.subdlKey)
                    SubState.Missing(fallbackFound = en.isNotEmpty())
                }
            }
        }.getOrNull()
        if (result != null) subCache[key] = result
        value = result
    }
    return state
}

/** "Svensk", "Engelsk" … för de vanligaste språken, annars null. */
private fun adjective(lang: String): String? = when (lang) {
    "sv" -> "Svensk"
    "en" -> "Engelsk"
    "no", "nb" -> "Norsk"
    "da" -> "Dansk"
    "fi" -> "Finsk"
    "de" -> "Tysk"
    "fr" -> "Fransk"
    "es" -> "Spansk"
    else -> null
}

private fun subLabel(lang: String): String =
    adjective(lang)?.let { "$it undertext" }
        ?: "Undertext (${java.util.Locale(lang).getDisplayLanguage(java.util.Locale("sv")).ifBlank { lang }})"

/** Märket under titeln i heron: söker / hittad / saknas. */
@Composable
internal fun SubtitleBadge(state: SubState, lang: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    val label = subLabel(lang)
    val (text, icon, fg) = when (state) {
        SubState.Searching -> Triple("Söker ${label.lowercase()}…", Icons.Rounded.Sync, TextBright)
        is SubState.Found -> Triple(
            "$label hittad · ${state.count} ${if (state.count == 1) "version" else "versioner"}",
            Icons.Rounded.Check,
            Accent,
        )
        is SubState.Missing -> Triple(
            "Ingen ${label.lowercase()}" + if (state.fallbackFound) " · engelsk finns" else "",
            Icons.Rounded.Close,
            Muted,
        )
    }
    val bg = when (state) {
        SubState.Searching -> Modifier.background(Muted.copy(alpha = 0.16f), shape)
        is SubState.Found -> Modifier.background(Accent.copy(alpha = 0.14f), shape)
        is SubState.Missing -> Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), shape)
    }
    Row(
        modifier.then(bg).padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (state is SubState.Missing) FontWeight.Medium else FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
