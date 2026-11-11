package com.alex.mfppro

import android.app.Application
import com.alex.mfppro.data.Repo
import com.alex.mfppro.platform.androidRepo

class MfpApp : Application() {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = androidRepo(this)
        com.alex.mfppro.data.AlertMonitor(this, repo)
        com.alex.mfppro.widget.Widgets.start(this, repo)
        if (repo.prefs.monitorBackground) com.alex.mfppro.data.MonitorService.setEnabled(this, true)
    }
}
