package app.translive.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateOffer(val versionName: String, val apkUrl: String)

object AppUpdater {
    const val VERSION_URL = "https://raw.githubusercontent.com/ken2025mendoza11/translive/main/android/version.json"

    fun check(context: Context, onReady: (UpdateOffer?) -> Unit, onError: () -> Unit) {
        Thread {
            try {
                val json = JSONObject(get(VERSION_URL))
                val code = json.getInt("versionCode")
                val offer = if (code > localCode(context)) {
                    UpdateOffer(json.getString("versionName"), json.getString("apk"))
                } else {
                    null
                }
                main { onReady(offer) }
            } catch (_: Exception) {
                main { onError() }
            }
        }.start()
    }

    fun download(context: Context, url: String, onDone: () -> Unit, onError: (String) -> Unit) {
        Thread {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val file = File(dir, "TransLIVE-update.apk")
                val conn = open(url)
                conn.inputStream.use { input ->
                    FileOutputStream(file).use { out -> input.copyTo(out) }
                }
                conn.disconnect()
                if (file.length() < 1_000_000) {
                    main { onError("The download was incomplete.") }
                    return@Thread
                }
                main {
                    install(context, file)
                    onDone()
                }
            } catch (_: Exception) {
                main { onError("Couldn’t download the update.") }
            }
        }.start()
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun localCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun get(url: String): String {
        val conn = open(url)
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("User-Agent", "TransLIVE")
        conn.setRequestProperty("Accept", "application/vnd.android.package-archive, application/json, */*")
        if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
        return conn
    }

    private fun main(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
