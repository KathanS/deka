package com.cloudagentos.app.ui.theme

import androidx.compose.ui.graphics.Color

// ── Core Surface System ─────────────────────────────────
val Background = Color(0xFF08090E)
val Surface = Color(0xFF0F1118)
val SurfaceElevated = Color(0xFF161821)
val CardBackground = Color(0xFF1A1D28)
val CardBackgroundHover = Color(0xFF22253A)

// ── Primary Accent (Emerald/Mint) ───────────────────────
val AccentGreen = Color(0xFF00E08E)
val AccentGreenSoft = Color(0xFF00E08E).copy(alpha = 0.12f)
val AccentGreenGlow = Color(0xFF00E08E).copy(alpha = 0.06f)

// ── Secondary Accents ───────────────────────────────────
val AccentCyan = Color(0xFF00C4EE)
val AccentAmber = Color(0xFFFFB020)
val AccentRose = Color(0xFFFF4466)
val AccentPurple = Color(0xFFA78BFA)

// ── Text System ─────────────────────────────────────────
val TextPrimary = Color(0xFFF0F2F5)
val TextSecondary = Color(0xFFB0B8C4)
val TextMuted = Color(0xFF5A6270)
val TextOnAccent = Color(0xFF0A0E14)

// ── Borders & Dividers ──────────────────────────────────
val DividerColor = Color(0xFF1E2130)
val BorderSubtle = Color(0xFF262940)
val GlassBorder = Color(0xFFFFFFFF).copy(alpha = 0.06f)

// ── Status Colors ───────────────────────────────────────
val StatusConnected = Color(0xFF00E08E)
val StatusDisconnected = Color(0xFFFF4466)
val StatusReconnecting = Color(0xFFFFB020)

// ── Agent Tag Colors ────────────────────────────────────
val AgentWeatherColor = AccentCyan
val AgentCalendarColor = AccentGreen
val AgentReminderColor = AccentAmber
val AgentDefaultColor = AccentPurple

// ── User Bubble ─────────────────────────────────────────
val UserBubble = Color(0xFF00E08E)
val UserBubbleText = Color(0xFF0A0E14)

// ── Agent Bubble ────────────────────────────────────────
val AgentBubble = Color(0xFF1A1D28)
val AgentBubbleBorder = Color(0xFF262940)
