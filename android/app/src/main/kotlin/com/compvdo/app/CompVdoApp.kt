package com.compvdo.app

import android.app.Application

/**
 * Application class — initialisation point.
 * Currently minimal; will hold singletons if needed.
 */
class CompVdoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Nothing needed yet — DataStore and MediaScanner are context-based
    }
}
