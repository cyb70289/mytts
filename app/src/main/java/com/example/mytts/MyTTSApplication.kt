package com.example.mytts

import android.app.Application

class MyTTSApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Clean up any temp files from previous crash
        cacheDir.listFiles()?.filter { it.name.startsWith("tts_") }?.forEach { it.delete() }
    }
}
