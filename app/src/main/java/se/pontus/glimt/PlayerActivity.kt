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
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private lateinit var playerView: PlayerView
    private lateinit var baseItem: MediaItem
    private lateinit var query: SubQuery
    private lateinit var entry: Entry

    private var results: List<SubResult> = emptyList()
    private var searchJob: Job? = null
    private var activeIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        val json = intent.getStringExtra(EXTRA_ENTRY) ?: return finish()
        entry = Store.entryFromJson(org.json.JSONObject(json))
        val url = entry.url
        val title = entry.title
        query = Subtitles.queryFor(title, entry.series, entry.season, entry.episode)
        File(cacheDir, "subs").deleteRecursively()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguage(prefs.subLang)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                toast("Uppspelningsfel: ${error.errorCodeName}")
            }
        })

        playerView = PlayerView(this).apply {
            player = this@PlayerActivity.player
            setShowSubtitleButton(true)
            controllerShowTimeoutMs = 4000
        }
        setContentView(playerView)

        baseItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        val saved = if (intent.getBooleanExtra(EXTRA_RESUME, true)) Store.progressFor(url) else null
        if (saved != null && saved.position > 30_000) {
            player.setMediaItem(baseItem, saved.position)
            toast("Fortsätter där du slutade")
        } else {
            player.setMediaItem(baseItem)
        }
        player.prepare()
        player.playWhenReady = true

        if (prefs.autoSubs) startSearch(auto = true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val k = event.keyCode
            val openMenu = k == KeyEvent.KEYCODE_MENU ||
                k == KeyEvent.KEYCODE_CAPTIONS ||
                k == KeyEvent.KEYCODE_PROG_YELLOW ||
                (k == KeyEvent.KEYCODE_DPAD_UP && !playerView.isControllerFullyVisible)
            if (openMenu) {
                showMenu()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized) {
            saveProgress()
            player.pause()
        }
    }

    private fun saveProgress() {
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val ended = player.playbackState == Player.STATE_ENDED
        Store.saveProgress(entry, if (ended) duration else player.currentPosition, duration)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    // ---------- Undertexter ----------

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
        searchJob = lifecycleScope.launch {
            if (!auto) toast("Söker ${langName()} undertext för \"${query.title}\"…")
            val (list, errors) = Subtitles.search(query, prefs.subLang, prefs.osKey, prefs.subdlKey)
            results = list
            when {
                list.isEmpty() -> {
                    val extra = if (errors.isEmpty()) "" else "\n" + errors.joinToString("\n")
                    toast("Hittade ingen ${langName()} undertext för \"${query.title}\"$extra")
                }
                auto -> applyResult(0)
                else -> showMenu()
            }
        }
    }

    private fun showMenu() {
        if (results.isEmpty()) {
            startSearch(auto = false)
            return
        }
        val items = ArrayList<String>()
        items += if (activeIndex == -1) "✓ Av" else "Av"
        items += "Sök igen"
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
                    else -> applyResult(which - 2)
                }
            }
            .show()
    }

    private fun applyResult(index: Int) {
        val r = results.getOrNull(index) ?: return
        lifecycleScope.launch {
            try {
                val text = r.fetch()
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "subs").apply { mkdirs() }
                    File(dir, "sub_${System.currentTimeMillis()}.srt").also { it.writeText(text, Charsets.UTF_8) }
                }
                setSubtitle(file)
                activeIndex = index
                toast("Undertext: ${r.label}")
            } catch (e: Exception) {
                toast("Kunde inte hämta undertexten: ${e.message}")
            }
        }
    }

    private fun setSubtitle(file: File?) {
        val position = player.currentPosition
        val configs = if (file == null) emptyList() else listOf(
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(file))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(prefs.subLang)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
        )
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, file == null)
            .build()
        player.setMediaItem(baseItem.buildUpon().setSubtitleConfigurations(configs).build(), position)
        player.prepare()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
