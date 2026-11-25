package se.pontus.glimt

import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

/** Vad som visas ovanpå huvudvyn: en films eller en series detaljsida. */
sealed interface Detail {
    data class Movie(val entry: Entry) : Detail
    data class Show(val series: Series) : Detail
}

@Composable
private fun Backdrop(url: String?) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (url != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg, 0.5f to Bg.copy(alpha = 0.85f), 1f to Bg.copy(alpha = 0.3f))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg)))
    }
}

// ---------------------------------------------------------------- Film

@Composable
internal fun MovieDetail(e: Entry, onPlay: (Entry, Boolean) -> Unit, onOpen: (Detail) -> Unit) {
    val history by Store.history.collectAsState()
    val favorites by Store.favMovies.collectAsState()
    val tmdbKey by Store.tmdbKey.collectAsState()
    val progress = history.firstOrNull { it.entry.url == e.url }
    val isFav = favorites.any { it.url == e.url }
    val context = LocalContext.current
    val details by produceState<Details?>(null, e.url) {
        val acc = Repo.account
        val id = e.xtreamId
        if (acc != null && id != null) value = runCatching { Xtream.vodInfo(acc, id) }.getOrNull()
    }
    val extras by produceState<Tmdb.Extras?>(null, e.url, tmdbKey, details) {
        value = runCatching { Tmdb.extras(tmdbKey, false, e.tmdb ?: details?.tmdbId, e.title) }.getOrNull()
    }
    val similar by produceState<List<Entry>>(emptyList(), e.url, extras) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { Current.similarMovies(e, extras?.recommendations.orEmpty()) }.getOrDefault(emptyList())
        }
    }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(e.url) { runCatching { playFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(details?.backdrop ?: e.logo)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 48.dp, top = 60.dp, bottom = 48.dp)
        ) {
            Row(Modifier.padding(end = 48.dp)) {
                Poster(e.logo, e.title, Modifier.width(230.dp).height(345.dp).clip(RoundedCornerShape(18.dp)))
                Spacer(Modifier.width(40.dp))
                Column(Modifier.widthIn(max = 760.dp)) {
                    Text(displayTitle(e.title), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)
                    Spacer(Modifier.height(8.dp))
                    MetaLine(details)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        entryBadges(e).forEach { b ->
                            Text(
                                b,
                                color = if (b == "SV") OnAccent else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .background(if (b == "SV") Accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                            )
                        }
                        Text(e.group, color = Muted, fontSize = 13.sp)
                    }
                    if (progress != null) {
                        Spacer(Modifier.height(12.dp))
                        ProgressBar(progress, Modifier.width(320.dp))
                    }
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (progress != null) {
                            PillButton("Fortsätt · ${progress.minutesLeft} min kvar", primary = true, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus)) {
                                onPlay(e, true)
                            }
                            PillButton("Från början", primary = false, icon = Icons.Rounded.Replay) { onPlay(e, false) }
                        } else {
                            PillButton("Spela", primary = true, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus)) { onPlay(e, false) }
                        }
                        extras?.trailerKey?.let { key -> PillButton("Trailer", primary = false, icon = Icons.Rounded.Movie) { openTrailer(context, key) } }
                        PillButton(if (isFav) "Favorit" else "Lägg till favorit", primary = false, icon = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { Store.toggleFavorite(e) }
                        if (progress != null) {
                            PillButton("Ta bort", primary = false, icon = Icons.Rounded.Close) { Store.removeProgress(e.url) }
                        }
                    }
                    val plot = details?.plot.orEmpty()
                    if (plot.isNotBlank()) {
                        Spacer(Modifier.height(24.dp))
                        Text(plot, color = TextBright, fontSize = 16.sp, lineHeight = 23.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val cast = extras?.cast.orEmpty()
            if (cast.isNotEmpty()) {
                SectionTitle("Skådespelare")
                CastRow(cast)
            }
            if (similar.isNotEmpty()) {
                SectionTitle("Mer som detta")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(start = 4.dp, end = 48.dp, top = 6.dp, bottom = 6.dp)) {
                    items(similar) { m ->
                        PosterCard(m.title, m.logo, null, Modifier.width(140.dp), badges = entryBadges(m)) { onOpen(Detail.Movie(m)) }
                    }
                }
            }
        }
    }
}

