package com.hasan.airspeaker

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "AirSpeakerUpdates"
    private const val PREF_SKIPPED_UPDATE_ID = "skipped_update_id"
    private const val RELEASE_API_URL = "https://api.github.com/repos/HSAdib/AirSpeaker/releases/latest"

    @Volatile private var cachedEtag: String? = null
    @Volatile private var cachedJson: String? = null

    data class UpdateInfo(
        val isAvailable: Boolean,
        val tagName: String,
        val displayVersion: String,
        val downloadUrl: String,
        val fileName: String,
        val updateId: String,
        val releaseNotes: String
    )

    fun formatBytes(bytes: Long): String {
        var v = bytes.toDouble().coerceAtLeast(0.0)
        val units = arrayOf("B", "KB", "MB", "GB")
        for (unit in units) {
            if (v < 1024.0 || unit == "GB") {
                return if (unit == "B") "${v.toLong()} B" else String.format(java.util.Locale.US, "%.1f %s", v, unit)
            }
            v /= 1024.0
        }
        return String.format(java.util.Locale.US, "%.1f GB", v)
    }

    fun formatSpeed(bps: Double): String {
        return "${formatBytes(bps.toLong())}/s"
    }

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
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "AirSpeaker-Android/${BuildConfig.VERSION_NAME}")
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

                cachedEtag?.let { etag ->
                    if (cachedJson != null) {
                        conn.setRequestProperty("If-None-Match", etag)
                    }
                }

                val responseCode = conn.responseCode
                val responseBodyStr: String

                if (responseCode == HttpURLConnection.HTTP_NOT_MODIFIED && cachedJson != null) {
                    responseBodyStr = cachedJson!!
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    conn.getHeaderField("ETag")?.let { cachedEtag = it }
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()
                    responseBodyStr = sb.toString()
                    cachedJson = responseBodyStr
                } else {
                    Log.w(TAG, "GitHub release API returned code: $responseCode")
                    mainHandler.post { callback(null) }
                    return@Thread
                }

                val json = JSONObject(responseBodyStr)
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

                val localVersionName = BuildConfig.VERSION_NAME
                val localBuildSha = BuildConfig.BUILD_SHA

                // Exact build SHA short-circuit: already running exact same commit
                if (remoteSha != null && localBuildSha != "dev" &&
                    remoteSha.take(7).equals(localBuildSha.take(7), ignoreCase = true)
                ) {
                    mainHandler.post { callback(null) }
                    return@Thread
                }

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

                // Locate APK asset in assets array matching device supported ABI
                var apkDownloadUrl: String = htmlUrl
                var apkFileName = "AirSpeaker-$tagName.apk"
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    val assetMap = mutableMapOf<String, Pair<String, String>>()
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i) ?: continue
                        val name = asset.optString("name", "")
                        val dlUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true) && dlUrl.isNotEmpty()) {
                            assetMap[name.lowercase()] = Pair(dlUrl, name)
                        }
                    }

                    var matchedPair: Pair<String, String>? = null
                    // 1. Match device supported ABIs in priority order
                    val supportedAbis = android.os.Build.SUPPORTED_ABIS ?: emptyArray()
                    for (abi in supportedAbis) {
                        val normalizedAbi = abi.lowercase()
                        val matched = assetMap.entries.firstOrNull { it.key.contains(normalizedAbi) }
                        if (matched != null) {
                            matchedPair = matched.value
                            break
                        }
                    }

                    // 2. Fall back to universal APK
                    if (matchedPair == null) {
                        val universal = assetMap.entries.firstOrNull { it.key.contains("universal") }
                        if (universal != null) {
                            matchedPair = universal.value
                        }
                    }

                    // 3. Fall back to any .apk
                    if (matchedPair == null && assetMap.isNotEmpty()) {
                        matchedPair = assetMap.values.first()
                    }

                    if (matchedPair != null) {
                        apkDownloadUrl = matchedPair.first
                        apkFileName = matchedPair.second
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
                    fileName = apkFileName,
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

    fun downloadApkToDownloads(
        context: Context,
        info: UpdateInfo,
        cancelFlag: AtomicBoolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Double) -> Unit,
        onComplete: (uri: Uri, displayPath: String) -> Unit,
        onError: (Exception) -> Unit
    ) {
        Thread {
            val mainHandler = Handler(Looper.getMainLooper())
            var currentUrl = info.downloadUrl
            var conn: HttpURLConnection? = null
            var redirects = 0
            var inputStream: InputStream? = null

            try {
                // Follow redirects (GitHub release asset URLs redirect to objects.githubusercontent.com)
                while (redirects < 8) {
                    val urlObj = URL(currentUrl)
                    conn = urlObj.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 15000
                    conn.setRequestProperty("User-Agent", "AirSpeaker-Android/${BuildConfig.VERSION_NAME}")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val location = conn.getHeaderField("Location") ?: break
                        currentUrl = location
                        conn.disconnect()
                        redirects++
                    } else if (code == HttpURLConnection.HTTP_OK) {
                        break
                    } else {
                        throw RuntimeException("HTTP error $code from $currentUrl")
                    }
                }

                if (conn == null || conn.responseCode != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("Failed to establish download connection")
                }

                val totalBytes = conn.contentLength.toLong()
                val stream = conn.inputStream
                inputStream = stream

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, info.fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }

                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                        ?: throw RuntimeException("Could not create MediaStore download entry")

                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            val buf = ByteArray(32768)
                            var read: Int
                            var downloaded: Long = 0
                            var lastProgressTime = System.currentTimeMillis()
                            var lastProgressBytes: Long = 0
                            var emaSpeed = 0.0

                            while (stream.read(buf).also { read = it } != -1) {
                                if (cancelFlag.get()) {
                                    throw InterruptedException("Download cancelled by user")
                                }
                                out.write(buf, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                val dt = (now - lastProgressTime) / 1000.0
                                if (dt >= 0.15) {
                                    val instSpeed = (downloaded - lastProgressBytes) / dt
                                    emaSpeed = if (emaSpeed <= 0.0) instSpeed else (0.7 * emaSpeed + 0.3 * instSpeed)
                                    lastProgressTime = now
                                    lastProgressBytes = downloaded
                                    val curD = downloaded
                                    val curSpeed = emaSpeed
                                    mainHandler.post { onProgress(curD, totalBytes, curSpeed) }
                                }
                            }
                            out.flush()
                        } ?: throw RuntimeException("Could not open output stream for $uri")

                        contentValues.clear()
                        contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)

                        mainHandler.post { onComplete(uri, "Downloads/${info.fileName}") }
                    } catch (e: Exception) {
                        try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                        throw e
                    }
                } else {
                    // API 24–28: Write directly to public Downloads directory
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val targetDir = if (downloadsDir.exists() || downloadsDir.mkdirs()) downloadsDir else context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    val partFile = File(targetDir, "${info.fileName}.part")
                    val finalFile = File(targetDir, info.fileName)

                    FileOutputStream(partFile).use { out ->
                        val buf = ByteArray(32768)
                        var read: Int
                        var downloaded: Long = 0
                        var lastProgressTime = System.currentTimeMillis()
                        var lastProgressBytes: Long = 0
                        var emaSpeed = 0.0

                        while (stream.read(buf).also { read = it } != -1) {
                            if (cancelFlag.get()) {
                                throw InterruptedException("Download cancelled by user")
                            }
                            out.write(buf, 0, read)
                            downloaded += read

                            val now = System.currentTimeMillis()
                            val dt = (now - lastProgressTime) / 1000.0
                            if (dt >= 0.15) {
                                val instSpeed = (downloaded - lastProgressBytes) / dt
                                emaSpeed = if (emaSpeed <= 0.0) instSpeed else (0.7 * emaSpeed + 0.3 * instSpeed)
                                lastProgressTime = now
                                lastProgressBytes = downloaded
                                val curD = downloaded
                                val curSpeed = emaSpeed
                                mainHandler.post { onProgress(curD, totalBytes, curSpeed) }
                            }
                        }
                        out.flush()
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        partFile.copyTo(finalFile, overwrite = true)
                        partFile.delete()
                    }

                    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", finalFile)
                    mainHandler.post { onComplete(apkUri, finalFile.absolutePath) }
                }
            } catch (e: Exception) {
                if (e is InterruptedException) {
                    Log.i(TAG, "Update download cancelled.")
                } else {
                    Log.e(TAG, "Update download failed: ${e.message}", e)
                }
                mainHandler.post { onError(e) }
            } finally {
                try { inputStream?.close() } catch (_: Exception) {}
                conn?.disconnect()
            }
        }.start()
    }

    fun launchApkInstall(context: Context, apkUri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch APK installer: ${e.message}", e)
        }
    }
}
