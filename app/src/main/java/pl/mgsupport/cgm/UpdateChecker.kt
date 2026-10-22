package pl.mgsupport.cgm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A newer release published on GitHub, ready to be shown and downloaded. */
data class AvailableUpdate(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

sealed interface UpdateCheck {
    data class Found(val update: AvailableUpdate) : UpdateCheck
    data object None : UpdateCheck
    data object Failed : UpdateCheck
}

/**
 * Self-update against the project's GitHub Releases.
 *
 * The app is distributed outside Google Play for now, so the release feed is the update channel:
 * the latest release tag is compared with [BuildConfig.VERSION_NAME] and its APK asset is handed to
 * the system installer. Everything here is best effort: no network, no release or a malformed
 * payload simply means "no update".
 */
class UpdateChecker(private val context: Context) {

    suspend fun latest(): UpdateCheck = withContext(Dispatchers.IO) {
        val conn = open(RELEASES_API) ?: return@withContext UpdateCheck.Failed
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext UpdateCheck.None
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext UpdateCheck.Failed
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            if (version.isBlank()) return@withContext UpdateCheck.Failed
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) return@withContext UpdateCheck.None

            val apkUrl = firstApkUrl(json) ?: return@withContext UpdateCheck.Failed
            UpdateCheck.Found(
                AvailableUpdate(
                    version = version,
                    notes = json.optString("body").trim(),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                ),
            )
        } catch (_: Exception) {
            UpdateCheck.Failed
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release APK into the cache directory. Returns null when the transfer failed. */
    suspend fun download(update: AvailableUpdate): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "MGSupportCGM-${update.version}.apk")
        val conn = open(update.apkUrl) ?: return@withContext null
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.takeIf { it.length() > 0 }
        } catch (_: Exception) {
            target.delete()
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Hands the downloaded APK to the system installer. */
    fun install(apk: File) {
        val uri =
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    /** Low-priority notification so an update is visible even if the dialog was dismissed. */
    fun notifyAvailable(update: AvailableUpdate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.updates_channel),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(true) },
                )
            }
        }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, PumpDashboardActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.updates_available_title))
                .setContentText(context.getString(R.string.updates_available_body, update.version))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, notification)
    }

    private fun open(rawUrl: String): HttpURLConnection? =
        try {
            (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MGSupportCGM/${BuildConfig.VERSION_NAME}")
            }
        } catch (_: Exception) {
            null
        }

    private fun firstApkUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                return asset.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
        }
        return null
    }

    private companion object {
        const val RELEASES_API = "https://api.github.com/repos/MichaelGrycz/mgsupport-cgm/releases/latest"
        const val CHANNEL_ID = "app_updates"
        const val NOTIF_ID = 2001
    }
}

/** Numeric segment comparison so `1.0-beta10` counts as newer than `1.0-beta9`. */
internal fun compareVersions(candidate: String, current: String): Int {
    fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
    val a = parts(candidate)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(i) { 0 }
        val right = b.getOrElse(i) { 0 }
        if (left != right) return left - right
    }
    return 0
}
