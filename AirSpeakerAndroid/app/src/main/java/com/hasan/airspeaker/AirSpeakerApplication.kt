package com.hasan.airspeaker

import android.app.Application
import com.google.android.material.color.DynamicColors

class AirSpeakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
