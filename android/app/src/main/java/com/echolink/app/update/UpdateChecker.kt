package com.echolink.app.update

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * 版本自检。查一个极简的版本接口，把服务端版本和本地版本比较，供 UI 提示"有新版本"。
 *
 * 只做**提示 + 打开下载页**，不自动下载、不自动安装：更新包是 APK，
 * 静默安装要么做不到、要么需要危险权限，都会把更新通道变成攻击面。
 *
 * 接口是 HTTP（非 HTTPS），所以只能当"提示"，绝不能作为安全边界：
 * 被中间人改返回值的后果是"弹一个假的下载提示"，而下载页同样走浏览器，用户仍能看到域名。
 */
object UpdateChecker {

    /** 版本接口。key 公开在客户端里，只算粗粒度防扫描，不是机密。 */
    const val ENDPOINT =
        "http://carovo.shop/api/version.php?format=json&key=ae5d71d171e39cc04defc29293506fc0"

    const val ANDROID_DOWNLOAD_URL = "http://el.carovo.shop/echolink-arm64-debug.apk"

    data class Result(
        val latestVersion: String,
        val isNewer: Boolean,
    )

    /**
     * 同步查询（调用方负责放到 IO 线程）。任何失败返回 null，不影响主流程。
     */
    fun check(currentVersion: String): Result? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("Accept", "application/json")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) {
                return null
            }

            val latest = json.optString("version", "")
            if (latest.isBlank()) {
                return null
            }

            Result(latestVersion = latest, isNewer = isNewer(latest, currentVersion))
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** remote 是否比 local 新。 */
    fun isNewer(remote: String?, local: String?): Boolean = compareVersions(remote, local) > 0

    /**
     * 逐段比较点分版本号。非数字段按 0、缺段按 0 补齐，
     * 这样 "0.3.10" 正确大于 "0.3.9"（字符串比较会搞错）。
     */
    fun compareVersions(left: String?, right: String?): Int {
        val a = split(left)
        val b = split(right)
        val length = maxOf(a.size, b.size)
        for (i in 0 until length) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) {
                return if (x > y) 1 else -1
            }
        }

        return 0
    }

    private fun split(version: String?): List<Int> {
        if (version.isNullOrBlank()) {
            return emptyList()
        }

        return version.trim().trimStart('v', 'V')
            .split('.')
            .filter { it.isNotEmpty() }
            .map { piece -> piece.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
