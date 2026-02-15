package com.example.obsliterecorder

import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.URLSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.core.text.util.LinkifyCompat
import com.example.obsliterecorder.ui.OBSCard
import com.example.obsliterecorder.ui.OBSColors
import com.example.obsliterecorder.ui.OBSHeaderWithBack

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                AboutScreen(
                    versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?",
                    onBack = { finish() }
                )
            }
        }
    }
}

@Composable
private fun AboutScreen(versionName: String, onBack: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OBSColors.Background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 24.dp)
        ) {
            OBSHeaderWithBack(
                title = stringResource(R.string.about_app_title),
                onBack = onBack,
                backIcon = Icons.AutoMirrored.Filled.ArrowBack
            )

            Spacer(Modifier.height(14.dp))

            // App Info Card
            OBSCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Sensors,
                        contentDescription = null,
                        tint = OBSColors.Accent,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "OBS Lite Recorder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            "Version $versionName",
                            color = OBSColors.Gray500,
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = OBSColors.CardBorder)
                Spacer(Modifier.height(12.dp))

                Text(
                    "Zeichnet Fahrten und Ueberholabstaende auf, die mit einem OpenBikeSensor Lite gemessen werden.",
                    color = OBSColors.Gray700,
                    fontSize = 14.sp
                )
            }

            Spacer(Modifier.height(12.dp))

            // Features Card
            OBSCard {
                Text("Funktionen", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))

                FeatureRow(Icons.Default.Usb, "USB-Verbindung zum OBS Lite")
                FeatureRow(Icons.Default.MyLocation, "GPS-Tracking waehrend der Fahrt")
                FeatureRow(Icons.Default.FiberManualRecord, "Aufnahme im BIN-Format")
                FeatureRow(Icons.Default.CloudUpload, "Upload zum OBS Portal")
                FeatureRow(Icons.Default.Map, "Kartenansicht mit Ueberholvorgaengen")
            }

            Spacer(Modifier.height(12.dp))

            // Credits Card
            OBSCard {
                Text(
                    stringResource(R.string.about_app_credits_header),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                HtmlText(html = stringResource(R.string.about_app_credits))
            }

            Spacer(Modifier.height(12.dp))

            // License Card
            OBSCard {
                Text(
                    stringResource(R.string.about_app_license_header),
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                HtmlText(html = stringResource(R.string.about_app_license))
            }

            Spacer(Modifier.height(12.dp))

            // Links Card
            OBSCard {
                Text("Links", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))

                val uriHandler = LocalUriHandler.current

                LinkRow(
                    icon = Icons.Default.Code,
                    label = "OpenBikeSensor Projekt",
                    url = "https://www.openbikesensor.org",
                    onClick = { uriHandler.openUri("https://www.openbikesensor.org") }
                )
                LinkRow(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    label = "OBS Lite Dokumentation",
                    url = "https://www.openbikesensor.org/docs/lite/",
                    onClick = { uriHandler.openUri("https://www.openbikesensor.org/docs/lite/") }
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = OBSColors.Good,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = OBSColors.Gray700, fontSize = 14.sp)
    }
}

@Composable
private fun LinkRow(icon: ImageVector, label: String, url: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = OBSColors.Accent,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(url, color = OBSColors.Accent, fontSize = 12.sp)
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = OBSColors.Gray400,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    val spanned: Spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
    val spannable = SpannableString(spanned)
    LinkifyCompat.addLinks(spannable, android.text.util.Linkify.WEB_URLS)
    val annotated = spannedToAnnotatedString(spannable)

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = OBSColors.Gray700),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { uriHandler.openUri(it.item) }
        }
    )
}

private fun spannedToAnnotatedString(spanned: Spanned): AnnotatedString {
    return buildAnnotatedString {
        append(spanned.toString())

        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        for (span in spans) {
            val start = spanned.getSpanStart(span).coerceAtLeast(0)
            val end = spanned.getSpanEnd(span).coerceAtMost(spanned.length)
            if (start >= end) continue

            addStringAnnotation("URL", span.url, start, end)
            addStyle(
                SpanStyle(color = OBSColors.Accent, textDecoration = TextDecoration.Underline),
                start,
                end
            )
        }
    }
}
