// SPDX-License-Identifier: GPL-3.0-or-later

// PortalLoginActivity.kt - WebView-basierter Portal-Login
package com.example.obsliterecorder.portal

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.obsliterecorder.ui.OBSColors

/**
 * Activity fuer den Portal-Login via WebView.
 * Nach dem Login werden die Cookies synchronisiert.
 */
class PortalLoginActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BASE_URL = "base_url"
        const val RESULT_LOGGED_IN = "logged_in"
    }

    private var baseUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: ""

        if (baseUrl.isBlank()) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                PortalLoginScreen(
                    baseUrl = baseUrl,
                    onFinish = { success ->
                        if (success) {
                            // Cookies synchronisieren
                            syncCookies()
                            setResult(RESULT_OK)
                        } else {
                            setResult(RESULT_CANCELED)
                        }
                        finish()
                    }
                )
            }
        }
    }

    private fun syncCookies() {
        // CookieManager synchronisiert automatisch mit OkHttp wenn akzeptiert
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        // Debug: Cookies ausgeben
        val cookies = cookieManager.getCookie(baseUrl)
        android.util.Log.d("PortalLogin", "Cookies nach Login: $cookies")
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PortalLoginScreen(
    baseUrl: String,
    onFinish: (success: Boolean) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var pageTitle by remember { mutableStateOf("Portal-Login") }
    var currentUrl by remember { mutableStateOf("") }

    // Login-URL erstellen
    val loginUrl = remember(baseUrl) {
        val normalized = baseUrl.trimEnd('/')
        "$normalized/login"
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        Surface(
            color = OBSColors.CardBackground,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Abbrechen Button
                IconButton(onClick = { onFinish(false) }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Abbrechen",
                        tint = OBSColors.Danger
                    )
                }

                // Titel
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        pageTitle,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 32.dp)
                                .height(2.dp),
                            color = OBSColors.Accent
                        )
                    }
                }

                // Fertig Button
                IconButton(onClick = { onFinish(true) }) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Fertig",
                        tint = OBSColors.Good
                    )
                }
            }
        }

        // Hinweis-Banner
        Surface(
            color = OBSColors.Accent.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "Bitte im Portal einloggen, dann auf den Haken tippen.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = OBSColors.Accent,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // WebView
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true

                    // Cookies aktivieren
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            isLoading = false
                            currentUrl = url ?: ""
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            isLoading = true
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            pageTitle = title ?: "Portal-Login"
                        }
                    }

                    loadUrl(loginUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
