package se.pontus.glimt

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = CardBg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(prefs) }
            }
        }
    }
}

internal enum class Section(val label: String, val icon: String) {
    HOME("Hem", "⌂"),
    MOVIES("Filmer", "▶"),
    SERIES("Serier", "▤"),
    FAVORITES("Favoriter", "♥"),
    HISTORY("Historik", "↺"),
    SETTINGS("Inställningar", "⚙"),
}

@Composable
private fun App(prefs: Prefs) {
    val activity = LocalContext.current
    val appContext = activity.applicationContext
    val scope = rememberCoroutineScope()
    val state by Repo.state.collectAsState()
    var section by rememberSaveable { mutableStateOf(if (prefs.m3uUrl.isBlank()) Section.SETTINGS else Section.HOME) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    val play: (Entry, Boolean) -> Unit = { e, resume -> activity.startActivity(PlayerActivity.intent(activity, e, resume)) }
    val open: (Detail) -> Unit = { detail = it }

    LaunchedEffect(Unit) {
        if (Repo.state.value is LoadState.Idle && prefs.m3uUrl.isNotBlank()) {
            Repo.load(appContext, prefs.m3uUrl, forceDownload = false)
        }
    }
    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && section != Section.HOME) { section = Section.HOME }

    // ---- Kraschrapport från förra körningen ----
    val crashFile = remember { java.io.File(appContext.filesDir, GlimtApp.CRASH_FILE) }
    var crash by remember { mutableStateOf(crashFile.takeIf { it.exists() }?.readText()) }
    crash?.let { report ->
        AlertDialog(
            onDismissRequest = { crashFile.delete(); crash = null },
            confirmButton = { TextButton(onClick = { crashFile.delete(); crash = null }) { Text("Stäng") } },
            title = { Text("Appen kraschade förra gången") },
            text = { Text(report.take(2500), fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
        )
    }

    // ---- Uppdatering från GitHub ----
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { update = runCatching { Updater.check() }.getOrNull() }
    val startUpdate: () -> Unit = {
        val release = update
        if (release != null && updateStatus == null) {
            scope.launch {
                try {
                    updateStatus = "Laddar ner…"
                    val file = Updater.download(appContext, release) { pct -> updateStatus = "Laddar ner $pct %" }
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
                else -> "Ny version ${update?.versionName} finns – knappen ligger längst ner i menyn"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            current = section,
            onSelect = {
                section = it
                detail = null
            },
            updateLabel = update?.let { updateStatus ?: "⬆  Uppdatera till ${it.versionName}" },
            onUpdate = startUpdate,
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val d = detail
            when {
                d is Detail.Movie -> MovieDetail(d.entry, play)
                d is Detail.Show -> SeriesDetail(d.series, play)
                section == Section.SETTINGS -> SettingsScreen(prefs, onCheckUpdate = checkUpdate) {
                    scope.launch { Repo.load(appContext, prefs.m3uUrl, forceDownload = true) }
                    section = Section.HOME
                }
                else -> when (val s = state) {
                    LoadState.Idle -> Message("Lägg in din M3U-länk under Inställningar.")
                    is LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Accent)
                            Spacer(Modifier.height(16.dp))
                            Text(s.message, color = TextBright)
                        }
                    }
                    is LoadState.Failed -> Message("Kunde inte ladda spellistan:\n${s.message}")
                    is LoadState.Ready -> key(section) {
                        when (section) {
                            Section.HOME -> HomeScreen(s.library, play, open) { section = it }
                            Section.MOVIES -> Browser(s.library, seriesMode = false, onOpen = open)
                            Section.SERIES -> Browser(s.library, seriesMode = true, onOpen = open)
                            Section.FAVORITES -> FavoritesScreen(open)
                            Section.HISTORY -> HistoryScreen(play)
                            Section.SETTINGS -> Unit
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Meny

@Composable
private fun Sidebar(current: Section, onSelect: (Section) -> Unit, updateLabel: String?, onUpdate: () -> Unit) {
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(SideBg)
            .padding(horizontal = 14.dp, vertical = 24.dp)
    ) {
        Text(
            "Glimt",
            color = Accent,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 12.dp, bottom = 28.dp),
        )
        Section.entries.forEach { s ->
            val selected = s == current
            FocusBox(onClick = { onSelect(s) }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), scale = 1.03f) { focused ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selected -> Color.White
                                focused -> CardBg
                                else -> Color.Transparent
                            }
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.icon, color = if (selected) Color.Black else Muted, fontSize = 18.sp, modifier = Modifier.width(30.dp))
                    Text(
                        s.label,
                        color = if (selected) Color.Black else TextBright,
                        fontSize = 17.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (updateLabel != null) {
            FocusBox(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) {
                Text(
                    updateLabel,
                    modifier = Modifier.fillMaxWidth().background(Accent).padding(horizontal = 14.dp, vertical = 12.dp),
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Filmer / Serier

@Composable
private fun Browser(lib: Library, seriesMode: Boolean, onOpen: (Detail) -> Unit) {
    val groups = if (seriesMode) lib.seriesGroups else lib.movieGroups
    if (groups.isEmpty()) {
        Message(if (seriesMode) "Inga serier hittades i spellistan." else "Inga filmer hittades i spellistan.")
        return
    }
    val favMovies by Store.favMovies.collectAsState()
    var selected by rememberSaveable(seriesMode) { mutableStateOf(groups.first()) }
    var search by remember { mutableStateOf("") }
    val term = search.trim()

    Row(Modifier.fillMaxSize().padding(top = 24.dp, start = 20.dp)) {
        Column(Modifier.width(270.dp).fillMaxHeight()) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                singleLine = true,
                placeholder = { Text(if (seriesMode) "Sök serie…" else "Sök film…") },
                modifier = Modifier.fillMaxWidth().dpadEscape(),
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                items(groups) { g ->
                    val active = g == selected && term.length < 2
                    FocusBox(onClick = { selected = g; search = "" }, modifier = Modifier.fillMaxWidth(), scale = 1.03f) { focused ->
                        Text(
                            g,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (active) CardBg else if (focused) CardBg.copy(alpha = 0.6f) else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            color = if (focused || active) Color.White else Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        if (seriesMode) {
            val list = remember(selected, term) {
                if (term.length >= 2) lib.allSeries.filter { it.name.contains(term, ignoreCase = true) }.take(500)
                else lib.series[selected].orEmpty()
            }
            Grid(list, emptyText = "Inga serier här.") { s ->
                PosterCard(s.name, s.logo, if (s.xtreamId == null) "${s.episodes.size} avsnitt" else null) { onOpen(Detail.Show(s)) }
            }
        } else {
            val list = remember(selected, term) {
                if (term.length >= 2) lib.allMovies.filter { it.title.contains(term, ignoreCase = true) }.take(500)
                else lib.movies[selected].orEmpty()
            }
            Grid(list, emptyText = "Inga filmer här.") { e ->
                PosterCard(e.title, e.logo, null, favorite = favMovies.any { it.url == e.url }) { onOpen(Detail.Movie(e)) }
            }
        }
    }
}

@Composable
private fun <T> Grid(list: List<T>, emptyText: String, minWidth: Int = 150, card: @Composable (T) -> Unit) {
    if (list.isEmpty()) {
        Message(emptyText)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minWidth.dp),
        contentPadding = PaddingValues(start = 8.dp, end = 28.dp, top = 8.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list) { card(it) }
    }
}

// ---------------------------------------------------------------- Favoriter / Historik

@Composable
private fun FavoritesScreen(onOpen: (Detail) -> Unit) {
    val movies by Store.favMovies.collectAsState()
    val series by Store.favSeries.collectAsState()
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 12.dp)) {
        SectionTitle("Favoriter", "Lägg till med ♡ på en film eller serie")
        val all: List<Detail> = series.map { Detail.Show(it) } + movies.map { Detail.Movie(it) }
        Grid(all, emptyText = "Inga favoriter än.\nÖppna en film eller serie och tryck ♡ Favorit.") { d ->
            when (d) {
                is Detail.Movie -> PosterCard(d.entry.title, d.entry.logo, "Film") { onOpen(d) }
                is Detail.Show -> PosterCard(d.series.name, d.series.logo, "Serie") { onOpen(d) }
            }
        }
    }
}

@Composable
private fun HistoryScreen(onPlay: (Entry, Boolean) -> Unit) {
    val history by Store.history.collectAsState()
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 12.dp)) {
        SectionTitle("Fortsätt titta", "Färdigsedda titlar försvinner automatiskt")
        Grid(history, emptyText = "Du har inte börjat titta på något än.", minWidth = 250) { p ->
            ContinueCard(p) { onPlay(p.entry, true) }
        }
    }
}

// ---------------------------------------------------------------- Inställningar

@Composable
private fun SettingsScreen(prefs: Prefs, onCheckUpdate: () -> Unit, onSaved: () -> Unit) {
    var m3u by remember { mutableStateOf(prefs.m3uUrl) }
    var osKey by remember { mutableStateOf(prefs.osKey) }
    var subdlKey by remember { mutableStateOf(prefs.subdlKey) }
    var lang by remember { mutableStateOf(prefs.subLang) }
    var auto by remember { mutableStateOf(prefs.autoSubs) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 28.dp)
            .widthIn(max = 900.dp)
    ) {
        Text("Inställningar", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Field("M3U-länk", m3u, "http://…/get.php?username=…") { m3u = it }
        Field("OpenSubtitles API-nyckel", osKey, "Gratis: opensubtitles.com → API consumers") { osKey = it }
        Field("SubDL API-nyckel", subdlKey, "Gratis: subdl.com → Account → API") { subdlKey = it }
        Field("Undertextspråk (språkkod)", lang, "sv") { lang = it }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            Switch(checked = auto, onCheckedChange = { auto = it })
            Spacer(Modifier.width(12.dp))
            Text("Hämta undertext automatiskt när något startar", color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                prefs.m3uUrl = m3u
                prefs.osKey = osKey
                prefs.subdlKey = subdlKey
                prefs.subLang = lang.ifBlank { "sv" }
                prefs.autoSubs = auto
                if (prefs.m3uUrl.isNotBlank()) onSaved()
            }) { Text("Spara och ladda om spellistan") }
            Button(onClick = onCheckUpdate) { Text("Sök efter uppdatering") }
        }
        Spacer(Modifier.height(24.dp))
        Text("Version ${Updater.currentVersion}", color = Muted)
        Spacer(Modifier.height(8.dp))
        Text(
            "I spelaren: tryck UPP (eller Meny) för att välja, byta eller stänga av undertext.",
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
