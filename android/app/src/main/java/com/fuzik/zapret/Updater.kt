package com.fuzik.zapret

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Обновление приложения с GitHub — из того же репозитория, что и Windows-версия. */
object Updater {
    private const val API = "https://api.github.com/repos/Fuzedev-design/zapret-fuzik/releases?per_page=15"
    const val ASSET = "Zapret-ot-Fuzika.apk"

    data class Update(val tag: String, val url: String, val size: Long, val sha256: String?, val page: String)

    private fun versionKey(v: String): List<Int> {
        val nums = Regex("\\d+").findAll(v.trim().trimStart('v', 'V')).map { it.value.toInt() }.toMutableList()
        while (nums.size < 4) nums += 0
        return nums
    }

    fun isNewer(remote: String, local: String): Boolean {
        val a = versionKey(remote)
        val b = versionKey(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ZapretFuzik-Android")
        c.connectTimeout = 15000
        c.readTimeout = 15000
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Ищет самый свежий релиз, в котором есть APK (в релизах лежит и exe для Windows). */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        val releases = JSONArray(get(API))
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
            val assets = r.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                if (a.getString("name") != ASSET) continue
                val tag = r.getString("tag_name")
                if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                return@withContext Update(tag, a.getString("browser_download_url"), a.getLong("size"), digest,
                    r.getString("html_url"))
            }
        }
        null
    }

    /** Скачивает APK и сверяет размер и SHA-256 с данными GitHub. */
    suspend fun download(ctx: Context, u: Update, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val c = URL(u.url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "ZapretFuzik-Android")
        c.connectTimeout = 15000
        c.readTimeout = 30000
        val md = MessageDigest.getInstance("SHA-256")
        var done = 0L
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    md.update(buf, 0, n)
                    done += n
                    onProgress(done.toFloat() / u.size)
                }
            }
        }
        if (done != u.size) throw IllegalStateException("Размер файла не совпадает, загрузка повреждена")
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        if (u.sha256 != null && hex != u.sha256) {
            out.delete()
            throw IllegalStateException("Контрольная сумма не совпадает, файл повреждён или подменён")
        }
        out
    }

    /** true — установщик открыт; false — сначала нужно разрешить установку из этого приложения. */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
