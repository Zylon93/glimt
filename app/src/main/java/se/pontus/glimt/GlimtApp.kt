package se.pontus.glimt

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File

/** Sparar kraschrapporter så att de kan visas nästa gång appen startar. */
class GlimtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(
                    "Glimt ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                        "Grundorsak:\n" + rootCause(error) + "\n\nHela rapporten:\n" +
                        Log.getStackTraceString(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun rootCause(error: Throwable): String {
        var e = error
        while (e.cause != null && e.cause !== e) e = e.cause!!
        return e.toString() + "\n" + e.stackTrace.take(6).joinToString("\n") { "  at $it" }
    }

    companion object {
        const val CRASH_FILE = "crash.txt"
    }
}
