// OBSComponents.kt - Gemeinsame UI-Komponenten
package com.example.obsliterecorder.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standard Card im iOS-Style
 */
@Composable
fun OBSCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = OBSColors.CardBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/**
 * Header mit Titel und optionalem Info-Button
 */
@Composable
fun OBSHeader(
    title: String,
    onInfoTap: (() -> Unit)? = null
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        if (onInfoTap != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(36.dp),
                shape = CircleShape,
                color = Color.White,
                tonalElevation = 1.dp,
                shadowElevation = 2.dp
            ) {
                TextButton(onClick = onInfoTap) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Info",
                        tint = OBSColors.Accent
                    )
                }
            }
        }
    }
}

/**
 * Header mit Zurück-Button (für Sub-Screens)
 */
@Composable
fun OBSHeaderWithBack(
    title: String,
    onBack: () -> Unit,
    backIcon: ImageVector
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(36.dp),
            shape = CircleShape,
            color = Color.White,
            tonalElevation = 1.dp,
            shadowElevation = 2.dp
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = backIcon,
                    contentDescription = "Zurück",
                    tint = OBSColors.Accent
                )
            }
        }

        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Status-Punkt (Dot) mit animierter Farbe
 */
@Composable
fun OBSStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Int = 10
) {
    val animatedColor by animateColorAsState(color, label = "dot_color")
    Surface(
        modifier = modifier.size(size.dp),
        shape = CircleShape,
        color = animatedColor
    ) {}
}

/**
 * Status-Chip (Pill) für Verbindungsstatus etc.
 */
@Composable
fun OBSStatusChip(
    text: String,
    backgroundColor: Color,
    textColor: Color
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = backgroundColor
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
    }
}

/**
 * iOS-Style Stepper (+/- Buttons)
 */
@Composable
fun OBSStepper(
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = OBSColors.Gray200
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onMinus) {
                Text("–", fontWeight = FontWeight.Bold, color = OBSColors.Gray900)
            }
            VerticalDivider(
                modifier = Modifier
                    .height(26.dp)
                    .width(1.dp),
                color = OBSColors.Gray300
            )
            TextButton(onClick = onPlus) {
                Text("+", fontWeight = FontWeight.Bold, color = OBSColors.Gray900)
            }
        }
    }
}

/**
 * Segmented Control (wie iOS)
 */
@Composable
fun OBSSegmentedControl(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = OBSColors.Gray200,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            items.forEachIndexed { idx, label ->
                val selected = idx == selectedIndex
                val bg = if (selected) Color.White else Color.Transparent
                val fg = if (selected) OBSColors.Gray900 else OBSColors.Gray500

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(999.dp))
                        .background(bg)
                        .clickable { onSelect(idx) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = fg, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Bottom Tab Bar (iOS-Style)
 */
@Composable
fun OBSBottomTabBar(
    modifier: Modifier = Modifier,
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(999.dp),
        color = Color.White.copy(alpha = 0.95f),
        shadowElevation = 12.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp)
        ) {
            val gap = 6.dp
            val itemWidth = (maxWidth - gap * (items.size - 1)) / items.size

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap)
            ) {
                items.forEachIndexed { idx, label ->
                    OBSTabItem(
                        modifier = Modifier.width(itemWidth),
                        selected = (selectedIndex == idx),
                        label = label,
                        onClick = { onSelect(idx) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OBSTabItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val bg = if (selected) OBSColors.Gray200 else Color.Transparent
    val fg = if (selected) OBSColors.Gray900 else OBSColors.Gray500

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = fg,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            fontSize = 14.sp
        )
    }
}

/**
 * Großer Aktions-Button (Record, Upload, etc.)
 */
@Composable
fun OBSPrimaryButton(
    modifier: Modifier = Modifier,
    text: String,
    enabled: Boolean = true,
    isActive: Boolean = false,
    disabledText: String? = null,
    onClick: () -> Unit
) {
    val gradient = if (isActive) {
        Brush.horizontalGradient(listOf(Color(0xFFFF453A), Color(0xFFFF3B30)))
    } else {
        Brush.horizontalGradient(listOf(OBSColors.Good, Color(0xFF30D158)))
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 58.dp)
            .clip(RoundedCornerShape(18.dp))
            .semantics {
                stateDescription = when {
                    !enabled -> disabledText ?: "Nicht verfügbar"
                    isActive -> "Aktiv"
                    else -> "Bereit"
                }
            },
        shadowElevation = if (enabled) 10.dp else 0.dp,
        color = Color.Transparent
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient, RoundedCornerShape(18.dp))
                .alpha(if (enabled) 1f else 0.55f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(12.dp),
                    shape = CircleShape,
                    color = Color.White
                ) {}

                Spacer(Modifier.width(10.dp))

                Text(
                    text = text,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!enabled && disabledText != null) {
                    Text(
                        disabledText,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

/**
 * Progress-Bar mit Farbe basierend auf Wert
 */
@Composable
fun OBSDistanceProgressBar(
    valueCm: Int?,
    modifier: Modifier = Modifier
) {
    val targetProgress = if (valueCm == null) 0f else (valueCm.coerceIn(0, 200) / 200f)
    val animatedProgress by animateFloatAsState(targetProgress, label = "progress")

    LinearProgressIndicator(
        progress = animatedProgress,
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(99.dp)),
        color = OBSColors.overtakeColor(valueCm),
        trackColor = OBSColors.Gray200
    )
}

/**
 * Bestätigungs-Dialog
 */
@Composable
fun OBSConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    dismissText: String = "Abbrechen",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    color = if (destructive) OBSColors.Danger else OBSColors.Accent
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText, color = OBSColors.Accent)
            }
        }
    )
}

/**
 * Animierter Zahlenwert
 */
@Composable
fun OBSAnimatedValue(
    value: Int?,
    suffix: String = "cm",
    modifier: Modifier = Modifier
) {
    val targetValue = value ?: 0
    val animatedValue by animateIntAsState(targetValue, label = "value")
    val displayValue = if (value == null) "–" else animatedValue.toString()

    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = modifier
    ) {
        Text(displayValue, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(suffix, color = OBSColors.Gray500)
    }
}
