package com.cloudagentos.app

import android.app.Application
import com.cloudagentos.app.notifications.NotificationHelper

class CloudAgentOSApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
    }
}
