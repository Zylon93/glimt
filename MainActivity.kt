package se.pontus.glimt

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch

private val Accent = Color(0xFF5CC8FF)
private val Bg = Color(0xFF0F1115)
private val CardBg = Color(0xFF1C2028)
private val Muted = Color(0xFF8A93A3)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(prefs) }
            }
        }
    }
}

private enum class Tab(val label: String) { MOVIES("Filmer"), SERIES("Serier"), SETTINGS("Inställningar") }

@Composable
private fun App(prefs: Prefs) {
    val activity = LocalContext.current
    val appContext = activity.applicationContext
    val scope = rememberCoroutineScope()
    val state by Repo.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (prefs.m3uUrl.isBlank()) Tab.SETTINGS else Tab.MOVIES) }
    var openSeries by remember { mutableStateOf<Series?>(null) }
    val play: (Entry) -> Unit = { activity.startActivity(PlayerActivity.intent(activity, it)) }

    LaunchedEffect(Unit) {
        if (Repo.state.value is LoadState.Idle && prefs.m3uUrl.isNotBlank()) {
            Repo.load(appContext, prefs.m3uUrl, forceDownload = false)
        }
    }
    BackHandler(enabled = openSeries != null) { openSeries = null }

    // ---- Automatisk uppdatering från GitHub ----
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { update = runCatching { Updater.check() }.getOrNull() }
    val startUpdate: () -> Unit = {
        val release = update
        if (release != null && updateStatus == null) {
            scope.launch {
                try {
                    updateStatus = "Laddar ner…"
                    val file = Updater.download(appContext, release) { pct -> updateStatus = "Laddar ner… $pct %" }
                    updateStatus = null
                    Updater.install(activity as Activity, file)
                } catch (e: Exception) {
                    updateStatus = null
                    Toast.makeText(activity, "Uppdateringen misslyckades: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val checkUpdate: () -> Unit = {
        scope.launch {
            val result = runCatching { Updater.check() }
            update = result.getOrNull()
            val msg = when {
                result.isFailure -> "Kunde inte kolla efter uppdatering: ${result.exceptionOrNull()?.message}"
                update == null -> "Du har senaste versionen (${Updater.currentVersion})"
                else -> "Ny version ${update?.versionName} finns – tryck på knappen uppe till höger"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 24.dp)) {
        TopBar(tab, onSelect = {
            tab = it
            openSeries = null
        }) {
            val release = update
            if (release != null) {
                FocusBox(onClick = startUpdate) {
                    Text(
                        updateStatus ?: "Uppdatera till ${release.versionName}",
                        modifier = Modifier.background(Accent).padding(horizontal = 18.dp, vertical = 8.dp),
                        color = Color.Black,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val series = openSeries
        when {
            tab == Tab.SETTINGS -> SettingsScreen(prefs, onCheckUpdate = checkUpdate) {
                scope.launch { Repo.load(appContext, prefs.m3uUrl, forceDownload = true) }
                tab = Tab.MOVIES
            }
            series != null -> EpisodeList(series, play)
            else -> when (val s = state) {
                LoadState.Idle -> Message("Lägg in din M3U-länk under Inställningar.")
                is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        Text(s.message, color = Color.White)
                    }
                }
                is LoadState.Failed -> Message("Kunde inte ladda spellistan:\n${s.message}")
                is LoadState.Ready -> key(tab) {
                    Browser(s.library, seriesMode = tab == Tab.SERIES, onPlay = play, onOpenSeries = { openSeries = it })
                }
            }
        }
    }
}

@Composable
private fun TopBar(current: Tab, onSelect: (Tab) -> Unit, trailing: @Composable () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Glimt", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(32.dp))
        Tab.entries.forEachIndexed { i, t ->
            val base = if (i == 0) Modifier.focusRequester(first) else Modifier
            FocusBox(onClick = { onSelect(t) }, modifier = base) { focused ->
                Text(
                    t.label,
                    modifier = Modifier
                        .background(if (t == current) CardBg else Color.Transparent)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    color = if (focused || t == current) Color.White else Muted,
                    fontSize = 18.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun Browser(lib: Library, seriesMode: Boolean, onPlay: (Entry) -> Unit, onOpenSeries: (Series) -> Unit) {
    val groups = if (seriesMode) lib.seriesGroups else lib.movieGroups
    if (groups.isEmpty()) {
        Message(if (seriesMode) "Inga serier hittades i spellistan." else "Inga filmer hittades i spellistan.")
        return
    }
    var selected by remember { mutableStateOf(groups.first()) }
    var search by remember { mutableStateOf("") }
    val term = search.trim()

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text("Sök…") },
                modifier = Modifier.fillMaxWidth().dpadEscape(),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(groups) { g ->
                    val active = g == selected && term.length < 2
                    FocusBox(onClick = { selected = g; search = "" }, modifier = Modifier.fillMaxWidth()) { focused ->
                        Text(
                            g,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (active) CardBg else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = if (focused || active) Color.White else Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(24.dp))
        if (seriesMode) {
            val list = remember(selected, term) {
                if (term.length >= 2) lib.allSeries.filter { it.name.contains(term, ignoreCase = true) }.take(500)
                else lib.series[selected].orEmpty()
            }
            PosterGrid(list, title = { it.name }, logo = { it.logo }, caption = { "${it.episodes.size} avsnitt" }, onClick = onOpenSeries)
        } else {
            val list = remember(selected, term) {
                if (term.length >= 2) lib.allMovies.filter { it.title.contains(term, ignoreCase = true) }.take(500)
                else lib.movies[selected].orEmpty()
            }
            PosterGrid(list, title = { it.title }, logo = { it.logo }, caption = { null }, onClick = onPlay)
        }
    }
}

@Composable
private fun <T> PosterGrid(
    list: List<T>,
    title: (T) -> String,
    logo: (T) -> String?,
    caption: (T) -> String?,
    onClick: (T) -> Unit,
) {
    if (list.isEmpty()) {
        Message("Inget att visa här.")
        return
    }
    val context = LocalContext.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list) { item ->
            Column {
                FocusBox(onClick = { onClick(item) }, modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
                    Box(Modifier.fillMaxSize().background(CardBg), contentAlignment = Alignment.Center) {
                        val url = logo(item)
                        if (url != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(url).size(300, 450).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(title(item), Modifier.padding(8.dp), textAlign = TextAlign.Center, color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(title(item), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = Color(0xFFD8DEE8))
                caption(item)?.let { Text(it, fontSize = 11.sp, color = Muted) }
            }
        }
    }
}

@Composable
private fun EpisodeList(series: Series, onPlay: (Entry) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(series) { runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Text(series.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Text("${series.episodes.size} avsnitt · Tillbaka-knappen går ur serien", color = Muted)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(series.episodes) { i, e ->
                val label = if (e.season > 0) "S%02d E%02d".format(e.season, e.episode) else ""
                val base = if (i == 0) Modifier.focusRequester(first) else Modifier
                FocusBox(onClick = { onPlay(e) }, modifier = base.fillMaxWidth()) { focused ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (focused) CardBg else Color(0xFF161A21))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(label, color = Accent, modifier = Modifier.width(110.dp))
                        Text(e.title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(prefs: Prefs, onCheckUpdate: () -> Unit, onSaved: () -> Unit) {
    var m3u by remember { mutableStateOf(prefs.m3uUrl) }
    var osKey by remember { mutableStateOf(prefs.osKey) }
    var subdlKey by remember { mutableStateOf(prefs.subdlKey) }
    var lang by remember { mutableStateOf(prefs.subLang) }
    var auto by remember { mutableStateOf(prefs.autoSubs) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).widthIn(max = 900.dp)) {
        Field("M3U-länk", m3u, "http://…") { m3u = it }
        Field("OpenSubtitles API-nyckel", osKey, "Gratis: opensubtitles.com → API consumers") { osKey = it }
        Field("SubDL API-nyckel", subdlKey, "Gratis: subdl.com → Account → API") { subdlKey = it }
        Field("Undertextspråk (språkkod)", lang, "sv") { lang = it }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Switch(checked = auto, onCheckedChange = { auto = it })
            Spacer(Modifier.width(12.dp))
            Text("Hämta undertext automatiskt när något startar", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = {
            prefs.m3uUrl = m3u
            prefs.osKey = osKey
            prefs.subdlKey = subdlKey
            prefs.subLang = lang.ifBlank { "sv" }
            prefs.autoSubs = auto
            if (prefs.m3uUrl.isNotBlank()) onSaved()
        }) { Text("Spara och ladda om spellistan") }
        Spacer(Modifier.height(12.dp))
        Button(onClick = onCheckUpdate) { Text("Sök efter uppdatering") }
        Spacer(Modifier.height(24.dp))
        Text("Version ${Updater.currentVersion}", color = Muted)
        Spacer(Modifier.height(8.dp))
        Text(
            "I spelaren: tryck UPP (eller Meny) för att välja, byta eller stänga av undertext.\n" +
                "Bara filmer och serier läses in – live-kanaler hoppas över, så även stora listor går snabbt.",
            color = Muted,
        )
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).dpadEscape(),
    )
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White, textAlign = TextAlign.Center, fontSize = 18.sp)
    }
}

/** Ett fokuserbart kort som lyser upp när fjärrkontrollen står på det. */
@Composable
private fun FocusBox(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    content: @Composable (focused: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    Box(
        modifier
            .graphicsLayer {
                val s = if (focused) 1.05f else 1f
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .then(if (focused) Modifier.border(3.dp, Accent, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
    ) { content(focused) }
}

/** Låter fjärrkontrollens upp/ner flytta fokus ut ur textfält. */
@Composable
private fun Modifier.dpadEscape(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
