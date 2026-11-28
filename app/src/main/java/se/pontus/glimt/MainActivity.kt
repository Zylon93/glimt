@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package se.pontus.glimt

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
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
            GlimtTheme {
                Surface(Modifier.fillMaxSize(), color = Bg) { App(prefs) }
            }
        }
    }
}

internal enum class Section(val label: String, val icon: ImageVector) {
    HOME("Hem", Icons.Rounded.Home),
    SEARCH("Sök", Icons.Rounded.Search),
    MOVIES("Filmer", Icons.Rounded.Movie),
    SERIES("Serier", Icons.Rounded.Tv),
    LIVE("Live-TV", Icons.Rounded.LiveTv),
    FAVORITES("Favoriter", Icons.Rounded.Favorite),
    HISTORY("Historik", Icons.Rounded.History),
    SETTINGS("Inställningar", Icons.Rounded.Settings),
}

@Composable
private fun App(prefs: Prefs) {
    val activity = LocalContext.current
    val appContext = activity.applicationContext
    val scope = rememberCoroutineScope()
    val state by Repo.state.collectAsState()
    var section by rememberSaveable { mutableStateOf(if (prefs.m3uUrl.isBlank()) Section.SETTINGS else Section.HOME) }
    var detail by remember { mutableStateOf<Detail?>(null) }
    var showCategories by remember { mutableStateOf(false) }
    val hidden by Store.hidden.collectAsState()
    val showAdult by Store.showAdult.collectAsState()
    val genreMode by Store.genreMode.collectAsState()
    val tmdbKey by Store.tmdbKey.collectAsState()
    val tmdbVersion by Tmdb.version.collectAsState()

    // Biblioteket utan dolda kategorier, och samma bibliotek grupperat efter genre
    val readyLib = (state as? LoadState.Ready)?.library
    val filteredLib = remember(readyLib, hidden, showAdult) { readyLib?.filtered(hidden, showAdult) }
    LaunchedEffect(filteredLib, tmdbKey, genreMode) {
        kotlinx.coroutines.delay(1500) // vänta tills man skrivit klart nyckeln
        if (filteredLib != null && genreMode) Tmdb.start(appContext, filteredLib, tmdbKey)
    }
    androidx.compose.runtime.SideEffect { Current.library = filteredLib }
    // Nya avsnitt i serier man sett ikapp: kolla när listan är inläst och sedan var sjätte timme
    LaunchedEffect(readyLib != null) {
        if (readyLib == null) return@LaunchedEffect
        while (true) {
            runCatching { NewEpisodes.checkAll() }
            kotlinx.coroutines.delay(6 * 60 * 60 * 1000L)
        }
    }
    val genreLib by androidx.compose.runtime.produceState<Library?>(null, filteredLib, tmdbVersion, genreMode) {
        val l = filteredLib
        value = if (l != null && genreMode) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Genres.build(l) } else null
    }
    androidx.compose.runtime.SideEffect { Current.genreLibrary = genreLib }
    val play: (Entry, Boolean) -> Unit = { e, resume ->
        // Startas ett avsnitt utanför seriens sida (t.ex. från Fortsätt titta) öppnas serien
        // bakom spelaren, så att Tillbaka leder dit i stället för till startsidan.
        val series = e.series
        if (e.kind == Kind.EPISODE && series != null && detail !is Detail.Show) {
            detail = Detail.Show(Series(series, e.group, e.logo, emptyList(), e.xtreamId))
        }
        activity.startActivity(PlayerActivity.intent(activity, e, resume))
    }
    val open: (Detail) -> Unit = { detail = it }

    LaunchedEffect(Unit) {
        if (Repo.state.value is LoadState.Idle && prefs.m3uUrl.isNotBlank()) {
            Repo.load(appContext, prefs.m3uUrl, forceDownload = false)
        }
    }
    BackHandler(enabled = detail != null) { detail = null }
    BackHandler(enabled = detail == null && section != Section.HOME && !showCategories) { section = Section.HOME }
    BackHandler(enabled = showCategories) { showCategories = false }

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
                else -> "Ny version ${update?.versionName} finns – knappen ligger uppe i menyn"
            }
            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
        }
    }

    // Bakgrund med ett svagt guldskimmer uppe till vänster
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .background(
                Brush.radialGradient(
                    listOf(Accent.copy(alpha = 0.07f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, 0f),
                    radius = 1600f,
                )
            )
    ) {
        // Startsidans hero ligger under toppmenyn, övriga skärmar börjar nedanför den
        val heroUnderBar = detail == null && section == Section.HOME && state is LoadState.Ready
        val contentFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = if (heroUnderBar) 0.dp else TopBarHeight)
                // Ner från toppmenyn går alltid hit. (focusRestorer kraschade med
                // "Release should only be called once" i den här Compose-versionen.)
                .focusRequester(contentFocus)
                .focusGroup()
        ) {
            val d = detail
            when {
                d is Detail.Movie -> MovieDetail(d.entry, play, open)
                d is Detail.Show -> SeriesDetail(d.series, play)
                section == Section.SETTINGS && showCategories ->
                    CategoryScreen((state as? LoadState.Ready)?.library)
                section == Section.SETTINGS -> SettingsScreen(prefs, hiddenCount = hidden.size, onCheckUpdate = checkUpdate, onCategories = { showCategories = true }) {
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
                        val lib = filteredLib ?: s.library
                        val browseLib = genreLib ?: lib
                        when (section) {
                            Section.HOME -> HomeScreen(lib, play, open) { section = it }
                            Section.SEARCH -> SearchScreen(lib, onOpen = open)
                            Section.MOVIES -> Browser(browseLib, seriesMode = false, isGenre = genreLib != null, onOpen = open)
                            Section.SERIES -> Browser(browseLib, seriesMode = true, isGenre = genreLib != null, onOpen = open)
                            Section.LIVE -> LiveScreen(lib) { e -> play(e, false) }
                            Section.FAVORITES -> FavoritesScreen(open)
                            Section.HISTORY -> HistoryScreen(play)
                            Section.SETTINGS -> Unit
                        }
                    }
                }
            }
        }
        TopBar(
            contentFocus = contentFocus,
            current = section,
            onSelect = {
                section = it
                detail = null
                showCategories = false
            },
            updateLabel = update?.let { updateStatus ?: "Uppdatera till ${it.versionName}" },
            onUpdate = startUpdate,
        )
    }
}

