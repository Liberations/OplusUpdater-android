package com.houvven.oplusupdater.utils

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.houvven.oplusupdater.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UrlDecryptUtil {

    /**
     * 解析并获取最终重定向后的 URL
     * @param originalUrl 原始 URL
     * @return 最终 URL
     */
    suspend fun resolveUrl(originalUrl: String): String = withContext(Dispatchers.IO) {
        // 如果不包含 downloadCheck，直接返回原 URL
        if (!originalUrl.contains("downloadCheck")) {
            return@withContext originalUrl
        }

        var currentUrl = URL(originalUrl)
        var redirectCount = 0
        val maxRedirects = 10

        while (redirectCount++ < maxRedirects) {
            val conn = (currentUrl.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 15000
                requestMethod = "GET"  // 改为 GET，与 Java 版本一致

                // 添加请求头
                val headers = buildHeaders(currentUrl)
                headers.forEach { (key, value) ->
                    setRequestProperty(key, value)
                }
            }

            try {
                val code = conn.responseCode
                when {
                    code in 300..399 -> {
                        val location = conn.getHeaderField("Location")
                            ?: throw IOException("Redirect without Location header")

                        // 支持相对路径重定向
                        val nextUrl = URL(currentUrl, location)
                        conn.disconnect()

                        // 如果重定向后的 URL 不再包含 downloadCheck，返回最终地址
                        if (!location.contains("downloadCheck")) {
                            return@withContext location
                        }

                        currentUrl = nextUrl
                    }

                    code == 200 -> {
                        val finalUrl = conn.url.toString()
                        conn.disconnect()
                        return@withContext finalUrl
                    }

                    else -> {
                        conn.disconnect()
                        throw IOException("Unexpected response code: $code")
                    }
                }
            } catch (e: Exception) {
                conn.disconnect()
                throw e
            }
        }

        throw IOException("Too many redirects")
    }

    /**
     * 构建请求头
     */
    private fun buildHeaders(url: URL): Map<String, String> {
        val id = extractIdFromUrl(url)

        return buildMap {
            // language - persist.sys.locale
            put(
                "language", getSystemProperty("persist.sys.locale")
                    ?: Locale.getDefault().toString()
            )

            // androidVersion - "Android" + ro.build.version.release
            put("androidVersion", "Android ${Build.VERSION.RELEASE}")

            // colorOSVersion - "ColorOS" + ro.build.version.oplusrom (移除 V)
            put("colorOSVersion", buildColorOSVersion())

            // otaVersion - ro.build.version.ota
            getSystemProperty("ro.build.version.ota")?.let {
                put("otaVersion", it)
            }

            // model - ro.product.name
            put("model", getSystemProperty("ro.product.name") ?: Build.MODEL)

            // mode - sys.ota.test
            put("mode", getSystemProperty("sys.ota.test") ?: "0")

            // nvCarrier - ro.build.oplus_nv_id
            getSystemProperty("ro.build.oplus_nv_id")?.let {
                put("nvCarrier", it)
            }

            // brand - ro.product.brand
            put("brand", Build.BRAND)

            // osType - ro.oplus.image.my_stock.type
            getSystemProperty("ro.oplus.image.my_stock.type")?.let {
                put("osType", it)
            }

            // operator - persist.sys.channel.info 或 ro.oplus.pipeline.carrier 或 "default"
            val operator = getSystemProperty("persist.sys.channel.info")
                ?: getSystemProperty("ro.oplus.pipeline.carrier")
                ?: "default"
            put("operator", operator)

            // prjNum - ro.separate.soft
            getSystemProperty("ro.separate.soft")?.let {
                put("prjNum", it)
            }

            // id - 从 URL 提取 g 参数
            if (id.isNotEmpty()) {
                put("id", id)
            }

            // ts - 时间戳
            put("ts", System.currentTimeMillis().toString())

            // userId - oplus-ota
            put("userId", "oplus-ota|16000015")
        }
    }

    /**
     * 从 URL 中提取 g 参数作为 id
     */
    private fun extractIdFromUrl(url: URL): String {
        val query = url.query ?: return ""
        return query.split("&")
            .firstOrNull { it.startsWith("g=") }
            ?.substringAfter("g=")
            ?: ""
    }

    /**
     * 构建 ColorOS 版本号（移除 V 前缀）
     */
    private fun buildColorOSVersion(): String {
        val version = getSystemProperty("ro.build.version.oplusrom") ?: ""
        return "ColorOS${version.replace("V", "")}"
    }

    /**
     * 获取系统属性
     */
    @SuppressLint("PrivateApi")
    private fun getSystemProperty(key: String, default: String? = null): String? {
        return try {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val getMethod =
                systemProperties.getMethod("get", String::class.java, String::class.java)
            val result = getMethod.invoke(null, key, default ?: "") as? String
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            default
        }
    }

    /**
     * 从 URL 中提取 Expires 参数（Unix 时间戳）
     * @return 过期时间戳（秒），如果不存在返回 null
     */
    fun extractExpiresTimestamp(url: String): Long? {
        return try {
            val uri = URL(url)
            uri.query?.split("&")
                ?.firstOrNull { it.startsWith("Expires=") }
                ?.substringAfter("Expires=")
                ?.toLongOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算剩余时间并格式化为 "X天X时X分X秒" 或 "已过期"
     */
    fun formatRemainingTime(expiresTimestamp: Long, context: Context): String {
        val now = System.currentTimeMillis() / 1000 // 当前时间戳（秒）
        val remaining = expiresTimestamp - now

        if (remaining <= 0) {
            return context.getString(R.string.url_expired)
        }

        val days = remaining / 86400
        val hours = (remaining % 86400) / 3600
        val minutes = (remaining % 3600) / 60
        val seconds = remaining % 60

        return buildString {
            if (days > 0) append("${days}:")
            if (hours > 0 || days > 0) append("${hours}:")
            if (minutes > 0 || hours > 0 || days > 0) append("${minutes}:")
            append("$seconds")
        }
    }

}