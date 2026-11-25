package se.pontus.glimt

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FAVORITES = "★ Favoritkanaler"

/** Live-TV: kategorier till vänster, kanaler med "nu" och "sedan" till höger. */
@Composable
internal fun LiveScreen(lib: Library, onPlay: (Entry) -> Unit) {
    val favs by Store.favChannels.collectAsState()
    val groups = remember(lib, favs.isNotEmpty()) {
        (if (favs.isNotEmpty()) listOf(FAVORITES) else emptyList()) + lib.liveGroups
    }
    if (groups.isEmpty()) {
        Message("Inga kanaler hittades i spellistan.\nDolda språk och vuxeninnehåll gäller även här.")
        return
    }
    var selected by rememberSaveable { mutableStateOf(groups.first()) }
    if (selected !in groups) selected = groups.first()
    val channels = if (selected == FAVORITES) favs else lib.live[selected].orEmpty()

    Row(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight()) {
            Text("Live-TV", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(groups) { g ->
                    val active = g == selected
                    FocusBox(onClick = { selected = g }, modifier = Modifier.fillMaxWidth(), scale = 1.03f, glow = false) { focused ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        focused -> Color.White
                                        active -> CardBg
                                        else -> Color.Transparent
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                g,
                                color = if (focused) OnAccent else if (active) Color.White else TextBright,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            val n = if (g == FAVORITES) favs.size else lib.live[g]?.size ?: 0
                            Text("$n", color = if (focused) OnAccent else Muted, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(
                "$selected · ${channels.size} kanaler · Håll in OK för favorit",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 14.dp, bottom = 14.dp),
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(end = 32.dp, bottom = 48.dp),
            ) {
                itemsIndexed(channels) { i, e ->
                    ChannelRow(
                        number = i + 1,
                        e = e,
                        favorite = favs.any { it.url == e.url },
                        onClick = { onPlay(e) },
                        onLongClick = { Store.toggleFavoriteChannel(e) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(number: Int, e: Entry, favorite: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val epg by produceState<List<Xtream.Program>>(emptyList(), e.url) {
        val acc = Repo.account
        val id = e.xtreamId
        if (acc != null && id != null) value = runCatching { Xtream.shortEpg(acc, id) }.getOrDefault(emptyList())
    }
    val now = System.currentTimeMillis()
    val current = epg.firstOrNull { it.start <= now && it.end > now }
    val upcoming = epg.firstOrNull { it.start > now }
    val hm = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    FocusBox(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth(), scale = 1.02f, glow = false) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) CardBg else Surface1)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$number", color = Muted, fontSize = 15.sp, modifier = Modifier.width(44.dp))
            Box(
                Modifier.width(96.dp).height(54.dp).background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (e.logo != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(e.logo).size(200, 112).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(e.title.take(3), color = Muted, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (favorite) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Bad, modifier = Modifier.size(16.dp))
                    }
                }
                if (current != null) {
                    Text(
                        "${hm.format(Date(current.start))}  ${current.title}",
                        color = TextBright,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    val f = ((now - current.start).toFloat() / (current.end - current.start)).coerceIn(0f, 1f)
                    Box(Modifier.width(260.dp).height(3.dp).background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(AccentBrush, RoundedCornerShape(2.dp)))
                    }
                }
            }
            if (upcoming != null) {
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(280.dp)) {
                    Text("Sedan ${hm.format(Date(upcoming.start))}", color = Muted, fontSize = 12.sp)
                    Text(upcoming.title, color = Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
