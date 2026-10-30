package com.alex.mfppro

import android.app.Application
import com.alex.mfppro.data.Repo

class MfpApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        com.alex.mfppro.data.AlertMonitor(this, repo)
        if (repo.prefs.monitorBackground) com.alex.mfppro.data.MonitorService.setEnabled(this, true)
    }
}
