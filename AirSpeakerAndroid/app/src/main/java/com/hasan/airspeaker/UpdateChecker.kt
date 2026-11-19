package com.hasan.airspeaker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "AirSpeakerUpdates"
    private const val PREF_SKIPPED_UPDATE_ID = "skipped_update_id"
    private const val RELEASE_API_URL = "https://api.github.com/repos/HSAdib/AirSpeaker/releases/latest"

    data class UpdateInfo(
        val isAvailable: Boolean,
        val tagName: String,
        val displayVersion: String,
        val downloadUrl: String,
        val updateId: String,
        val releaseNotes: String
    )

    private fun parseSemver(v: String): Triple<Int, Int, Int> {
        val cleaned = v.trim().trimStart('v', 'V')
        val parts = cleaned.split(".")
        val n0 = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val n1 = parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        val n2 = parts.getOrNull(2)?.filter { it.isDigit() }?.toIntOrNull() ?: 0
        return Triple(n0, n1, n2)
    }

    private fun compareSemver(remote: Triple<Int, Int, Int>, local: Triple<Int, Int, Int>): Int {
        if (remote.first != local.first) return remote.first.compareTo(local.first)
        if (remote.second != local.second) return remote.second.compareTo(local.second)
        return remote.third.compareTo(local.third)
    }

    fun isUpdateSkipped(context: Context, updateId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(PREF_SKIPPED_UPDATE_ID, "") == updateId
    }

    fun skipUpdate(context: Context, updateId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_SKIPPED_UPDATE_ID, updateId).apply()
    }

    fun checkForUpdates(
        context: Context,
        isManual: Boolean,
        callback: (UpdateInfo?) -> Unit
    ) {
        Thread {
            val mainHandler = Handler(Looper.getMainLooper())
            var conn: HttpURLConnection? = null
            try {
                val url = URL(RELEASE_API_URL)
                conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "AirSpeaker-Android/1.0.0")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "GitHub release API returned code: ${conn.responseCode}")
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val json = JSONObject(sb.toString())
                val tagName = json.optString("tag_name", "")
                val htmlUrl = json.optString("html_url", "https://github.com/HSAdib/AirSpeaker/releases/latest")
                val body = json.optString("body", "")

                if (tagName.isEmpty()) {
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                // Parse build sha from release notes body: <!-- build: a1b2c3d -->
                var remoteSha: String? = null
                val pattern = Pattern.compile("<!--\\s*build:\\s*([a-fA-F0-9]+)\\s*-->", Pattern.CASE_INSENSITIVE)
                val matcher = pattern.matcher(body)
                if (matcher.find()) {
                    remoteSha = matcher.group(1)?.trim()
                }

                val localVersionName = "1.0.0"
                val localBuildSha = BuildConfig.BUILD_SHA

                val remoteSem = parseSemver(tagName)
                val localSem = parseSemver(localVersionName)
                val cmp = compareSemver(remoteSem, localSem)

                val isNewerSemver = cmp > 0
                var isNewerRebuild = false
                if (cmp == 0 && remoteSha != null && localBuildSha != "dev") {
                    if (!remoteSha.equals(localBuildSha, ignoreCase = true)) {
                        isNewerRebuild = true
                    }
                }

                if (!isNewerSemver && !isNewerRebuild) {
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                val updateId = if (remoteSha != null) "${tagName}_$remoteSha" else tagName
                if (!isManual && isUpdateSkipped(context, updateId)) {
                    Log.d(TAG, "Update $updateId has been skipped by user.")
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                // Locate APK asset in assets array
                var apkDownloadUrl = htmlUrl
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", apkDownloadUrl)
                            break
                        }
                    }
                }

                val displayVersion = if (isNewerRebuild && remoteSha != null) {
                    "$tagName (${remoteSha.take(7)})"
                } else {
                    tagName
                }

                val info = UpdateInfo(
                    isAvailable = true,
                    tagName = tagName,
                    displayVersion = displayVersion,
                    downloadUrl = apkDownloadUrl,
                    updateId = updateId,
                    releaseNotes = body
                )

                mainHandler.post { callback(info) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check for updates: ${e.message}")
                mainHandler.post { callback(null) }
            } finally {
                conn?.disconnect()
            }
        }.start()
    }
}
