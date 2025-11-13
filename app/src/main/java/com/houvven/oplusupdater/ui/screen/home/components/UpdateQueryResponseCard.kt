package com.houvven.oplusupdater.ui.screen.home.components

import android.content.ClipData
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.houvven.oplusupdater.R
import com.houvven.oplusupdater.domain.UpdateQueryResponse
import com.houvven.oplusupdater.utils.StorageUnitUtil
import com.houvven.oplusupdater.utils.UrlDecryptUtil
import com.houvven.oplusupdater.utils.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.extra.RightActionColors
import top.yukonga.miuix.kmp.extra.SuperArrow
import top.yukonga.miuix.kmp.extra.SuperArrowDefaults
import updater.ResponseResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.compareTo

@Composable
fun UpdateQueryResponseCard(
    response: ResponseResult,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (response.responseCode.toInt() != 200) {
        Card {
            SuperArrowWrapper(
                title = "status",
                summary = response.responseCode.toString()
            )
            SuperArrowWrapper(
                title = "message",
                summary = response.errMsg
            )
        }
        return
    }

    runCatching {
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString<UpdateQueryResponse>(response.decryptedBodyBytes.decodeToString())
    }.onSuccess {
        UpdateQueryResponseCardContent(
            modifier = modifier,
            response = it
        )
    }.onFailure {
        it.message?.let(context::toast)
    }
}

@Composable
private fun SuperArrowWrapper(
    title: String,
    modifier: Modifier = Modifier,
    titleColor: BasicComponentColors = BasicComponentDefaults.titleColor(),
    summary: String? = null,
    summaryColor: BasicComponentColors = BasicComponentDefaults.summaryColor(),
    leftAction: @Composable (() -> Unit)? = null,
    rightText: String? = null,
    rightActionColor: RightActionColors = SuperArrowDefaults.rightActionColors(),
    onClick: (() -> Unit)? = null,
) {
    val defaultActionColors = RightActionColors(
        color = Color.Transparent,
        disabledColor = Color.Transparent,
    )

    if (!summary.isNullOrBlank()) {
        SuperArrow(
            title = title,
            modifier = modifier,
            titleColor = titleColor,
            summary = summary,
            summaryColor = summaryColor,
            leftAction = leftAction,
            rightText = rightText,
            rightActionColor = if (onClick == null) defaultActionColors else rightActionColor,
            onClick = onClick,
        )
    }
}

@Composable
private fun UpdateQueryResponseCardContent(
    modifier: Modifier = Modifier,
    response: UpdateQueryResponse,
) = with(response) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var showUpdateLogDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card {
            SuperArrowWrapper(
                title = stringResource(R.string.version_type),
                summary = "$versionTypeH5 ($status)"
            )
            SuperArrowWrapper(
                title = stringResource(R.string.version_name),
                summary = realVersionName ?: versionName
            )
            (realOtaVersion ?: otaVersion)?.let {
                SuperArrowWrapper(
                    title = stringResource(R.string.ota_version),
                    summary = it
                )
            }
            SuperArrowWrapper(
                title = stringResource(R.string.android_version),
                summary = realAndroidVersion ?: androidVersion
            )
            SuperArrowWrapper(
                title = stringResource(R.string.os_version),
                summary = realOsVersion ?: colorOSVersion ?: osVersion
            )
            SuperArrowWrapper(
                title = stringResource(R.string.security_patch),
                summary = securityPatch ?: securityPatchVendor
            )
            SuperArrowWrapper(
                title = stringResource(R.string.published_time),
                summary = publishedTime?.let {
                    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault())
                        .format(Date(it))
                }
            )
            description?.panelUrl?.let {
                SuperArrowWrapper(
                    modifier = Modifier.combinedClickable(
                        onClick = { showUpdateLogDialog = true },
                        onLongClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText(it, it).toClipEntry())
                            }
                            context.toast(R.string.copied)
                        }
                    ),
                    title = stringResource(R.string.update_log),
                    summary = it
                )
            }
        }

        components?.forEach { component ->
            val componentPackets = component?.componentPackets ?: return@forEach
            var decryptedUrl by remember { mutableStateOf<String?>(null) }
            var isDecrypting by remember { mutableStateOf(false) }
            var expiresTime by remember { mutableStateOf<String?>(null) }
            var isEncryptedUrl by remember { mutableStateOf(false) }

            LaunchedEffect(componentPackets.manualUrl) {
                componentPackets.manualUrl?.let { url ->
                    println("url to decrypt: $url")
                    if (url.contains("downloadCheck")) {
                        isEncryptedUrl = true
                        isDecrypting = true
                        runCatching {
                            UrlDecryptUtil.resolveUrl(url)
                        }.onSuccess {
                            decryptedUrl = it
                        }.onFailure {
                            decryptedUrl = url // 失败时使用原 URL
                        }.also {
                            isDecrypting = false
                        }
                    } else {
                        isEncryptedUrl = false
                        decryptedUrl = url
                    }
                }
            }
            Card {
                val size = componentPackets.size?.toLongOrNull()?.let(StorageUnitUtil::formatSize)

                SuperArrowWrapper(
                    title = stringResource(R.string.packet_name),
                    summary = component.componentName,
                    rightText = size
                )

                // 倒计时更新（每秒刷新）
                LaunchedEffect(decryptedUrl, isEncryptedUrl) {
                    if (!isEncryptedUrl) return@LaunchedEffect
                    decryptedUrl?.let { url ->
                        UrlDecryptUtil.extractExpiresTimestamp(url)?.let { timestamp ->
                            while (true) {
                                expiresTime = UrlDecryptUtil.formatRemainingTime(timestamp, context)
                                delay(1000)
                                if (timestamp - System.currentTimeMillis() / 1000 <= 0) break
                            }
                        }
                    }
                }

                val finalUrl = decryptedUrl ?: componentPackets.url
                componentPackets.manualUrl?.let {
                    SuperArrowWrapper(
                        title = stringResource(R.string.packet_url),
                        summary = when {
                            isDecrypting -> context.getString(R.string.url_is_decrypting)
                            isEncryptedUrl && expiresTime != null -> "$finalUrl\n⏱ ${
                                context.getString(
                                    R.string.validity_period
                                )
                            }: $expiresTime"

                            else -> finalUrl
                        },
                        onClick = {
                            if (!isDecrypting && !finalUrl.isNullOrEmpty()) {
                                coroutineScope.launch {
                                    clipboard.setClipEntry(
                                        ClipData.newPlainText(finalUrl, finalUrl).toClipEntry()
                                    )
                                }
                                context.toast(R.string.copied)
                            }
                        }
                    )
                }
                componentPackets.md5?.let {
                    SuperArrowWrapper(
                        title = stringResource(R.string.packet_md5),
                        summary = componentPackets.md5,
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText(it, it).toClipEntry())
                            }
                            context.toast(R.string.copied)
                        }
                    )
                }
            }
        }
    }

    description?.panelUrl?.let {
        UpdateLogDialog(
            show = showUpdateLogDialog,
            url = it,
            softwareVersion = versionName ?: "Only god known it.",
            onDismissRequest = { showUpdateLogDialog = false }
        )
    }
}