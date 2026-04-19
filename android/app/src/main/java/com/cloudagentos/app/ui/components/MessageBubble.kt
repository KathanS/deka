package com.cloudagentos.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudagentos.app.data.ChatMessage
import com.cloudagentos.app.data.MessageRole
import com.cloudagentos.app.ui.theme.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MessageBubble(message: ChatMessage, modifier: Modifier = Modifier) {
    val isUser = message.role == MessageRole.USER
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = if (isUser) 52.dp else 16.dp,
                end = if (isUser) 16.dp else 52.dp,
                top = 4.dp,
                bottom = 4.dp
            ),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            // Agent avatar — gradient ring
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(AccentGreen, AccentCyan))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(AgentBubble),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▲", fontSize = 11.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.weight(1f, fill = false)
        ) {
            // Bubble
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = if (isUser) 20.dp else 6.dp,
                            bottomEnd = if (isUser) 6.dp else 20.dp
                        )
                    )
                    .then(
                        if (isUser) {
                            Modifier.background(
                                Brush.linearGradient(listOf(AccentGreen, AccentGreen.copy(alpha = 0.85f)))
                            )
                        } else {
                            Modifier
                                .background(AgentBubble)
                                .border(
                                    1.dp, AgentBubbleBorder,
                                    RoundedCornerShape(
                                        topStart = 20.dp, topEnd = 20.dp,
                                        bottomStart = 6.dp, bottomEnd = 20.dp
                                    )
                                )
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    if (!isUser && message.agentTag != null && message.agentTag != "deka") {
                        Text(
                            text = message.agentTag.uppercase(),
                            color = agentTagColor(message.agentTag),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }

                    if (message.isStreaming && message.content.startsWith("🤖")) {
                        // Status message — show with accent
                        Text(
                            text = message.content.removePrefix("🤖 "),
                            color = AccentAmber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                        ThinkingDots()
                    } else {
                        val textColor = if (isUser) TextOnAccent else TextPrimary
                        val displayText = message.content + if (message.isStreaming) " ●" else ""
                        Text(
                            text = parseMarkdown(displayText, textColor),
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 21.sp
                        )
                    }
                }
            }

            // Timestamp
            Text(
                text = timeFormatter.format(message.timestamp),
                color = TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp, start = 6.dp, end = 6.dp)
            )
        }
    }
}

@Composable
private fun ThinkingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse),
        label = "alpha"
    )
    Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Box(
                modifier = Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(AccentAmber.copy(alpha = dotAlpha * (1f - i * 0.2f)))
            )
        }
    }
}

fun agentTagColor(tag: String): Color = when (tag.lowercase()) {
    "weather" -> AgentWeatherColor
    "calendar" -> AgentCalendarColor
    "reminder" -> AgentReminderColor
    "deka" -> AccentGreen
    else -> AgentDefaultColor
}

private fun parseMarkdown(text: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // **bold**
                i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > 0) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = baseColor)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i]); i++
                    }
                }
                // *italic*
                text[i] == '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = baseColor)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                // `code`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > 0) {
                        withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, color = AccentCyan)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i]); i++
                    }
                }
                // - bullet points → •
                (i == 0 || text[i - 1] == '\n') && text[i] == '-' && i + 1 < text.length && text[i + 1] == ' ' -> {
                    append("  •")
                    i += 1 // skip the dash, keep the space
                }
                else -> {
                    withStyle(SpanStyle(color = baseColor)) { append(text[i]) }
                    i++
                }
            }
        }
    }
}
