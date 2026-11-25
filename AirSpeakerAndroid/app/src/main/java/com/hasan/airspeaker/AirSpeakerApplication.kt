package com.hasan.airspeaker

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class AirSpeakerApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Capture any uncaught crash to filesDir/last_crash.txt before delegating to default handler
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = "Thread: ${thread.name}\n${sw}"
                Log.e("AirSpeakerCrash", report)
                File(filesDir, "last_crash.txt").writeText(report)
            } catch (_: Throwable) {}
            prevHandler?.uncaughtException(thread, throwable)
        }

        try {
            DynamicColors.applyToActivitiesIfAvailable(this)
        } catch (t: Throwable) {
            Log.w("AirSpeakerApp", "DynamicColors unavailable on this device: ${t.message}")
        }
    }
}
