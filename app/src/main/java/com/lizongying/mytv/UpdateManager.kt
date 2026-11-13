package com.lizongying.mytv

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lizongying.mytv.api.ReleaseV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateManager(
    private var context: Context,
    private var settingFragment: SettingFragment,
    private var versionCode: Long
) : ConfirmationFragment.ConfirmationListener {

    private var release: ReleaseV2? = null
    private var downloadReceiver: DownloadReceiver? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun checkAndUpdate() {
        val progressBar = settingFragment.view?.findViewById<View>(R.id.check_version_loading)
        progressBar?.visibility = View.VISIBLE
        Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            var updateAvailable = false
            var dialogText = ""

            try {
                val rel = fetchLatestGithubRelease()
                release = rel
                if (rel != null) {
                    val currentVersion = context.appVersionName
                    val latestVersion = rel.n ?: ""
                    Log.i(TAG, "Current version: $currentVersion, Latest version: $latestVersion")

                    if (isNewerVersion(latestVersion, currentVersion)) {
                        updateAvailable = true
                        dialogText = "发现新版本：$latestVersion\n\n${rel.d ?: "无更新说明"}"
                    } else {
                        Toast.makeText(context, "当前已是最新版本 (v$currentVersion)", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(context, "检查更新失败，未获取到版本信息", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkAndUpdate error: ${e.message}", e)
                Toast.makeText(context, "检查更新失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progressBar?.visibility = View.GONE
            }

            if (updateAvailable) {
                val dialog = ConfirmationFragment(this@UpdateManager, dialogText)
                dialog.show(settingFragment.parentFragmentManager, TAG)
            }
        }
    }

    private suspend fun fetchLatestGithubRelease(): ReleaseV2? = withContext(Dispatchers.IO) {
        val apiUrl = "https://api.github.com/repos/zysaii/m3u_tvbox/releases/latest"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", "my-tv-app")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Github release API failed with code: ${response.code()}")
                    return@withContext null
                }
                val body = response.body()?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                val bodyText = json.optString("body", "").trim()

                // 查找 assets 中的 apk 下载链接
                var downloadUrl: String? = null
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }

                if (downloadUrl.isNullOrEmpty()) {
                    downloadUrl = "https://github.com/zysaii/m3u_tvbox/releases/download/$tagName/app-release.apk"
                }

                // 使用 gh-proxy 加速下载，保证国内电视网络能够正常顺畅下载
                val proxyDownloadUrl = if (downloadUrl.startsWith("https://github.com")) {
                    "https://gh-proxy.org/$downloadUrl"
                } else {
                    downloadUrl
                }

                return@withContext ReleaseV2(
                    n = tagName.removePrefix("v"),
                    u = proxyDownloadUrl,
                    d = bodyText,
                    c = 0
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchLatestGithubRelease exception: ${e.message}", e)
            null
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        val cleanRemote = remote.trim().removePrefix("v")
        val cleanLocal = local.trim().removePrefix("v")
        if (cleanRemote.isEmpty() || cleanRemote == cleanLocal) return false

        val remoteParts = cleanRemote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = cleanLocal.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, localParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun startDownload(release: ReleaseV2) {
        val url = release.u ?: return
        val apkFileName = "m3u_tvbox_${release.n}.apk"
        Toast.makeText(context, "开始下载新版本，请稍候...", Toast.LENGTH_SHORT).show()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
        request.setTitle("我的电视 ${release.n}")
        request.setDescription("正在下载新版本安装包...")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setMimeType("application/vnd.android.package-archive")

        // 存放到 Context 的外部文件私有目录，不需要运行时 WRITE_EXTERNAL_STORAGE 权限
        request.setDestinationInExternalFilesDir(
            context,
            Environment.DIRECTORY_DOWNLOADS,
            apkFileName
        )

        val downloadReference = downloadManager.enqueue(request)

        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Unregister previous receiver failed", e)
            }
        }

        downloadReceiver = DownloadReceiver(context, apkFileName, downloadReference)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(
                downloadReceiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    private class DownloadReceiver(
        private val context: Context,
        private val apkFileName: String,
        private val downloadReference: Long
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reference = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (reference == downloadReference) {
                Toast.makeText(context, "下载完成，正在调起安装...", Toast.LENGTH_SHORT).show()
                installNewVersion()
            }
        }

        private fun installNewVersion() {
            try {
                val apkFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkFileName)
                if (!apkFile.exists()) {
                    Toast.makeText(context, "安装包不存在", Toast.LENGTH_SHORT).show()
                    return
                }

                val installIntent = Intent(Intent.ACTION_VIEW)
                val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
                } else {
                    Uri.fromFile(apkFile)
                }

                installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive")
                installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(installIntent)
            } catch (e: Exception) {
                Log.e(TAG, "installNewVersion error: ${e.message}", e)
                Toast.makeText(context, "启动安装程序失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConfirm() {
        release?.let { startDownload(it) }
    }

    override fun onCancel() {
    }

    fun destroy() {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "destroy unregisterReceiver failed", e)
            }
            downloadReceiver = null
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}
