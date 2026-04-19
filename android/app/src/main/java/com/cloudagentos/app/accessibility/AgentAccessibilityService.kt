package com.cloudagentos.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cloudagentos.app.config.Flags
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * CloudAgentOS AccessibilityService
 *
 * Runs on the user's phone. Reads screen UI hierarchy and executes
 * actions (tap, type, swipe) as directed by the cloud server.
 *
 * The user's data never leaves the phone — only the UI element tree
 * (text, positions, types) is sent to the server for action planning.
 */
class AgentAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gson = Gson()

    private val _screenState = MutableSharedFlow<String>(replay = 1)
    val screenState = _screenState.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    companion object {
        private const val TAG = "AgentA11y"
        var instance: AgentAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isRunning.value = true

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onDestroy() {
        instance = null
        _isRunning.value = false
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We use on-demand screen reads instead of event-driven updates
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService interrupted")
    }

    // ── Screen reading ───────────────────────────────────────────────

    /**
     * Capture the current screen state as a JSON string.
     * Traverses the accessibility tree and extracts interactive elements.
     */
    fun captureScreenState(): String {
        val root = rootInActiveWindow ?: return "{\"error\": \"no_window\"}"

        val elements = mutableListOf<JsonObject>()
        traverseTree(root, elements)

        val state = JsonObject().apply {
            addProperty("package_name", root.packageName?.toString() ?: "")
            addProperty("activity_name", "")  // Activity name not easily available
            addProperty("screen_width", resources.displayMetrics.widthPixels)
            addProperty("screen_height", resources.displayMetrics.heightPixels)
            addProperty("timestamp", System.currentTimeMillis() / 1000.0)
            add("elements", gson.toJsonTree(elements))
        }

        root.recycle()
        return state.toString()
    }

    // ── Screenshot ───────────────────────────────────────────────

    /**
     * Take a screenshot and return it as base64-encoded JPEG.
     * Requires Android 11+ (API 30) and canTakeScreenshot=true in config.
     * Returns null if not available.
     */
    suspend fun takeScreenshotBase64(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot requires Android 11+")
            return null
        }

        val result = CompletableDeferred<String?>()

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace
                        )
                        if (bitmap == null) {
                            Log.e(TAG, "Failed to create bitmap from screenshot")
                            result.complete(null)
                            return
                        }

                        // Convert to software bitmap for compression
                        val swBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                        bitmap.recycle()
                        screenshot.hardwareBuffer.close()

                        // Scale down to save tokens (max 1280px on long side)
                        val scale = Flags.SCREENSHOT_MAX_SIZE.toFloat() / maxOf(swBitmap.width, swBitmap.height)
                        val scaledBitmap = if (scale < 1f) {
                            val sw = (swBitmap.width * scale).toInt()
                            val sh = (swBitmap.height * scale).toInt()
                            val scaled = Bitmap.createScaledBitmap(swBitmap, sw, sh, true)
                            swBitmap.recycle()
                            scaled
                        } else {
                            swBitmap
                        }

                        // Compress to JPEG (quality 60 — good enough for vision, small size)
                        val baos = ByteArrayOutputStream()
                        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, Flags.SCREENSHOT_QUALITY, baos)
                        scaledBitmap.recycle()

                        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        Log.i(TAG, "Screenshot captured: ${base64.length} chars base64")
                        result.complete(base64)
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot processing failed", e)
                        result.complete(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed with error code: $errorCode")
                    result.complete(null)
                }
            }
        )

        return withTimeoutOrNull(5000) { result.await() }
    }

    private fun traverseTree(node: AccessibilityNodeInfo, elements: MutableList<JsonObject>) {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resId = node.viewIdResourceName ?: ""
        val className = node.className?.toString() ?: ""
        val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString() ?: "" else ""

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip zero-size and off-screen elements
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            // Still traverse children
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseTree(child, elements)
                child.recycle()
            }
            return
        }
        val isVisible = bounds.right > 0 && bounds.bottom > 0 && bounds.left < screenW && bounds.top < screenH

        val hasContent = text.isNotEmpty() || desc.isNotEmpty() || hintText.isNotEmpty()
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable || node.isLongClickable

        if ((hasContent || isInteractive) && isVisible) {
            elements.add(JsonObject().apply {
                if (text.isNotEmpty()) addProperty("text", text)
                if (desc.isNotEmpty()) addProperty("content_desc", desc)
                if (hintText.isNotEmpty()) addProperty("hint", hintText)
                if (resId.isNotEmpty()) addProperty("resource_id", resId)
                addProperty("class_name", className.substringAfterLast('.'))
                add("bounds", gson.toJsonTree(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                if (node.isClickable) addProperty("clickable", true)
                if (node.isEditable) addProperty("editable", true)
                if (node.isScrollable) addProperty("scrollable", true)
                if (node.isCheckable) {
                    addProperty("checkable", true)
                    addProperty("checked", node.isChecked)
                }
                if (node.isSelected) addProperty("selected", true)
                if (!node.isEnabled) addProperty("enabled", false)
                if (node.isFocused) addProperty("focused", true)
                if (node.isLongClickable) addProperty("long_clickable", true)
            })
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseTree(child, elements)
            child.recycle()
        }
    }

    // ── Action execution ─────────────────────────────────────────────

    /**
     * Click a node by finding it in the accessibility tree matching the given element.
     * Returns true if a clickable node was found and clicked.
     */
    fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Clicked node by text: '$text'")
                node.recycle()
                root.recycle()
                return true
            }
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) {
                if (parent.isClickable) {
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.d(TAG, "Clicked parent of '$text'")
                    parent.recycle()
                    node.recycle()
                    root.recycle()
                    return true
                }
                val grandParent = parent.parent
                parent.recycle()
                parent = grandParent
                depth++
            }
            parent?.recycle()
            node.recycle()
        }
        root.recycle()
        return false
    }

    /**
     * Tap at coordinates using gesture dispatch.
     * Uses ViewConfiguration.getTapTimeout() for proper tap recognition.
     * Tries gesture first, falls back to node ACTION_CLICK.
     */
    fun executeTap(x: Int, y: Int, callback: (() -> Unit)? = null) {
        Log.i(TAG, "Executing tap at ($x, $y)")

        // First try: find and click the AccessibilityNode at these coords
        val root = rootInActiveWindow
        if (root != null) {
            val node = findClickableNodeAt(root, x, y)
            if (node != null) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Node ACTION_CLICK at ($x,$y): $result")
                node.recycle()
                root.recycle()
                if (result) {
                    callback?.invoke()
                    return
                }
            } else {
                root.recycle()
            }
        }

        // Second try: dispatchGesture with proper tap duration
        val tapDuration = android.view.ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(50L)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, tapDuration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.i(TAG, "Gesture tap OK at ($x, $y)")
                callback?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture tap CANCELLED at ($x, $y) — trying longer tap")
                // Third try: longer tap duration (some devices need this)
                val longPath = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val longStroke = GestureDescription.StrokeDescription(longPath, 0, 300)
                val longGesture = GestureDescription.Builder().addStroke(longStroke).build()
                dispatchGesture(longGesture, object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        Log.i(TAG, "Long gesture tap OK at ($x, $y)")
                        callback?.invoke()
                    }
                    override fun onCancelled(g: GestureDescription?) {
                        Log.e(TAG, "All tap attempts FAILED at ($x, $y)")
                        callback?.invoke()
                    }
                }, null)
            }
        }, null)

        if (!dispatched) {
            Log.e(TAG, "dispatchGesture returned false at ($x, $y)")
            callback?.invoke()
        }
    }

    /**
     * Find the most specific clickable node at given screen coordinates.
     */
    private fun findClickableNodeAt(node: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (!bounds.contains(x, y)) return null

        // Check children (more specific) first
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findClickableNodeAt(child, x, y)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }

        // This node contains the point — return if clickable
        if (node.isClickable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        return null
    }

    /**
     * Type text into the currently focused or a specific editable field.
     */
    fun executeType(text: String) {
        val root = rootInActiveWindow ?: return
        var target = findFocusedEditable(root)
        if (target == null) {
            target = findFirstEditable(root)
            target?.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }
        if (target != null) {
            val existing = target.text?.toString() ?: ""
            val newText = if (text.startsWith("+")) {
                existing + text.removePrefix("+")
            } else {
                text
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            target.recycle()
        } else {
            Log.w(TAG, "No editable field found for typing")
        }
        root.recycle()
    }

    fun executeSwipe(direction: String) {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollableNode(root)
            if (scrollable != null) {
                val scrollAction = when (direction) {
                    "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                scrollable.performAction(scrollAction)
                Log.d(TAG, "Node-scrolled $direction")
                scrollable.recycle()
                root.recycle()
                return
            }
            root.recycle()
        }

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val path = Path()

        when (direction) {
            "up" -> {
                path.moveTo(screenW / 2f, screenH * 0.7f)
                path.lineTo(screenW / 2f, screenH * 0.3f)
            }
            "down" -> {
                path.moveTo(screenW / 2f, screenH * 0.3f)
                path.lineTo(screenW / 2f, screenH * 0.7f)
            }
            "left" -> {
                path.moveTo(screenW * 0.8f, screenH / 2f)
                path.lineTo(screenW * 0.2f, screenH / 2f)
            }
            "right" -> {
                path.moveTo(screenW * 0.2f, screenH / 2f)
                path.lineTo(screenW * 0.8f, screenH / 2f)
            }
            else -> return
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Press the back button.
     */
    fun executeBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Press the home button.
     */
    fun executeHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Launch an app in background — start it but immediately bring our app back.
     * The target app's accessibility tree will still be readable.
     */
    fun launchApp(packageName: String): Boolean {
        var intent = packageManager.getLaunchIntentForPackage(packageName)

        if (intent == null) {
            val mainIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            if (resolveInfos.isNotEmpty()) {
                val activity = resolveInfos[0].activityInfo
                intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activity.packageName, activity.name)
                }
            }
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                applicationContext.startActivity(intent)
                Log.i(TAG, "Launched app: $packageName")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch $packageName", e)
                false
            }
        }

        Log.w(TAG, "App not found: $packageName")
        return false
    }

    /**
     * Bring CloudAgentOS back to foreground.
     */
    fun bringBackOurApp() {
        val intent = packageManager.getLaunchIntentForPackage("com.cloudagentos.app")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            applicationContext.startActivity(intent)
            Log.i(TAG, "Switched back to CloudAgentOS")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun findFocusedEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFirstEditable(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }
}
