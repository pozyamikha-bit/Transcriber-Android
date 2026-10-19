package com.transcriber.app

import android.app.Application
import android.content.Context
import com.transcriber.app.data.Settings
import com.transcriber.app.work.JobManager

class TranscriberApp : Application() {
    lateinit var settings: Settings
        private set
    lateinit var jobs: JobManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        jobs = JobManager(this, settings)
    }
}

val Context.app get() = applicationContext as TranscriberApp
