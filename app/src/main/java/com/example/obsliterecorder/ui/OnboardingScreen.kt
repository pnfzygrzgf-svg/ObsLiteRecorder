package com.example.obsliterecorder.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val iconColor: androidx.compose.ui.graphics.Color,
    val title: String,
    val subtitle: String
)

private val pages = listOf(
    OnboardingPage(
        icon = Icons.AutoMirrored.Filled.DirectionsBike,
        iconColor = OBSColors.Accent,
        title = "Willkommen bei OBS Recorder",
        subtitle = "Erfasse Ueberholabstaende mit deinem OpenBikeSensor und dokumentiere die Sicherheit auf deinen Radwegen."
    ),
    OnboardingPage(
        icon = Icons.Default.Bluetooth,
        iconColor = OBSColors.Good,
        title = "Sensor verbinden",
        subtitle = "Der Sensor verbindet sich automatisch per Bluetooth oder USB-Kabel, sobald er eingeschaltet ist."
    ),
    OnboardingPage(
        icon = Icons.Default.FiberManualRecord,
        iconColor = OBSColors.Danger,
        title = "Aufnahme starten",
        subtitle = "Tippe auf den Button, um deine Fahrt aufzuzeichnen. Alle Ueberholvorgaenge werden automatisch gespeichert."
    )
)

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OBSColors.Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Pages
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { pageIndex ->
                OnboardingPageContent(pages[pageIndex])
            }

            // Page indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                repeat(pages.size) { index ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) OBSColors.Accent
                                else OBSColors.Gray300
                            )
                    )
                }
            }

            // Button
            Button(
                onClick = {
                    if (pagerState.currentPage < pages.size - 1) {
                        scope.launch {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        }
                    } else {
                        onComplete()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OBSColors.Accent)
            ) {
                Text(
                    text = if (pagerState.currentPage < pages.size - 1) "Weiter" else "Los geht's",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }

            // Skip
            if (pagerState.currentPage < pages.size - 1) {
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        "Ueberspringen",
                        color = OBSColors.Gray500,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = page.icon,
            contentDescription = null,
            tint = page.iconColor,
            modifier = Modifier.size(80.dp)
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = page.title,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = page.subtitle,
            color = OBSColors.Gray500,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )
    }
}
