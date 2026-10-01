package com.realtimemanga.translator

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration
import com.realtimemanga.translator.data.EngineRegistry
import com.realtimemanga.translator.domain.LanguageRouter
import com.realtimemanga.translator.domain.LruTranslationCache
import com.realtimemanga.translator.domain.ScriptLanguageDetector
import com.realtimemanga.translator.service.AndroidLogger
import com.realtimemanga.translator.service.TranslatorRuntime
import com.realtimemanga.translator.service.UserPrefs

class MangaTranslatorApp : Application() {
    val registry = EngineRegistry()
    val cache = LruTranslationCache()
    val router = LanguageRouter(ScriptLanguageDetector())
    lateinit var prefs: UserPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = UserPrefs(this)
        TranslatorRuntime.logger = AndroidLogger()
        TranslatorRuntime.sourceMode.value = prefs.sourceMode
        registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                TranslatorRuntime.service?.onConfigurationChanged(newConfig)
            }

            override fun onLowMemory() {
                cache.clear()
            }
        })
    }
}
