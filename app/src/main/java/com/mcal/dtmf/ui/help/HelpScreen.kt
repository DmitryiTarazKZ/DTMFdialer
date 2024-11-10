package com.mcal.dtmf.ui.help

import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.core.screen.ScreenKey
import cafe.adriel.voyager.core.screen.uniqueScreenKey
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.mcal.dtmf.navigator.AppScreen
import com.mcal.dtmf.utils.Utils.Companion.copyFileFromAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class HelpScreen : AppScreen {
    override val key: ScreenKey
        get() = uniqueScreenKey

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(text = "Описание и конструкция")
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                navigator.pop()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                "Back"
                            )
                        }
                    }
                )
            },
            content = { paddingValues ->
                Box(
                    modifier = Modifier
                        .padding(paddingValues)
                        .fillMaxSize()
                ) {
                    AndroidView(
                        factory = { context ->
                            WebView(context)
                        },
                        update = { webView ->
                            webView.settings.builtInZoomControls = true
                            webView.settings.displayZoomControls = false
                            webView.settings.setSupportZoom(true)
                            webView.setDownloadListener { url,
                                                          userAgent,
                                                          contentDisposition,
                                                          mimetype,
                                                          contentLength ->
                                if (url.contains("file:///android_asset/")) {
                                    Toast.makeText(
                                        webView.context,
                                        "Файл был сохранен в загрузки",
                                        Toast.LENGTH_LONG
                                    ).show()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        copyFileFromAssets(
                                            webView.context,
                                            url.replace("file:///android_asset/", "")
                                        )
                                    }
                                }
                            }
                            webView.loadUrl("file:///android_asset/help/index.html")
                        },
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        )
    }
}
