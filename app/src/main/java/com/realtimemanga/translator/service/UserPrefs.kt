package com.realtimemanga.translator.service

import android.content.Context
import com.realtimemanga.translator.domain.SourceMode

class UserPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var privacyAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_PRIVACY, value).apply()
        }

    var sourceMode: SourceMode
        get() = runCatching { SourceMode.valueOf(prefs.getString(KEY_MODE, SourceMode.AUTO.name)!!) }
            .getOrDefault(SourceMode.AUTO)
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    private companion object {
        const val NAME = "translator_prefs"
        const val KEY_PRIVACY = "privacy_accepted"
        const val KEY_MODE = "source_mode"
    }
}
