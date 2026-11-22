package se.pontus.glimt

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ENTRY = "entry"
        private const val EXTRA_RESUME = "resume"

        /** [resume] = fortsätt där man slutade (om det finns en sparad position). */
        fun intent(context: Context, e: Entry, resume: Boolean = true): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_ENTRY, Store.entryToJson(e).toString())
                .putExtra(EXTRA_RESUME, resume)
    }

    private lateinit var prefs: Prefs
    private lateinit var player: ExoPlayer
    private lateinit var entry: Entry
    private lateinit var baseItem: MediaItem
    private lateinit var query: SubQuery

    // ---- Tillstånd som gränssnittet läser ----
    private var title by mutableStateOf("")
    private var subtitleLine by mutableStateOf<String?>(null)
    private var position by mutableLongStateOf(0L)
    private var duration by mutableLongStateOf(0L)
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var badges by mutableStateOf<List<String>>(emptyList())
    private var segments by mutableStateOf<List<IntroDb.Segment>>(emptyList())
    private var episodes by mutableStateOf<List<Entry>>(emptyList())
    private var currentUrl by mutableStateOf("")

    // ---- Undertexter ----
    private var results: List<SubResult> = emptyList()
    private var searchJob: Job? = null
    private var extrasJob: Job? = null
    private var activeIndex = -1
    private var cues by mutableStateOf<List<Cue>>(emptyList())
    /** Undertextsynk i ms. Positivt = texten visas senare. */
    private var subOffset by mutableLongStateOf(0L)
    private var syncMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Store.init(this)
        prefs = Prefs(this)
        val json = intent.getStringExtra(EXTRA_ENTRY) ?: return finish()
        val first = Store.entryFromJson(JSONObject(json))
        File(cacheDir, "subs").deleteRecursively()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast("Uppspelningsfel: ${error.errorCodeName}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) updateBadges()
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                updateBadges()
            }
        })

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) { PlayerScreen() }
        }
        loadEntry(first, intent.getBooleanExtra(EXTRA_RESUME, true))
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized && ::entry.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    // ---------------------------------------------------------------- Uppspelning

    private fun loadEntry(e: Entry, resume: Boolean) {
        if (::entry.isInitialized) saveProgress()
        entry = e
        currentUrl = e.url
        query = Subtitles.queryFor(e.title, e.series, e.season, e.episode)
        results = emptyList()
        activeIndex = -1
        cues = emptyList()
        syncMode = false
        segments = emptyList()
        badges = emptyList()
        searchJob?.cancel()
        title = if (e.kind == Kind.EPISODE && e.series != null) displayTitle(e.series) else displayTitle(e.title)
        subtitleLine = if (e.kind == Kind.EPISODE && e.season > 0) "S%02d E%02d · %s".format(e.season, e.episode, e.title) else null

        baseItem = MediaItem.Builder()
            .setUri(e.url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title).build())
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(prefs.subLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        val saved = if (resume) Store.progressFor(e.url) else null
        if (saved != null && saved.position > 30_000) {
            player.setMediaItem(baseItem, saved.position)
        } else {
            player.setMediaItem(baseItem)
        }
        player.prepare()
        player.playWhenReady = true

        if (prefs.autoSubs) startSearch(auto = true)
        loadExtras()
    }

    /** Hämtar seriens avsnitt (för nästa/föregående) och tidsstämplar från TheIntroDB. */
    private fun loadExtras() {
        extrasJob?.cancel()
        val e = entry
        extrasJob = lifecycleScope.launch {
            val acc = Repo.account
            val id = e.xtreamId
            var details: Details? = null
            if (acc != null && id != null) {
                if (e.kind == Kind.MOVIE) {
                    details = runCatching { Xtream.vodInfo(acc, id) }.getOrNull()
                } else {
                    val s = Series(e.series ?: e.title, e.group, e.logo, emptyList(), id)
                    val r = runCatching { Xtream.seriesInfo(acc, s) }.getOrNull()
                    if (r != null) {
                        details = r.first
                        episodes = r.second
                    }
                }
            } else if (e.kind == Kind.EPISODE) {
                val lib = (Repo.state.value as? LoadState.Ready)?.library
                episodes = lib?.allSeries?.firstOrNull { it.name == e.series && it.group == e.group }?.episodes.orEmpty()
            }
            if (details?.tmdbId == null && details?.imdbId == null) return@launch
            // Vänta tills längden är känd – TheIntroDB matchar då rätt version bättre
            var waited = 0
            while (duration <= 0 && waited < 40) {
                delay(500)
                waited++
            }
            val isEp = e.kind == Kind.EPISODE && e.season > 0
            segments = IntroDb.fetch(
                details?.tmdbId,
                details?.imdbId,
                if (isEp) e.season else null,
                if (isEp) e.episode else null,
                duration.takeIf { it > 0 },
            )
        }
    }

    private fun nextEpisode(): Entry? {
        val i = episodes.indexOfFirst { it.url == currentUrl }
        return if (i >= 0) episodes.getOrNull(i + 1) else null
    }

    private fun previousEpisode(): Entry? {
        val i = episodes.indexOfFirst { it.url == currentUrl }
        return if (i > 0) episodes[i - 1] else null
    }

    private fun onEnded() {
        Store.saveProgress(entry, duration, duration)
        val next = nextEpisode()
        if (next != null) loadEntry(next, resume = false) else finish()
    }

    private fun saveProgress() {
        val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val ended = player.playbackState == Player.STATE_ENDED
        Store.saveProgress(entry, if (ended) d else player.currentPosition, d)
    }

    private fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
    }

    private fun seekBy(ms: Long) {
        val d = duration
        val target = (player.currentPosition + ms).coerceAtLeast(0L)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
        position = player.currentPosition
    }

    private fun activeSegment(): IntroDb.Segment? {
        val pos = position
        return segments.firstOrNull { s ->
            s.type != IntroDb.Type.PREVIEW && pos >= s.start && pos < (s.end ?: duration) - 1500
        }
    }

    private fun skip(s: IntroDb.Segment) {
        if (s.type == IntroDb.Type.CREDITS) {
            Store.saveProgress(entry, duration, duration)
            val next = nextEpisode()
            if (next != null) loadEntry(next, resume = false) else finish()
        } else {
            player.seekTo(s.end ?: duration)
        }
    }

    private fun skipLabel(s: IntroDb.Segment): String = when (s.type) {
        IntroDb.Type.INTRO -> "Hoppa över intro"
        IntroDb.Type.RECAP -> "Hoppa över återblick"
        IntroDb.Type.CREDITS -> if (nextEpisode() != null) "Nästa avsnitt  ▶" else "Hoppa över eftertexter"
        IntroDb.Type.PREVIEW -> "Hoppa över"
    }

    private fun updateBadges() {
        val list = ArrayList<String>()
        val v = player.videoFormat
        val h = player.videoSize.height.takeIf { it > 0 } ?: v?.height ?: 0
        if (h > 0) list += when {
            h >= 2000 -> "4K"
            h >= 1000 -> "FHD"
            h >= 700 -> "HD"
            else -> "SD"
        }
        when (v?.sampleMimeType) {
            MimeTypes.VIDEO_H264 -> list += "H.264"
            MimeTypes.VIDEO_H265 -> list += "HEVC"
            MimeTypes.VIDEO_AV1 -> list += "AV1"
            MimeTypes.VIDEO_VP9 -> list += "VP9"
        }
        when (player.audioFormat?.channelCount) {
            8 -> list += "7.1"
            6 -> list += "5.1"
            2 -> list += "Stereo"
            1 -> list += "Mono"
        }
        badges = list
    }

    // ---------------------------------------------------------------- Gränssnitt

    @Composable
    private fun PlayerScreen() {
        var overlay by remember { mutableStateOf(true) }
        var lastKey by remember { mutableLongStateOf(0L) }
        val rootFocus = remember { FocusRequester() }
        val playFocus = remember { FocusRequester() }

        LaunchedEffect(Unit) {
            while (true) {
                position = player.currentPosition
                duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                delay(100)
            }
        }
        // Dölj kontrollerna efter 5 sekunder utan knapptryck när filmen spelar
        LaunchedEffect(overlay, lastKey, playing) {
            if (overlay && playing) {
                delay(5_000)
                overlay = false
            }
        }
        LaunchedEffect(overlay) {
            runCatching { if (overlay) playFocus.requestFocus() else rootFocus.requestFocus() }
        }
        BackHandler(enabled = overlay || syncMode) {
            if (syncMode) syncMode = false else overlay = false
        }
        LaunchedEffect(syncMode) {
            if (syncMode) runCatching { rootFocus.requestFocus() } else prefs.subOffset = subOffset
        }

        val segment = activeSegment()

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    lastKey = System.currentTimeMillis()
                    val code = ev.nativeKeyEvent.keyCode
                    when {
                        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); true }
                        code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_CAPTIONS -> { showSubtitleMenu(); true }
                        syncMode -> {
                            when (ev.key) {
                                Key.DirectionLeft -> subOffset -= 100
                                Key.DirectionRight -> subOffset += 100
                                Key.DirectionUp -> subOffset += 500
                                Key.DirectionDown -> subOffset -= 500
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.Back -> syncMode = false
                                else -> return@onPreviewKeyEvent false
                            }
                            true
                        }
                        overlay -> false // vanlig fokusnavigering bland knapparna
                        ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                            if (segment != null) skip(segment) else overlay = true
                            true
                        }
                        ev.key == Key.DirectionLeft -> { seekBy(-10_000); overlay = true; true }
                        ev.key == Key.DirectionRight -> { seekBy(10_000); overlay = true; true }
                        ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> { overlay = true; true }
                        else -> false
                    }
                }
                .focusRequester(rootFocus)
                .focusable()
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = this@PlayerActivity.player
                        setKeepContentOnPlayerReset(true)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (buffering) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center).size(56.dp))
            }
            // Egen undertextvisning (så att synken kan justeras direkt)
            val line = if (cues.isEmpty()) null else Srt.at(cues, position, subOffset)
            if (line != null) {
                Text(
                    line,
                    color = Color.White,
                    fontSize = 30.sp,
                    lineHeight = 38.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(Color.Black, Offset(2f, 2f), 6f)
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 80.dp, end = 80.dp, bottom = if (overlay) 230.dp else 56.dp)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            if (syncMode) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp)
                        .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 28.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Undertextsynk: " + offsetLabel(), color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "◀ ▶ 0,1 s   ▲ ▼ 0,5 s   OK = klar\nTexten före ljudet? Tryck ▶ så visas den senare.",
                        color = TextBright,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            if (overlay && !syncMode) Controls(playFocus)
            if (segment != null) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = if (overlay) 210.dp else 60.dp)) {
                    PillButton(skipLabel(segment), primary = !overlay) { skip(segment) }
                }
            }
        }
    }

    @Composable
    private fun Controls(playFocus: FocusRequester) {
        val next = nextEpisode()
        val prev = previousEpisode()
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(170.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
            Box(Modifier.fillMaxWidth().height(300.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))

            Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 36.dp)) {
                Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                subtitleLine?.let { Text(it, color = TextBright, fontSize = 16.sp) }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(end = 48.dp, top = 40.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                badges.forEach { b ->
                    Text(
                        b,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 30.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    if (duration > 0) Text("−" + fmt(duration - position), color = Color.White, fontSize = 18.sp)
                }
                Spacer(Modifier.height(8.dp))
                SeekBar()
                Spacer(Modifier.height(6.dp))
                Text(fmt(position), color = Color.White, fontSize = 16.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prev != null) RoundButton(onClick = { loadEntry(prev, resume = false) }) { c -> SkipIcon(c, forward = false) }
                    RoundButton(onClick = { seekBy(-10_000) }) { c -> Text("−10", color = c, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    RoundButton(size = 74.dp, focusRequester = playFocus, onClick = { togglePlay() }) { c ->
                        if (playing) PauseIcon(c) else PlayIcon(c)
                    }
                    RoundButton(onClick = { seekBy(10_000) }) { c -> Text("+10", color = c, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                    if (next != null) RoundButton(onClick = { loadEntry(next, resume = false) }) { c -> SkipIcon(c, forward = true) }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Row {
                            RoundButton(onClick = { showSubtitleMenu() }) { c ->
                                Text("CC", color = c, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                            RoundButton(onClick = { showAudioMenu() }) { c ->
                                Text("♪", color = c, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (duration > 0) {
                            val ends = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + duration - position))
                            Text("Slutar $ends", color = TextBright, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp, end = 8.dp))
                        }
                    }
                }
            }
        }
    }

    /** Tidslinje. Står fokus på den spolar vänster/höger 10 sekunder. */
    @Composable
    private fun SeekBar() {
        val source = remember { MutableInteractionSource() }
        val focused by source.collectIsFocusedAsState()
        val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.DirectionLeft -> { seekBy(-10_000); true }
                        Key.DirectionRight -> { seekBy(10_000); true }
                        Key.DirectionCenter, Key.Enter -> { togglePlay(); true }
                        else -> false
                    }
                }
                .focusable(interactionSource = source),
            contentAlignment = Alignment.CenterStart,
        ) {
            val barHeight = if (focused) 8.dp else 5.dp
            Box(Modifier.fillMaxWidth().height(barHeight).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
            Box(Modifier.fillMaxWidth(fraction).height(barHeight).background(Color(0xFF4CD08A), RoundedCornerShape(4.dp)))
            // Markera intro/eftertexter på tidslinjen
            if (duration > 0) {
                Canvas(Modifier.fillMaxWidth().height(barHeight)) {
                    segments.filter { it.type != IntroDb.Type.PREVIEW }.forEach { s ->
                        val x0 = size.width * (s.start.toFloat() / duration)
                        val x1 = size.width * (((s.end ?: duration).toFloat()) / duration)
                        drawRect(Color.White.copy(alpha = 0.35f), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(2f), size.height))
                    }
                }
            }
            Box(Modifier.fillMaxWidth(fraction).height(24.dp), contentAlignment = Alignment.CenterEnd) {
                Box(
                    Modifier
                        .offset(x = 9.dp)
                        .size(if (focused) 20.dp else 14.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
    }

    @Composable
    private fun RoundButton(
        size: Dp = 56.dp,
        focusRequester: FocusRequester? = null,
        onClick: () -> Unit,
        content: @Composable (Color) -> Unit,
    ) {
        val base = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
        FocusBox(onClick = onClick, modifier = base.padding(horizontal = 7.dp).size(size), shape = CircleShape, scale = 1.1f) { focused ->
            Box(
                Modifier.fillMaxSize().background(if (focused) Color.White else Color.White.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { content(if (focused) Color.Black else Color.White) }
        }
    }

    @Composable
    private fun PlayIcon(c: Color) = Canvas(Modifier.size(26.dp).offset(x = 2.dp)) {
        val p = Path().apply {
            moveTo(size.width * 0.15f, 0f)
            lineTo(size.width, size.height / 2)
            lineTo(size.width * 0.15f, size.height)
            close()
        }
        drawPath(p, c)
    }

    @Composable
    private fun PauseIcon(c: Color) = Canvas(Modifier.size(24.dp)) {
        val w = size.width * 0.3f
        drawRect(c, topLeft = Offset(size.width * 0.12f, 0f), size = Size(w, size.height))
        drawRect(c, topLeft = Offset(size.width * 0.58f, 0f), size = Size(w, size.height))
    }

    @Composable
    private fun SkipIcon(c: Color, forward: Boolean) = Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val h = size.height
        val p = Path().apply {
            if (forward) {
                moveTo(0f, 0f); lineTo(w * 0.75f, h / 2); lineTo(0f, h); close()
            } else {
                moveTo(w, 0f); lineTo(w * 0.25f, h / 2); lineTo(w, h); close()
            }
        }
        drawPath(p, c)
        val barX = if (forward) w * 0.78f else 0f
        drawRect(c, topLeft = Offset(barX, 0f), size = Size(w * 0.18f, h))
    }

    private fun fmt(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    // ---------------------------------------------------------------- Ljudspår

    private fun showAudioMenu() {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val options = ArrayList<Pair<String, () -> Unit>>()
        groups.forEach { g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val lang = f.language?.let { Locale(it).getDisplayLanguage(Locale("sv")).ifBlank { it } } ?: "Okänt språk"
                val ch = when (f.channelCount) { 8 -> " · 7.1"; 6 -> " · 5.1"; 2 -> " · Stereo"; else -> "" }
                val label = (f.label?.let { "$it – " } ?: "") + lang + ch
                val selected = g.isTrackSelected(i)
                options += ((if (selected) "✓ " else "") + label) to {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                        .build()
                }
            }
        }
        if (options.isEmpty()) {
            toast("Inga andra ljudspår")
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Ljudspår")
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    // ---------------------------------------------------------------- Undertexter

    private fun langName(): String =
        Locale(prefs.subLang).getDisplayLanguage(Locale("sv")).ifBlank { prefs.subLang }

    private fun startSearch(auto: Boolean) {
        if (prefs.osKey.isBlank() && prefs.subdlKey.isBlank()) {
            if (!auto) toast("Lägg in en API-nyckel för OpenSubtitles eller SubDL under Inställningar")
            return
        }
        if (searchJob?.isActive == true) {
            if (!auto) toast("Söker redan…")
            return
        }
        val q = query
        searchJob = lifecycleScope.launch {
            if (!auto) toast("Söker ${langName()} undertext för \"${q.title}\"…")
            val (list, errors) = Subtitles.search(q, prefs.subLang, prefs.osKey, prefs.subdlKey)
            results = list
            when {
                list.isEmpty() -> {
                    val extra = if (errors.isEmpty()) "" else "\n" + errors.joinToString("\n")
                    toast("Hittade ingen ${langName()} undertext för \"${q.title}\"$extra")
                }
                auto -> applyResult(0)
                else -> showSubtitleMenu()
            }
        }
    }

    private fun showSubtitleMenu() {
        if (results.isEmpty()) {
            startSearch(auto = false)
            return
        }
        val items = ArrayList<String>()
        items += if (activeIndex == -1) "✓ Av" else "Av"
        items += "Sök igen"
        if (cues.isNotEmpty()) items += "Justera synk (nu ${offsetLabel()})"
        results.forEachIndexed { i, r -> items += (if (i == activeIndex) "✓ " else "") + r.label }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Undertext (${langName()}) – ${query.title}")
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> {
                        setSubtitle(null)
                        activeIndex = -1
                    }
                    1 -> {
                        results = emptyList()
                        startSearch(auto = false)
                    }
                    2 -> if (cues.isNotEmpty()) syncMode = true else applyResult(0)
                    else -> applyResult(which - if (cues.isNotEmpty()) 3 else 2)
                }
            }
            .show()
    }

    private fun applyResult(index: Int) {
        val r = results.getOrNull(index) ?: return
        val forUrl = currentUrl
        lifecycleScope.launch {
            try {
                val text = r.fetch()
                if (forUrl != currentUrl) return@launch
                val parsed = withContext(Dispatchers.Default) { Srt.parse(text) }
                if (parsed.isEmpty()) {
                    toast("Undertexten gick inte att läsa")
                    return@launch
                }
                setSubtitle(parsed)
                activeIndex = index
                toast("Undertext: ${r.label}")
            } catch (e: Exception) {
                toast("Kunde inte hämta undertexten: ${e.message}")
            }
        }
    }

    /** Visar egna rader och stänger av inbäddade undertexter så att det inte blir dubbelt. */
    private fun setSubtitle(list: List<Cue>?) {
        cues = list.orEmpty()
        subOffset = prefs.subOffset
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun offsetLabel(): String {
        val s = subOffset / 1000.0
        return (if (subOffset > 0) "+" else "") + String.format(Locale("sv", "SE"), "%.1f s", s)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
