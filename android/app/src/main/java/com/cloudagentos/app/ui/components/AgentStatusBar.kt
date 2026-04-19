package com.cloudagentos.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudagentos.app.data.ActionConfirm
import com.cloudagentos.app.data.ConnectionState
import com.cloudagentos.app.ui.theme.*

@Composable
fun ConnectionStatusIndicator(state: ConnectionState, modifier: Modifier = Modifier) {
    val (color, label) = when (state) {
        is ConnectionState.Connected -> StatusConnected to "Ready"
        is ConnectionState.Disconnected -> StatusDisconnected to "Offline"
        is ConnectionState.Reconnecting -> StatusReconnecting to "Reconnecting"
        is ConnectionState.Error -> StatusDisconnected to "Error"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AgentStatusBar(activeAgent: String?, modifier: Modifier = Modifier) {
    // No longer used in v3 premium UI — status is in top bar
}

@Composable
fun ActionConfirmCard(
    action: ActionConfirm?,
    onApprove: (String) -> Unit,
    onDeny: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = action != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        action?.let { act ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceElevated)
                    .border(1.dp, AccentAmber.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AccentAmber.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "CONFIRM ACTION",
                            color = AccentAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (act.agentTag != null) {
                            Text(act.agentTag, color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = act.description,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 21.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { onApprove(act.actionId) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen,
                            contentColor = TextOnAccent
                        )
                    ) { Text("Approve", fontWeight = FontWeight.SemiBold) }
                    OutlinedButton(
                        onClick = { onDeny(act.actionId) },
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) { Text("Deny", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
