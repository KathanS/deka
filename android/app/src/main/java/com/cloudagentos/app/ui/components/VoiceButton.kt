package com.cloudagentos.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cloudagentos.app.ui.theme.*

@Composable
fun VoiceButton(
    isRecording: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.25f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = EaseInOut), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(48.dp)) {
        if (isRecording) {
            Box(modifier = Modifier.size(48.dp).scale(pulseScale).clip(CircleShape)
                .background(AccentRose.copy(alpha = 0.12f)))
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(48.dp).clip(CircleShape)
                .background(if (isRecording) AccentRose.copy(alpha = 0.15f) else SurfaceElevated)
                .border(1.dp, if (isRecording) AccentRose.copy(alpha = 0.4f) else BorderSubtle, CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        onStartRecording()
                        tryAwaitRelease()
                        onStopRecording()
                    })
                }
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = if (isRecording) "Stop recording" else "Start recording",
                tint = if (isRecording) AccentRose else TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
