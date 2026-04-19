package com.cloudagentos.app.config

import com.cloudagentos.app.agent.ModelBackend

/**
 * Feature flags for Deka.
 * Change these to toggle features without code changes.
 */
object Flags {

    // ── Model ───────────────────────────────────────────
    /** Which LLM backend to use */
    val DEFAULT_MODEL: ModelBackend = ModelBackend.CLAUDE

    // ── Vision ──────────────────────────────────────────
    /** Enable screenshot tool (sends actual image to LLM) */
    const val VISION_ENABLED: Boolean = true

    /** JPEG quality for screenshots (0-100). Lower = smaller = faster */
    const val SCREENSHOT_QUALITY: Int = 60

    /** Max screenshot dimension (scales down to save tokens) */
    const val SCREENSHOT_MAX_SIZE: Int = 1280

    // ── Agent Behavior ──────────────────────────────────
    /** Max tool-calling turns per request */
    const val MAX_TURNS: Int = 50

    /** Auto-wait ms after actions (tap, type, swipe) */
    const val POST_ACTION_DELAY_MS: Long = 800

    /** Max elements to send from read_screen */
    const val MAX_SCREEN_ELEMENTS: Int = 120

    /** Auto-switch back to Deka after task completion */
    const val AUTO_RETURN: Boolean = true

    // ── Conversation ────────────────────────────────────
    /** Max history items before trimming (per backend) */
    const val MAX_HISTORY_SIZE: Int = 40

    // ── Debug ───────────────────────────────────────────
    /** Log API request/response bodies */
    const val LOG_API_CALLS: Boolean = false

    /** Show tool names in status updates */
    const val SHOW_TOOL_STATUS: Boolean = true
}
