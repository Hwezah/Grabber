package com.hwezah.grabber

import android.app.Application

class GrabberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Downloads.init(this)
    }
}
