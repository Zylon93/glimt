package se.pontus.glimt

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import kotlinx.coroutines.delay

@Composable
internal fun HomeScreen(
    lib: Library,
    onPlay: (Entry, Boolean) -> Unit,
    onOpen: (Detail) -> Unit,
    onSection: (Section) -> Unit,
) {
    val history by Store.history.collectAsState()
    val favMovies by Store.favMovies.collectAsState()
    val favSeries by Store.favSeries.collectAsState()
    val featured = remember(lib) { lib.newestMovies.filter { it.logo != null }.take(8) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 40.dp),
    ) {
        if (featured.isNotEmpty()) {
            item { Hero(featured, onPlay, onOpen) }
        }
        if (history.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 28.dp)) {
                    SectionTitle("Fortsätt titta", "Håll in OK för att ta bort")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 6.dp)) {
                        items(history.take(20)) { p ->
                            ContinueCard(p, Modifier.width(260.dp)) { onPlay(p.entry, true) }
                        }
                    }
                }
            }
        }
        if (favMovies.isNotEmpty() || favSeries.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 28.dp)) {
                    SectionTitle("Favoriter")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 6.dp)) {
                        items(favSeries) { s ->
                            PosterCard(s.name, s.logo, "Serie", Modifier.width(140.dp)) { onOpen(Detail.Show(s)) }
                        }
                        items(favMovies) { e ->
                            PosterCard(e.title, e.logo, null, Modifier.width(140.dp)) { onOpen(Detail.Movie(e)) }
                        }
                    }
                }
            }
        }
        if (lib.newestSeries.isNotEmpty()) {
            item {
                Column(Modifier.padding(start = 28.dp)) {
                    SectionTitle("Nyligen tillagda serier")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 6.dp)) {
                        items(lib.newestSeries.take(40)) { s ->
                            PosterCard(s.name, s.logo, null, Modifier.width(140.dp), favorite = favSeries.any { (it.xtreamId ?: it.name) == (s.xtreamId ?: s.name) }) {
                                onOpen(Detail.Show(s))
                            }
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(start = 28.dp)) {
                SectionTitle("Nyligen tillagda filmer")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(end = 32.dp, top = 6.dp, bottom = 6.dp)) {
                    items(lib.newestMovies.take(40)) { e ->
                        PosterCard(e.title, e.logo, null, Modifier.width(140.dp), favorite = favMovies.any { it.url == e.url }) {
                            onOpen(Detail.Movie(e))
                        }
                    }
                }
            }
        }
    }
}

/** Stor bild överst som byter film var nionde sekund. */
@Composable
private fun Hero(featured: List<Entry>, onPlay: (Entry, Boolean) -> Unit, onOpen: (Detail) -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var focusedInside by remember { mutableStateOf(false) }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }
    LaunchedEffect(featured) {
        while (true) {
            delay(9_000)
            if (!focusedInside) index = (index + 1) % featured.size
        }
    }
    val e = featured[index % featured.size]
    val details by produceState<Details?>(null, e.url) {
        value = null
        val acc = Repo.account
        val id = e.xtreamId
        if (acc != null && id != null) value = runCatching { Xtream.vodInfo(acc, id) }.getOrNull()
    }
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val subs = rememberSubState(e, prefs)

    Box(Modifier.fillMaxWidth().height(500.dp)) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(details?.backdrop ?: e.logo).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Bg, 0.45f to Bg.copy(alpha = 0.75f), 1f to Color.Transparent)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg)
            )
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 40.dp, top = 84.dp)
                .widthIn(max = 620.dp)
                .onFocusChanged { focusedInside = it.hasFocus },
        ) {
            Text(
                "NYTT I BIBLIOTEKET",
                color = Muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.7.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                displayTitle(e.title),
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 52.sp,
            )
            Spacer(Modifier.height(8.dp))
            MetaLine(details)
            if (subs != null) {
                Spacer(Modifier.height(12.dp))
                SubtitleBadge(subs, prefs.subLang)
            }
            val plot = details?.plot.orEmpty()
            if (plot.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(plot, color = TextBright, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("Spela", primary = true, icon = Icons.Rounded.PlayArrow, modifier = Modifier.focusRequester(playFocus)) { onPlay(e, true) }
                PillButton("Mer info", primary = false, icon = Icons.Rounded.Info) { onOpen(Detail.Movie(e)) }
            }
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            featured.indices.forEach { i ->
                Box(
                    Modifier
                        .size(width = if (i == index % featured.size) 22.dp else 7.dp, height = 7.dp)
                        .background(
                            if (i == index % featured.size) Accent else Color.White.copy(alpha = 0.3f),
                            CircleShape,
                        )
                )
            }
        }
    }
}

@Composable
internal fun MetaLine(d: Details?) {
    if (d == null) return
    val parts = listOfNotNull(d.year, d.duration, d.genre)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), color = Muted, fontSize = 15.sp)
        if (d.rating != null) {
            if (parts.isNotEmpty()) Spacer(Modifier.width(14.dp))
            Row(
                Modifier.background(Accent.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Star, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(d.rating, color = Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