/** Rund bild och namn för varje skådespelare (från TMDB). */
@Composable
private fun CastRow(cast: List<Tmdb.Person>) {
    val context = LocalContext.current
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(start = 4.dp, end = 48.dp)) {
        items(cast) { p ->
            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(88.dp).clip(androidx.compose.foundation.shape.CircleShape).background(CardBg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.photo != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(p.photo).size(200, 200).build(),
                            contentDescription = p.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text(p.name.take(1), color = Muted, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(p.name, color = TextBright, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (p.role.isNotBlank()) Text(p.role, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Öppnar trailern i YouTube-appen. */
internal fun openTrailer(context: android.content.Context, key: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$key"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "YouTube finns inte på TV:n", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun ProgressBar(p: Progress, modifier: Modifier) {
    Box(modifier.height(5.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p.fraction).background(Accent, RoundedCornerShape(3.dp)))
    }
}

// ---------------------------------------------------------------- Serie

@Composable
internal fun SeriesDetail(series: Series, onPlay: (Entry, Boolean) -> Unit) {
    val history by Store.history.collectAsState()
    val favorites by Store.favSeries.collectAsState()
    val isFav = favorites.any { (it.xtreamId ?: it.name) == (series.xtreamId ?: series.name) }

    var details by remember(series) { mutableStateOf<Details?>(null) }
    val tmdbKey by Store.tmdbKey.collectAsState()
    val context = LocalContext.current
    val extras by produceState<Tmdb.Extras?>(null, series, tmdbKey, details) {
        value = runCatching { Tmdb.extras(tmdbKey, true, series.tmdb ?: details?.tmdbId, series.name) }.getOrNull()
    }
    var episodes by remember(series) { mutableStateOf(series.episodes) }
    var error by remember(series) { mutableStateOf<String?>(null) }
    LaunchedEffect(series) {
        val acc = Repo.account
        if (series.xtreamId != null && acc != null) {
            try {
                val (d, eps) = Xtream.seriesInfo(acc, series)
                details = d
                episodes = eps
                if (eps.isEmpty()) error = "Inga avsnitt hittades."
            } catch (e: Exception) {
                error = "Kunde inte hämta avsnitten: ${e.message}"
            }
        } else if (episodes.isEmpty()) {
            // Favorit från en M3U-lista: leta upp avsnitten i biblioteket igen
            val lib = (Repo.state.value as? LoadState.Ready)?.library
            episodes = lib?.allSeries?.firstOrNull { it.name == series.name && it.group == series.group }?.episodes.orEmpty()
            if (episodes.isEmpty()) error = "Inga avsnitt hittades."
        }
    }

    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val last = history.firstOrNull { it.entry.kind == Kind.EPISODE && it.entry.series == series.name }
    var season by remember(series, seasons) { mutableStateOf(last?.entry?.season?.takeIf { it in seasons } ?: seasons.firstOrNull() ?: 0) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(episodes.isNotEmpty()) { if (episodes.isNotEmpty()) runCatching { firstFocus.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(details?.backdrop ?: series.logo)
        Column(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 40.dp)) {
            Text(displayTitle(series.name), color = Color.White, fontSize = 42.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            MetaLine(details)
            val castNames = extras?.cast.orEmpty().take(6).joinToString(", ") { it.name }
            if (castNames.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Med: $castNames", color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val plot = details?.plot.orEmpty()
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(plot, color = TextBright, fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (last != null) {
                    PillButton(
                        (if (last.upNext) "Spela nästa: S%02d E%02d" else "Fortsätt S%02d E%02d")
                            .format(last.entry.season, last.entry.episode),
                        primary = true,
                        icon = Icons.Rounded.PlayArrow,
                        modifier = Modifier.focusRequester(firstFocus),
                    ) { onPlay(last.entry, true) }
                } else if (episodes.isNotEmpty()) {
                    PillButton("Spela första avsnittet", primary = true, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(firstFocus)) {
                        onPlay(episodes.first(), false)
                    }
                }
                extras?.trailerKey?.let { key -> PillButton("Trailer", primary = false, icon = Icons.Rounded.Movie) { openTrailer(context, key) } }
                PillButton(if (isFav) "Favorit" else "Lägg till favorit", primary = false, icon = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder) { Store.toggleFavorite(series) }
                if (last != null) {
                    PillButton("Ta bort från Fortsätt titta", primary = false, icon = Icons.Rounded.Close) {
                        Store.removeSeries(series.name, series.xtreamId)
                    }
                }
            }

            val err = error
            when {
                err != null -> Message(err)
                episodes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Accent)
                }
                else -> {
                    if (seasons.size > 1) {
                        Spacer(Modifier.height(18.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 4.dp)) {
                            items(seasons) { s ->
                                FocusBox(onClick = { season = s }, shape = RoundedCornerShape(50)) { focused ->
                                    Text(
                                        if (s > 0) "Säsong $s" else "Övrigt",
                                        modifier = Modifier
                                            .background(
                                                when {
                                                    focused -> Color.White
                                                    s == season -> Accent
                                                    else -> Color.White.copy(alpha = 0.12f)
                                                }
                                            )
                                            .padding(horizontal = 18.dp, vertical = 8.dp),
                                        color = if (focused || s == season) Color.Black else Color.White,
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    val shown = episodes.filter { seasons.size <= 1 || it.season == season }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp, top = 4.dp), modifier = Modifier.fillMaxSize()) {
                        items(shown) { e ->
                            val p = history.firstOrNull { it.entry.url == e.url }
                            FocusBox(onClick = { onPlay(e, true) }, modifier = Modifier.fillMaxWidth(), scale = 1.02f) { focused ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(if (focused) CardBg else Color.Black.copy(alpha = 0.35f))
                                        .padding(horizontal = 18.dp, vertical = 12.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (e.season > 0) "S%02d E%02d".format(e.season, e.episode) else "",
                                            color = Accent,
                                            modifier = Modifier.width(110.dp),
                                        )
                                        Text(e.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                        if (p != null && p.upNext) Text("Nästa", color = Accent, fontSize = 13.sp)
                                        if (p != null && !p.upNext) Text("${p.minutesLeft} min kvar", color = Muted, fontSize = 13.sp)
                                    }
                                    if (p != null && !p.upNext) {
                                        Spacer(Modifier.height(8.dp))
                                        ProgressBar(p, Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
