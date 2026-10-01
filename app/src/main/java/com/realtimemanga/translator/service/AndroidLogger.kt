package com.realtimemanga.translator.service

import android.util.Log
import com.realtimemanga.translator.BuildConfig
import com.realtimemanga.translator.domain.AppLogger

class AndroidLogger : AppLogger {
    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
    }

    override fun error(tag: String, message: String, error: Throwable?) {
        Log.e(tag, message, error)
    }
}