private val TopBarHeight = 92.dp

// ---------------------------------------------------------------- Meny

/** Flikar överst: vald flik har en guldlinje, fokus ger vit fyllning. */
@Composable
private fun TopBar(
    contentFocus: androidx.compose.ui.focus.FocusRequester,
    current: Section,
    onSelect: (Section) -> Unit,
    updateLabel: String?,
    onUpdate: () -> Unit,
) {
    val clock by androidx.compose.runtime.produceState(now()) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = now()
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .focusProperties { down = contentFocus }
            // Mörkt överst så att innehåll som skrollar under menyn inte krockar med texten
            .background(Brush.verticalGradient(listOf(Bg.copy(alpha = 0.92f), Bg.copy(alpha = 0.6f), Color.Transparent)))
            .padding(start = 40.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Glimt", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(".", color = Accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(36.dp))
        Section.entries.filter { it != Section.SETTINGS }.forEach { s ->
            TopTab(s.label, selected = s == current) { onSelect(s) }
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        if (updateLabel != null) {
            FocusBox(onClick = onUpdate, shape = RoundedCornerShape(50), scale = 1.05f, glow = false) {
                Row(
                    Modifier.background(AccentBrush).padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = OnAccent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(updateLabel, color = OnAccent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            Spacer(Modifier.width(18.dp))
        }
        Text(clock, color = Muted, fontSize = 17.sp)
        Spacer(Modifier.width(14.dp))
        val settingsSelected = current == Section.SETTINGS
        FocusBox(onClick = { onSelect(Section.SETTINGS) }, shape = RoundedCornerShape(50), scale = 1.08f, glow = false) { focused ->
            Box(
                Modifier
                    .size(46.dp)
                    .background(if (focused) Color.White else if (settingsSelected) Color.White.copy(alpha = 0.12f) else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = Section.SETTINGS.label,
                    tint = when {
                        focused -> OnAccent
                        settingsSelected -> Accent
                        else -> Muted
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}

@Composable
private fun TopTab(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusBox(onClick = onClick, shape = RoundedCornerShape(50), scale = 1.05f, glow = false) { focused ->
        Column(
            Modifier
                .then(if (focused) Modifier.background(Color.White) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                color = when {
                    focused -> OnAccent
                    selected -> Color.White
                    else -> Muted
                },
                fontSize = 18.sp,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier
                    .width(22.dp)
                    .height(3.dp)
                    .background(if (selected && !focused) Accent else Color.Transparent, RoundedCornerShape(2.dp))
            )
        }
    }
}

private fun now(): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale("sv", "SE")).format(java.util.Date())

// ---------------------------------------------------------------- Filmer / Serier

/** Filmer eller serier: en vågrät rad per kategori (som på Netflix), "Visa alla" öppnar hela kategorin. */
@Composable
private fun Browser(rawLib: Library, seriesMode: Boolean, isGenre: Boolean, onOpen: (Detail) -> Unit) {
    val sort by Store.sort.collectAsState()
    val lib = remember(rawLib, sort) { rawLib.sortedFor(sort) }
    val groups = if (seriesMode) lib.seriesGroups else lib.movieGroups
    if (groups.isEmpty()) {
        Message(if (seriesMode) "Inga serier hittades i spellistan." else "Inga filmer hittades i spellistan.")
        return
    }
    val favMovies by Store.favMovies.collectAsState()
    val favSeries by Store.favSeries.collectAsState()
    var openGroup by rememberSaveable(seriesMode) { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val term = search.trim()
    BackHandler(enabled = openGroup != null || term.isNotEmpty()) {
        if (term.isNotEmpty()) search = "" else openGroup = null
    }
    val movieCard: @Composable (Entry, Modifier) -> Unit = { e, m ->
        PosterCard(e.title, e.logo, null, m, badges = entryBadges(e), favorite = favMovies.any { it.url == e.url }) { onOpen(Detail.Movie(e)) }
    }
    val seriesCard: @Composable (Series, Modifier) -> Unit = { s, m ->
        PosterCard(
            s.name, s.logo, if (s.xtreamId == null) "${s.episodes.size} avsnitt" else null, m,
            badges = seriesBadges(s),
            favorite = favSeries.any { (it.xtreamId ?: it.name) == (s.xtreamId ?: s.name) },
        ) { onOpen(Detail.Show(s)) }
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 32.dp)) {
            Text(
                openGroup ?: if (seriesMode) "Serier" else "Filmer",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            // Sortering: OK växlar Nyast → A–Ö → År → Betyg
            FocusBox(onClick = { Store.setSort(Sort.entries[(sort.ordinal + 1) % Sort.entries.size]) }, shape = RoundedCornerShape(50), glow = false) { focused ->
                Row(
                    Modifier
                        .background(if (focused) Color.White else Surface1)
                        .padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, tint = if (focused) OnAccent else Accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sortera: ${sort.label}", color = if (focused) OnAccent else Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.width(14.dp))
            GlimtTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = if (seriesMode) "Sök serie…" else "Sök film…",
                leading = Icons.Rounded.Search,
                modifier = Modifier.width(380.dp),
            )
        }
        if (isGenre) {
            val progress by Tmdb.progress.collectAsState()
            val key by Store.tmdbKey.collectAsState()
            val p = progress
            val info = when {
                p?.error != null -> p.error + " – kontrollera den under Inställningar."
                p != null -> "Sorterar efter genre i bakgrunden… ${p.done} / ${p.total} (${if (p.total > 0) p.done * 100 / p.total else 0} %)"
                key.isBlank() && Genres.OTHER in groups -> "Titlar utan genre i kategorinamnet hamnar i Övrigt. Lägg in en gratis TMDB-nyckel under Inställningar så sorteras de också."
                else -> null
            }
            if (info != null) Text(info, color = if (p?.error != null) Bad else Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, end = 32.dp))
        }
        Spacer(Modifier.height(8.dp))
        val open = openGroup
        when {
            term.length >= 2 -> {
                if (seriesMode) {
                    val list = remember(term) { lib.allSeries.filter { it.name.contains(term, ignoreCase = true) }.take(500) }
                    Grid(list, emptyText = "Inga träffar.") { seriesCard(it, Modifier) }
                } else {
                    val list = remember(term) { lib.allMovies.filter { it.title.contains(term, ignoreCase = true) }.take(500) }
                    Grid(list, emptyText = "Inga träffar.") { movieCard(it, Modifier) }
                }
            }
            open != null -> {
                if (seriesMode) Grid(lib.series[open].orEmpty(), emptyText = "Inga serier här.") { seriesCard(it, Modifier) }
                else Grid(lib.movies[open].orEmpty(), emptyText = "Inga filmer här.") { movieCard(it, Modifier) }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(groups, key = { it }) { g ->
                    val count = if (seriesMode) lib.series[g]?.size ?: 0 else lib.movies[g]?.size ?: 0
                    Column {
                        SectionTitle(g, "$count titlar")
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(start = 4.dp, end = 32.dp, top = 6.dp, bottom = 8.dp),
                        ) {
                            if (seriesMode) {
                                items(lib.series[g].orEmpty().take(30)) { seriesCard(it, Modifier.width(140.dp)) }
                            } else {
                                items(lib.movies[g].orEmpty().take(30)) { movieCard(it, Modifier.width(140.dp)) }
                            }
                            item { ShowAllCard(count) { openGroup = g } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowAllCard(count: Int, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.width(140.dp).height(210.dp)) { focused ->
        Column(
            Modifier.fillMaxSize().background(if (focused) Color.White else Surface1),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = if (focused) OnAccent else Accent, modifier = Modifier.size(40.dp))
            Text("Visa alla", color = if (focused) OnAccent else Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("$count st", color = if (focused) OnAccent.copy(alpha = 0.7f) else Muted, fontSize = 13.sp)
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

// ---------------------------------------------------------------- Sök

private sealed interface Hit {
    data class M(val e: Entry) : Hit
    data class S(val s: Series) : Hit
}

/** Sök bland alla filmer och serier samtidigt. Körs i bakgrunden så att tangentbordet inte hackar. */
@Composable
private fun SearchScreen(lib: Library, onOpen: (Detail) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var counts by remember { mutableStateOf(0 to 0) }
    var searching by remember { mutableStateOf(false) }
    val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    val context = LocalContext.current
    // Röstsökning via Googles taligenkänning (finns på Google TV)
    val voice = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val text = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!text.isNullOrBlank()) query = text
    }
    val startVoice = {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "sv-SE")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Säg en film eller serie")
        try {
            voice.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, "Röstsökning finns inte på den här enheten", Toast.LENGTH_LONG).show()
        }
    }
    LaunchedEffect(query) {
        val term = query.trim()
        if (term.length < 2) {
            hits = emptyList()
            counts = 0 to 0
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        searching = true
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val words = term.lowercase().split(' ').filter { it.isNotBlank() }
            fun matches(t: String): Boolean {
                val l = t.lowercase()
                return words.all { it in l }
            }
            val series = lib.allSeries.filter { matches(it.name) }
            val movies = lib.allMovies.filter { matches(it.title) }
            // Titlar som börjar med söktermen först
            val first = words.first()
            val s = series.sortedBy { if (displayTitle(it.name).lowercase().startsWith(first)) 0 else 1 }
            val m = movies.sortedBy { if (displayTitle(it.title).lowercase().startsWith(first)) 0 else 1 }
            Triple(s.take(150).map { Hit.S(it) } + m.take(300).map { Hit.M(it) }, series.size, movies.size)
        }
        hits = result.first
        counts = result.third to result.second
        searching = false
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 12.dp)) {
        Text("Sök", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            GlimtTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Sök film eller serie…",
                leading = Icons.Rounded.Search,
                modifier = Modifier.widthIn(max = 760.dp).weight(1f, fill = false).width(760.dp),
                focusRequester = fieldFocus,
            )
            Spacer(Modifier.width(14.dp))
            PillButton("Prata", primary = true, icon = Icons.Rounded.Mic) { startVoice() }
        }
        Spacer(Modifier.height(8.dp))
        val (movieCount, seriesCount) = counts
        Text(
            when {
                query.trim().length < 2 -> "Skriv minst två tecken, eller tryck Prata och säg titeln."
                searching -> "Söker…"
                else -> "$movieCount filmer · $seriesCount serier"
            },
            color = Muted,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(10.dp))
        if (hits.isNotEmpty()) {
            Grid(hits, emptyText = "") { h ->
                when (h) {
                    is Hit.S -> PosterCard(h.s.name, h.s.logo, "Serie", badges = seriesBadges(h.s)) { onOpen(Detail.Show(h.s)) }
                    is Hit.M -> PosterCard(h.e.title, h.e.logo, h.e.group, badges = entryBadges(h.e)) { onOpen(Detail.Movie(h.e)) }
                }
            }
        } else if (query.trim().length >= 2 && !searching) {
            Message("Inga träffar på \"${query.trim()}\"")
        }
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
        SectionTitle("Fortsätt titta", "Håll in OK för att ta bort · färdigsedda försvinner automatiskt")
        Grid(history, emptyText = "Du har inte börjat titta på något än.", minWidth = 250) { p ->
            ContinueCard(p) { onPlay(p.entry, true) }
        }
    }
}

// ---------------------------------------------------------------- Dölj kategorier

@Composable
private fun CategoryScreen(lib: Library?) {
    if (lib == null) {
        Message("Spellistan är inte inläst än.")
        return
    }
    val hidden by Store.hidden.collectAsState()
    val cats = remember(lib) {
        (lib.movieGroups.map { it to (lib.movies[it]?.size ?: 0) } +
            lib.seriesGroups.map { it to (lib.series[it]?.size ?: 0) } +
            lib.liveGroups.map { it to (lib.live[it]?.size ?: 0) })
            .groupBy({ it.first }, { it.second })
            .map { (name, sizes) -> name to sizes.sum() }
    }
    val prefixes = remember(cats) {
        cats.groupBy { categoryPrefix(it.first) }
            .map { (p, list) -> p to list.sumOf { it.second } }
            .sortedByDescending { it.second }
    }
    val keep = setOf("SE", "SV", "SWE", "EN", "ENG", "UK", "US", "NORDIC", "Övrigt")

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 40.dp), contentPadding = PaddingValues(vertical = 24.dp)) {
        item {
            Column {
                Text("Dölj språk och kategorier", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dolda kategorier syns inte i Filmer, Serier, Sök eller på startsidan. Tryck OK för att växla. ✓ = visas, ✕ = dold.",
                    color = Muted,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PillButton("Behåll bara svenska och engelska", primary = true) {
                        Store.setHidden(prefixes.map { it.first }.filter { it !in keep }.map { "p:$it" }.toSet())
                    }
                    PillButton("Visa allt", primary = false) { Store.setHidden(emptySet()) }
                }
                SectionTitle("Språk och grupper")
            }
        }
        items(prefixes.chunked(4)) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (p, n) ->
                    Toggle("${prefixLabel(p)} · $n", hidden = "p:$p" in hidden, modifier = Modifier.weight(1f)) {
                        Store.toggleHidden("p:$p")
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item { SectionTitle("Enskilda kategorier") }
        items(cats) { (name, n) ->
            val byPrefix = "p:${categoryPrefix(name)}" in hidden
            Toggle(
                if (byPrefix) "$name · $n   (dold via språkgruppen)" else "$name · $n",
                hidden = byPrefix || "c:$name" in hidden,
                modifier = Modifier.fillMaxWidth(),
            ) { if (!byPrefix) Store.toggleHidden("c:$name") }
        }
    }
}

@Composable
private fun Toggle(label: String, hidden: Boolean, modifier: Modifier, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = modifier.padding(vertical = 3.dp), scale = 1.02f) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) CardBg else Surface1)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (hidden) Icons.Rounded.Close else Icons.Rounded.Check,
                contentDescription = null,
                tint = if (hidden) Bad else Good,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, color = if (hidden) Muted else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Inställningar

@Composable
private fun SettingsScreen(
    prefs: Prefs,
    hiddenCount: Int,
    onCheckUpdate: () -> Unit,
    onCategories: () -> Unit,
    onSaved: () -> Unit,
) {
    var m3u by remember { mutableStateOf(prefs.m3uUrl) }
    var osKey by remember { mutableStateOf(prefs.osKey) }
    var subdlKey by remember { mutableStateOf(prefs.subdlKey) }
    var lang by remember { mutableStateOf(prefs.subLang) }
    var auto by remember { mutableStateOf(prefs.autoSubs) }
    val save = {
        prefs.m3uUrl = m3u
        prefs.osKey = osKey
        prefs.subdlKey = subdlKey
        prefs.subLang = lang.ifBlank { "sv" }
        prefs.autoSubs = auto
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 32.dp)
    ) {
        Text("Inställningar", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Version ${Updater.currentVersion}", color = Muted, fontSize = 15.sp)

        SettingsCard("Spellista") {
            GlimtTextField(m3u, { m3u = it }, label = "M3U- eller Xtream-länk", placeholder = "http://server/get.php?username=…&password=…")
            Spacer(Modifier.height(16.dp))
            PillButton("Spara och ladda om spellistan", primary = true, icon = Icons.Rounded.Refresh) {
                save()
                if (prefs.m3uUrl.isNotBlank()) onSaved()
            }
        }

        SettingsCard("Undertexter") {
            GlimtTextField(osKey, { osKey = it }, label = "OpenSubtitles API-nyckel", placeholder = "Gratis: opensubtitles.com → API consumers")
            Spacer(Modifier.height(14.dp))
            GlimtTextField(subdlKey, { subdlKey = it }, label = "SubDL API-nyckel", placeholder = "Gratis: subdl.com → Account → API")
            Spacer(Modifier.height(14.dp))
            GlimtTextField(lang, { lang = it }, label = "Språk (språkkod)", placeholder = "sv", modifier = Modifier.width(220.dp))
            Spacer(Modifier.height(14.dp))
            SwitchRow("Hämta undertext automatiskt när något startar", auto) {
                auto = !auto
                save()
            }
            Spacer(Modifier.height(8.dp))
            Text("I spelaren: CC eller Meny-knappen byter undertext och justerar synken.", color = Muted, fontSize = 14.sp)
        }

        SettingsCard("Innehåll") {
            val adultOn by Store.showAdult.collectAsState()
            val genresOn by Store.genreMode.collectAsState()
            SwitchRow("Sortera Filmer och Serier efter genre (Skräck, Komedi …)", genresOn) { Store.setGenreMode(!genresOn) }
            SwitchRow("Visa vuxeninnehåll (18+)", adultOn) { Store.setShowAdult(!adultOn) }
            Spacer(Modifier.height(10.dp))
            var tmdb by remember { mutableStateOf(Store.tmdbKey.value) }
            GlimtTextField(
                tmdb,
                { tmdb = it; Store.setTmdbKey(it) },
                label = "TMDB API-nyckel (för genrer)",
                placeholder = "Gratis: themoviedb.org → Inställningar → API",
            )
            Text(
                "Utan nyckel används genreord i leverantörens kategorinamn. Med nyckel sorteras resten i bakgrunden första gången (några minuter), sedan sparas allt.",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            NavRow(
                Icons.Rounded.VisibilityOff,
                "Dölj språk och kategorier",
                if (hiddenCount == 0) "Allt visas" else "$hiddenCount dolda",
            ) {
                save()
                onCategories()
            }
        }

        SettingsCard("App") {
            NavRow(Icons.Rounded.SystemUpdate, "Sök efter uppdatering", "Installerad: ${Updater.currentVersion}", onCheckUpdate)
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(26.dp))
    Text(title.uppercase(), color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp)
    Spacer(Modifier.height(10.dp))
    Column(
        Modifier
            .widthIn(max = 880.dp)
            .fillMaxWidth()
            .background(Surface1, RoundedCornerShape(20.dp))
            .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
            .padding(22.dp)
    ) { content() }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    FocusBox(onClick = onClick, modifier = Modifier.fillMaxWidth(), scale = 1.02f, glow = false) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) Color.White else CardBg)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = if (focused) OnAccent else Accent, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = if (focused) OnAccent else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = if (focused) OnAccent.copy(alpha = 0.7f) else Muted, fontSize = 14.sp)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = if (focused) OnAccent else Muted)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    FocusBox(onClick = onToggle, modifier = Modifier.fillMaxWidth(), scale = 1.02f, glow = false) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (focused) CardBg else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
            // Egen omkopplare
            Box(
                Modifier
                    .width(52.dp)
                    .height(30.dp)
                    .background(if (checked) Accent else Color.White.copy(alpha = 0.18f), RoundedCornerShape(50)),
                contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.padding(4.dp).size(22.dp).background(if (checked) OnAccent else Color.White, RoundedCornerShape(50)))
            }
        }
    }
}
