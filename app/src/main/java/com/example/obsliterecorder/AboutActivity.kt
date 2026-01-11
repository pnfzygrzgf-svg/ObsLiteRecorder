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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import androidx.core.text.util.LinkifyCompat

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit) {
    val bg = Color(0xFFF2F2F7)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 18.dp, bottom = 24.dp)
        ) {
            HeaderIOSBack(title = stringResource(R.string.about_app_title), onBack = onBack)

            Spacer(Modifier.height(14.dp))

            CardIOS {
                Text("Über die App", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                HtmlText(html = stringResource(R.string.about_app_intro))
            }

            Spacer(Modifier.height(14.dp))

            CardIOS {
                Text(stringResource(R.string.about_app_credits_header), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                HtmlText(html = stringResource(R.string.about_app_credits))
            }

            Spacer(Modifier.height(14.dp))

            CardIOS {
                Text(stringResource(R.string.about_app_license_header), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                HtmlText(html = stringResource(R.string.about_app_license))
            }
        }
    }
}

@Composable
private fun HeaderIOSBack(title: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(36.dp),
            shape = CircleShape,
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            TextButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Zurück",
                    tint = Color(0xFF0A84FF)
                )
            }
        }
    }
}

@Composable
private fun HtmlText(html: String, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current

    // HTML -> Spanned
    val spanned: Spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)

    // Spanned -> Spannable + autolink für "nackte" URLs (Credits/Lizenz)
    val spannable = SpannableString(spanned)
    LinkifyCompat.addLinks(spannable, android.text.util.Linkify.WEB_URLS)

    // Spannable -> AnnotatedString (Compose-klickbar)
    val annotated = spannedToAnnotatedString(spannable)

    ClickableText(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF111827)),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()
                ?.let { uriHandler.openUri(it.item) }
        }
    )
}

private fun spannedToAnnotatedString(spanned: Spanned): AnnotatedString {
    val linkColor = Color(0xFF0A84FF)

    return buildAnnotatedString {
        append(spanned.toString())

        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
        for (span in spans) {
            val start = spanned.getSpanStart(span).coerceAtLeast(0)
            val end = spanned.getSpanEnd(span).coerceAtMost(spanned.length)
            if (start >= end) continue

            addStringAnnotation("URL", span.url, start, end)
            addStyle(
                SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
                start,
                end
            )
        }
    }
}

@Composable
private fun CardIOS(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}
