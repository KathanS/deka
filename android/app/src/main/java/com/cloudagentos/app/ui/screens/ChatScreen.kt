package com.cloudagentos.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.cloudagentos.app.ui.components.*
import com.cloudagentos.app.ui.theme.*
import com.cloudagentos.app.viewmodel.ChatViewModel

@Composable
fun ChatScreen(
    chatViewModel: ChatViewModel = viewModel()
) {
    val messages by chatViewModel.messages.collectAsState()
    val isRecording by chatViewModel.isRecording.collectAsState()
    val isProcessing by chatViewModel.isProcessing.collectAsState()
    val pendingAction by chatViewModel.pendingAction.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch("android.permission.POST_NOTIFICATIONS")
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) chatViewModel.startRecording()
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Background)) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        ) {
            // ── Premium Top Bar ─────────────────────────────
            PremiumTopBar(
                isProcessing = isProcessing
            )

            // ── Chat Content ────────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyState()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            MessageBubble(message = message)
                        }
                    }
                }

                // Top fade gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Background, Color.Transparent)
                            )
                        )
                )
            }

            // ── Action Confirm Card ─────────────────────────
            ActionConfirmCard(
                action = pendingAction,
                onApprove = chatViewModel::approveAction,
                onDeny = chatViewModel::denyAction
            )

            // ── Premium Input Bar ───────────────────────────
            PremiumInputBar(
                textInput = textInput,
                onTextChange = { textInput = it },
                onSend = {
                    chatViewModel.sendText(textInput)
                    textInput = ""
                    keyboardController?.hide()
                },
                isRecording = isRecording,
                isProcessing = isProcessing,
                onStartRecording = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        chatViewModel.startRecording()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStopRecording = chatViewModel::stopRecording
            )
        }
    }
}

// ── Premium Top Bar ─────────────────────────────────────────

@Composable
private fun PremiumTopBar(
    isProcessing: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "topbar")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse),
        label = "glow"
    )

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Logo mark
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(listOf(AccentGreen, AccentCyan))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("▲", fontSize = 18.sp, color = TextOnAccent, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        "Deka",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isProcessing) AccentAmber.copy(alpha = glowAlpha)
                                    else StatusConnected
                                )
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            if (isProcessing) "Working..." else "Ready",
                            color = if (isProcessing) AccentAmber else TextMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

        }

        // Subtle divider
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, BorderSubtle, Color.Transparent)
                    )
                )
        )
    }
}

// ── Empty State ─────────────────────────────────────────────

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(AccentGreenSoft),
            contentAlignment = Alignment.Center
        ) {
            Text("▲", fontSize = 28.sp, color = AccentGreen, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "What can I do for you?",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "I can order food, book rides, shop groceries\u2014anything on your phone.",
            color = TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp
        )

        Spacer(modifier = Modifier.height(32.dp))

        SuggestionChip(text = "Order milk from Blinkit")
        Spacer(modifier = Modifier.height(8.dp))
        SuggestionChip(text = "Get biryani from Zomato")
        Spacer(modifier = Modifier.height(8.dp))
        SuggestionChip(text = "Book an Uber to airport")
    }
}

@Composable
private fun SuggestionChip(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text, color = TextSecondary, fontSize = 14.sp)
    }
}

// ── Premium Input Bar ───────────────────────────────────────

@Composable
private fun PremiumInputBar(
    textInput: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    isRecording: Boolean,
    isProcessing: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit
) {
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, BorderSubtle, Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .imePadding(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = onTextChange,
                placeholder = {
                    Text(
                        if (isProcessing) "Deka is working..." else "Ask Deka anything...",
                        color = TextMuted,
                        fontSize = 15.sp
                    )
                },
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (textInput.isNotBlank()) onSend() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentGreen.copy(alpha = 0.5f),
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = AccentGreen,
                    focusedContainerColor = SurfaceElevated,
                    unfocusedContainerColor = SurfaceElevated
                ),
                shape = RoundedCornerShape(20.dp),
                singleLine = false,
                maxLines = 4,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp)
            )

            VoiceButton(
                isRecording = isRecording,
                onStartRecording = onStartRecording,
                onStopRecording = onStopRecording
            )

            val canSend = textInput.isNotBlank() && !isProcessing
            FilledIconButton(
                onClick = { if (canSend) onSend() },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (canSend) AccentGreen else SurfaceElevated,
                    contentColor = if (canSend) TextOnAccent else TextMuted
                )
            ) {
                Icon(
                    Icons.Rounded.ArrowUpward,
                    contentDescription = "Send",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
