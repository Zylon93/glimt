package se.pontus.glimt

import android.content.Context

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("glimt", Context.MODE_PRIVATE)

    var m3uUrl: String
        get() = p.getString("m3u", "").orEmpty()
        set(v) { p.edit().putString("m3u", v.trim()).apply() }

    var osKey: String
        get() = p.getString("os_key", "").orEmpty()
        set(v) { p.edit().putString("os_key", v.trim()).apply() }

    var subdlKey: String
        get() = p.getString("subdl_key", "").orEmpty()
        set(v) { p.edit().putString("subdl_key", v.trim()).apply() }

    /** ISO 639-1-kod, t.ex. "sv". */
    var subLang: String
        get() = p.getString("sub_lang", "sv").orEmpty().ifBlank { "sv" }
        set(v) { p.edit().putString("sub_lang", v.trim().lowercase()).apply() }

    var autoSubs: Boolean
        get() = p.getBoolean("auto_subs", true)
        set(v) { p.edit().putBoolean("auto_subs", v).apply() }
}
