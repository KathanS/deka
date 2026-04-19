package com.cloudagentos.app.agent

import android.util.Log
import com.cloudagentos.app.accessibility.AgentAccessibilityService
import com.cloudagentos.app.config.Flags
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "DekaAgent"
private const val OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions"
private const val ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages"
private const val MAX_TOKENS = 4096

enum class ModelBackend(val modelName: String, val apiUrl: String) {
    OPENAI("gpt-5.4", OPENAI_CHAT_URL),
    CLAUDE("claude-sonnet-4-20250514", OPENAI_CHAT_URL)  // Via OpenAI-compatible endpoint or direct
}

/**
 * DekaAgent — Serverless AI agent that runs entirely on the phone.
 *
 * Supports two backends:
 * - OpenAI GPT-5.4 via Chat Completions API
 * - Claude Sonnet via Chat Completions API (OpenAI-compatible)
 *
 * Uses function calling to control the phone via AccessibilityService.
 */
class DekaAgent(
    private val getToken: suspend () -> String?,
    var backend: ModelBackend = ModelBackend.OPENAI
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // Callback for streaming status updates to the UI
    var onStatusUpdate: ((String) -> Unit)? = null
    var onComplete: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onConfirmNeeded: ((String, (Boolean) -> Unit) -> Unit)? = null

    // Conversation memory — persists across run() calls
    private val conversationHistory = mutableListOf<JsonObject>()

    private val systemPrompt = """You are Deka, an AI agent that controls Android apps by reading the screen and performing actions.

CRITICAL RULES:
1. ALWAYS call read_screen() FIRST to see what's currently displayed before any action.
2. After EVERY action (tap, type, swipe), call read_screen() again to verify it worked.
3. Use tap_text() as primary method — match the EXACT visible text you see from read_screen.
4. If tap_text fails, use tap_element() with the index number from read_screen results.
5. If both fail, use tap_coordinates() with the center of the element's bounds.
6. After typing in a search box, wait 2 seconds for results to load, then read_screen.
7. Before any purchase/booking, STOP and tell the user the total price and ask for confirmation.
8. NEVER enter passwords, OTPs, or payment PINs.
9. If the screen hasn't changed after 3 attempts, call screenshot() to visually see what's on screen.
10. When multiple items match, pick the first/cheapest unless user specified otherwise.
11. Use screenshot() when read_screen shows few elements, when you see unexpected results, or when dealing with image-heavy screens (product cards, maps, etc).

SCREEN READING:
- read_screen() gives you structured text elements. Fast, reliable for most screens.
- screenshot() gives you BOTH elements AND a visual image. Use when you need to see images, icons, colors, or when the element list isn't enough.

INTERACTION PATTERN:
- launch_app → wait(2) → read_screen → find search/button → tap_text → wait(1) → read_screen → ...
- If stuck: screenshot() to see the visual, then decide.

ELEMENT READING:
- Elements marked [clickable] can be tapped
- Elements marked [editable] are text input fields — tap them first, then type_text
- Elements marked [selected] indicate the currently active tab/option
- Elements marked [checked] indicate toggled/selected checkboxes
- The bounds format is (left,top,right,bottom) — center = ((left+right)/2, (top+bottom)/2)

APPS:
- Blinkit: com.grofers.customerapp (groceries, 10-min delivery)
- Zomato: com.application.zomato (restaurant food delivery)
- Swiggy: in.swiggy.android (food + Instamart groceries)
- Uber: com.ubercab (cab rides)
- Zepto: com.zeptonow.app (groceries, 10-min delivery)
- Any other installed app — just tell me the name

When the user asks to order something, follow this pattern:
1. Launch the app
2. Wait for it to load
3. Find the search bar and tap it
4. Type the search query
5. Wait for results
6. Pick the best match
7. Add to cart
8. Report back the item and price"""

    /**
     * Run the agent with a user message.
     * Routes to the appropriate backend (Responses API vs Chat Completions).
     */
    suspend fun run(userMessage: String): String {
        return when (backend) {
            ModelBackend.OPENAI -> runResponsesAPI(userMessage)
            ModelBackend.CLAUDE -> runChatCompletions(userMessage)
        }
    }

    // ── Responses API (gpt-5.4) ─────────────────────────────────

    private suspend fun runResponsesAPI(userMessage: String): String {
        // Add user message to conversation history
        conversationHistory.add(JsonObject().apply {
            addProperty("type", "message")
            addProperty("role", "user")
            add("content", JsonArray().also { arr ->
                arr.add(JsonObject().apply {
                    addProperty("type", "input_text")
                    addProperty("text", userMessage)
                })
            })
        })

        // Keep history manageable (last 20 turns)
        while (conversationHistory.size > 40) {
            conversationHistory.removeAt(0)
        }

        var turnCount = 0
        while (turnCount < Flags.MAX_TURNS) {
            turnCount++
            onStatusUpdate?.invoke("Thinking... (step $turnCount)")

            val body = JsonObject().apply {
                addProperty("model", backend.modelName)
                add("tools", buildToolDefinitionsResponsesAPI())
                addProperty("instructions", systemPrompt)
                add("input", gson.toJsonTree(conversationHistory))
            }

            Log.d(TAG, "API request: ${conversationHistory.size} history items, turn $turnCount")

            var response: JsonObject? = null
            for (attempt in 1..2) {
                response = callResponsesAPI(body)
                if (response != null) break
                if (attempt < 2) {
                    onStatusUpdate?.invoke("Retrying... (attempt $attempt)")
                    delay(2000)
                }
            }
            if (response == null) {
                AgentAccessibilityService.instance?.bringBackOurApp()
                onError?.invoke("Failed to reach AI — check your internet connection")
                return "Error: Could not connect to AI."
            }

            Log.d(TAG, "API response: ${response.toString().take(500)}")

            // Parse output
            val output = response.getAsJsonArray("output")
            if (output == null || output.size() == 0) {
                onError?.invoke("Empty response")
                return "Error: No response"
            }

            // Check for function calls
            val functionCalls = mutableListOf<JsonObject>()
            val textParts = mutableListOf<String>()

            for (item in output) {
                val obj = item.asJsonObject
                when (obj.get("type")?.asString) {
                    "function_call" -> functionCalls.add(obj)
                    "message" -> {
                        val content = obj.getAsJsonArray("content")
                        content?.forEach { c ->
                            val cObj = c.asJsonObject
                            if (cObj.get("type")?.asString == "output_text") {
                                textParts.add(cObj.get("text")?.asString ?: "")
                            }
                        }
                    }
                }
            }

            if (functionCalls.isNotEmpty()) {
                // Add assistant's function calls to history
                for (fc in functionCalls) {
                    conversationHistory.add(fc)
                }

                // Execute tools and add results to history
                for (fc in functionCalls) {
                    val callId = fc.get("call_id")?.asString ?: fc.get("id")?.asString ?: ""
                    val toolName = fc.get("name")?.asString ?: ""
                    val argsStr = fc.get("arguments")?.asString ?: "{}"
                    val input = try { JsonParser.parseString(argsStr).asJsonObject } catch (_: Exception) { JsonObject() }

                    Log.i(TAG, "Tool call: $toolName($input)")
                    onStatusUpdate?.invoke("$toolName...")

                    if (toolName == "screenshot") {
                        // Screenshot tool: take actual screenshot + accessibility tree
                        onStatusUpdate?.invoke("Taking screenshot...")
                        val service = AgentAccessibilityService.instance
                        val treeText = if (service != null) toolReadScreen(service) else "Accessibility service not running"
                        val screenshotB64 = service?.takeScreenshotBase64()

                        // Add function call output with tree text
                        conversationHistory.add(JsonObject().apply {
                            addProperty("type", "function_call_output")
                            addProperty("call_id", callId)
                            addProperty("output", treeText)
                        })

                        // If screenshot was captured, inject it as a user message with the image
                        if (screenshotB64 != null) {
                            conversationHistory.add(JsonObject().apply {
                                addProperty("type", "message")
                                addProperty("role", "user")
                                add("content", JsonArray().also { arr ->
                                    arr.add(JsonObject().apply {
                                        addProperty("type", "input_image")
                                        addProperty("image_url", "data:image/jpeg;base64,$screenshotB64")
                                    })
                                    arr.add(JsonObject().apply {
                                        addProperty("type", "input_text")
                                        addProperty("text", "Here is a screenshot of the current screen. Use this along with the element list above to decide what to do next. If you need to tap something, identify it from the element list or use tap_coordinates with pixel positions from the screenshot.")
                                    })
                                })
                            })
                        }
                    } else {
                        val result = executeTool(toolName, input)
                        conversationHistory.add(JsonObject().apply {
                            addProperty("type", "function_call_output")
                            addProperty("call_id", callId)
                            addProperty("output", result)
                        })
                    }
                }
                continue // Loop back with tool results
            }

            // No function calls — final text response
            val finalText = textParts.joinToString("\n")

            // Switch back to Deka so user sees the response
            AgentAccessibilityService.instance?.bringBackOurApp()

            // Add assistant response to history
            conversationHistory.add(JsonObject().apply {
                addProperty("type", "message")
                addProperty("role", "assistant")
                add("content", JsonArray().also { arr ->
                    arr.add(JsonObject().apply {
                        addProperty("type", "output_text")
                        addProperty("text", finalText)
                    })
                })
            })

            onComplete?.invoke(finalText)
            return finalText
        }

        AgentAccessibilityService.instance?.bringBackOurApp()
        onError?.invoke("Reached maximum steps (${Flags.MAX_TURNS})")
        return "I took too many steps. Please try a simpler request."
    }

    fun clearConversation() {
        conversationHistory.clear()
        chatMessages.clear()
    }

    // ── Chat Completions API (CLAUDE 4.6) ─────────────────────────

    private val chatMessages = mutableListOf<JsonObject>()

    private suspend fun runChatCompletions(userMessage: String): String {
        chatMessages.add(JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userMessage)
        })

        // Keep manageable
        while (chatMessages.size > 40) chatMessages.removeAt(0)

        var turnCount = 0
        while (turnCount < Flags.MAX_TURNS) {
            turnCount++
            onStatusUpdate?.invoke("Thinking... (step $turnCount)")

            val body = JsonObject().apply {
                addProperty("model", backend.modelName)
                addProperty("max_tokens", MAX_TOKENS)
                add("messages", JsonArray().also { arr ->
                    // System message
                    arr.add(JsonObject().apply {
                        addProperty("role", "system")
                        addProperty("content", systemPrompt)
                    })
                    chatMessages.forEach { arr.add(it) }
                })
                add("tools", buildChatTools())
            }

            Log.d(TAG, "Chat API: ${chatMessages.size} messages, turn $turnCount")

            var response: JsonObject? = null
            for (attempt in 1..2) {
                response = callChatAPI(body)
                if (response != null) break
                if (attempt < 2) { onStatusUpdate?.invoke("Retrying..."); delay(2000) }
            }
            if (response == null) {
                AgentAccessibilityService.instance?.bringBackOurApp()
                onError?.invoke("Failed to reach AI — check your internet connection")
                return "Error: Could not connect to AI."
            }

            val choice = response.getAsJsonArray("choices")?.get(0)?.asJsonObject
            val msg = choice?.getAsJsonObject("message") ?: run {
                onError?.invoke("Empty response"); return "Error: No response"
            }
            val finishReason = choice.get("finish_reason")?.asString

            // Add assistant message to history (clean copy)
            val hasToolCalls = msg.has("tool_calls") && msg.getAsJsonArray("tool_calls").size() > 0
            val cleanMsg = JsonObject().apply {
                addProperty("role", "assistant")
                if (hasToolCalls) {
                    add("tool_calls", msg.getAsJsonArray("tool_calls"))
                } else if (msg.has("content") && !msg.get("content").isJsonNull) {
                    addProperty("content", msg.get("content").asString)
                }
            }
            chatMessages.add(cleanMsg)

            if (hasToolCalls) {
                val toolCalls = msg.getAsJsonArray("tool_calls")

                for (tc in toolCalls) {
                    val tcObj = tc.asJsonObject
                    val id = tcObj.get("id")?.asString ?: ""
                    val fn = tcObj.getAsJsonObject("function")
                    val toolName = fn.get("name")?.asString ?: ""
                    val argsStr = fn.get("arguments")?.asString ?: "{}"
                    val input = try { JsonParser.parseString(argsStr).asJsonObject } catch (_: Exception) { JsonObject() }

                    Log.i(TAG, "Tool call: $toolName($input)")
                    onStatusUpdate?.invoke("$toolName...")

                    val result = if (toolName == "screenshot") {
                        onStatusUpdate?.invoke("Taking screenshot...")
                        val service = AgentAccessibilityService.instance
                        val treeText = if (service != null) toolReadScreen(service) else "Accessibility service not running"
                        val screenshotB64 = service?.takeScreenshotBase64()

                        // For chat completions, add tool result then a user message with image
                        chatMessages.add(JsonObject().apply {
                            addProperty("role", "tool")
                            addProperty("tool_call_id", id)
                            addProperty("content", treeText)
                        })
                        if (screenshotB64 != null) {
                            chatMessages.add(JsonObject().apply {
                                addProperty("role", "user")
                                add("content", JsonArray().also { arr ->
                                    arr.add(JsonObject().apply {
                                        addProperty("type", "image_url")
                                        add("image_url", JsonObject().apply {
                                            addProperty("url", "data:image/jpeg;base64,$screenshotB64")
                                        })
                                    })
                                    arr.add(JsonObject().apply {
                                        addProperty("type", "text")
                                        addProperty("text", "Screenshot of the current screen. Use this with the element list to decide what to do next.")
                                    })
                                })
                            })
                        }
                        null // Already added to chatMessages
                    } else {
                        executeTool(toolName, input)
                    }

                    if (result != null) {
                        chatMessages.add(JsonObject().apply {
                            addProperty("role", "tool")
                            addProperty("tool_call_id", id)
                            addProperty("content", result)
                        })
                    }
                }
                continue
            }

            // Final text response
            val finalText = msg.get("content")?.asString ?: ""
            AgentAccessibilityService.instance?.bringBackOurApp()
            onComplete?.invoke(finalText)
            return finalText
        }

        AgentAccessibilityService.instance?.bringBackOurApp()
        onError?.invoke("Reached maximum steps (${Flags.MAX_TURNS})")
        return "I took too many steps. Please try a simpler request."
    }

    private suspend fun callChatAPI(requestBody: JsonObject): JsonObject? {
        val token = getToken() ?: run {
            Log.e(TAG, "No valid token available")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(backend.apiUrl)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: return@withContext null
                if (response.isSuccessful) {
                    JsonParser.parseString(responseBody).asJsonObject
                } else {
                    Log.e(TAG, "Chat API error ${response.code}: $responseBody")
                    onStatusUpdate?.invoke("API error: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chat API call failed", e)
                null
            }
        }
    }

    private fun buildChatTools(): JsonArray {
        val tools = JsonArray()
        fun chatTool(name: String, desc: String, params: Map<String, String>): JsonObject {
            val properties = JsonObject()
            val required = JsonArray()
            for ((key, d) in params) {
                properties.add(key, JsonObject().apply {
                    addProperty("type", if (key in listOf("x", "y", "index", "seconds")) "integer" else "string")
                    addProperty("description", d)
                })
                if (key != "near_text") required.add(key)
            }
            return JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", name)
                    addProperty("description", desc)
                    add("parameters", JsonObject().apply {
                        addProperty("type", "object")
                        add("properties", properties)
                        add("required", required)
                    })
                })
            }
        }

        tools.add(chatTool("read_screen", "Read the current screen elements", emptyMap()))
        tools.add(chatTool("tap_text", "Tap element by text", mapOf("text" to "Text to tap", "near_text" to "Optional: disambiguation")))
        tools.add(chatTool("tap_element", "Tap by index from read_screen", mapOf("index" to "Element index")))
        tools.add(chatTool("tap_coordinates", "Tap at x,y", mapOf("x" to "X pixel", "y" to "Y pixel")))
        tools.add(chatTool("type_text", "Type into focused field", mapOf("text" to "Text to type")))
        tools.add(chatTool("swipe", "Swipe/scroll", mapOf("direction" to "up/down/left/right")))
        tools.add(chatTool("press_back", "Press back button", emptyMap()))
        tools.add(chatTool("launch_app", "Launch app by name", mapOf("app_name" to "App name")))
        tools.add(chatTool("wait", "Wait seconds", mapOf("seconds" to "1-10")))
        tools.add(chatTool("screenshot", "Take visual screenshot + elements", emptyMap()))

        return tools
    }

    // ── Responses API Call ───────────────────────────────────────

    private suspend fun callResponsesAPI(requestBody: JsonObject): JsonObject? {
        val token = getToken() ?: run {
            Log.e(TAG, "No valid token available")
            onStatusUpdate?.invoke("Token expired — reconnecting...")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(OPENAI_CHAT_URL)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: return@withContext null
                    JsonParser.parseString(responseBody).asJsonObject
                } else {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    Log.e(TAG, "API error ${response.code}: $errorBody")
                    onStatusUpdate?.invoke("API error: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "API call failed", e)
                null
            }
        }
    }

    // ── Tool Definitions (Responses API format) ─────────────────

    private fun buildToolDefinitionsResponsesAPI(): JsonArray {
        val tools = JsonArray()

        tools.add(responseTool("read_screen",
            "Read the current screen. Returns ALL visible UI elements with text, position, and state (clickable, editable, selected, etc). ALWAYS call this before taking any action.",
            emptyMap()))

        tools.add(responseTool("tap_text",
            "Find and tap an element by its visible text. Uses exact match first, then partial match. Most reliable tap method.",
            mapOf("text" to "The visible text to tap (case-insensitive). Use the EXACT text from read_screen results.",
                  "near_text" to "Optional: when multiple matches exist, tap the one nearest to this text (for disambiguation)")))

        tools.add(responseTool("tap_element",
            "Tap a UI element by its index number from read_screen results. Use when tap_text doesn't work or for elements without text.",
            mapOf("index" to "The element index number [N] from read_screen output")))

        tools.add(responseTool("tap_coordinates",
            "Tap at exact screen coordinates. Last resort — use only when other methods fail. Calculate center from bounds: x=(left+right)/2, y=(top+bottom)/2.",
            mapOf("x" to "X coordinate (pixels from left)", "y" to "Y coordinate (pixels from top)")))

        tools.add(responseTool("type_text",
            "Type text into the currently focused input field. Tap an editable field first to focus it. Replaces existing text unless prefixed with '+' to append.",
            mapOf("text" to "Text to type. Prefix with '+' to append to existing text instead of replacing.")))

        tools.add(responseTool("swipe",
            "Swipe/scroll the screen in a direction. Use 'up' to scroll down (see more content below), 'down' to scroll up.",
            mapOf("direction" to "Direction: up (scroll down to see more), down (scroll up), left, right")))

        tools.add(responseTool("press_back",
            "Press the Android back button. Use to go back to previous screen, dismiss popups/dialogs, or close keyboard.",
            emptyMap()))

        tools.add(responseTool("launch_app",
            "Launch any app by name. Supports 30+ apps: blinkit, zomato, swiggy, uber, ola, rapido, zepto, amazon, flipkart, whatsapp, instagram, youtube, maps, chrome, paytm, phonepe, gpay, spotify, myntra, and more.",
            mapOf("app_name" to "Name of the app (e.g., 'blinkit', 'zomato', 'whatsapp')")))

        tools.add(responseTool("wait",
            "Wait for a specified number of seconds. Use after launching apps (2-3s), after taps that load new screens (1-2s), or after typing in search (2s for results).",
            mapOf("seconds" to "Number of seconds to wait (1-10)")))

        tools.add(responseTool("screenshot",
            "Take a VISUAL screenshot of the screen. Returns both the element list AND an actual image of the screen. Use this when read_screen doesn't give enough info, when you see images/icons without text, or when you're stuck and need to visually see the screen. More accurate than read_screen but slower.",
            emptyMap()))

        return tools
    }

    private fun responseTool(name: String, description: String, params: Map<String, String>): JsonObject {
        val properties = JsonObject()
        val required = JsonArray()
        for ((key, desc) in params) {
            properties.add(key, JsonObject().apply {
                addProperty("type", if (key in listOf("x", "y", "index", "seconds")) "integer" else "string")
                addProperty("description", desc)
            })
            if (key != "near_text") required.add(key)
        }

        return JsonObject().apply {
            addProperty("type", "function")
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", JsonObject().apply {
                addProperty("type", "object")
                add("properties", properties)
                add("required", required)
            })
        }
    }

    // ── Tool Execution ──────────────────────────────────────────

    private suspend fun executeTool(name: String, input: JsonObject): String {
        val service = AgentAccessibilityService.instance
        if (service == null && name != "launch_app" && name != "wait") {
            return "Error: Accessibility service not running. Please enable it in Settings > Accessibility > Deka."
        }

        val result = when (name) {
            "read_screen" -> toolReadScreen(service!!)
            "tap_text" -> toolTapText(service!!, input)
            "tap_element" -> toolTapElement(service!!, input)
            "tap_coordinates" -> toolTapCoordinates(service!!, input)
            "type_text" -> toolTypeText(service!!, input)
            "swipe" -> { toolSwipe(service!!, input) }
            "press_back" -> { service!!.executeBack(); "Pressed back button." }
            "launch_app" -> toolLaunchApp(input)
            "wait" -> { delay((input.get("seconds")?.asLong ?: 2) * 1000); "Waited." }
            "screenshot" -> toolReadScreen(service!!) // Fallback — actual screenshot handled in run loop
            else -> "Unknown tool: $name"
        }

        // Auto-wait after actions that change the screen (not read_screen/wait/screenshot)
        if (name in listOf("tap_text", "tap_element", "tap_coordinates", "type_text", "swipe", "press_back", "launch_app")) {
            delay(Flags.POST_ACTION_DELAY_MS) // Let the UI settle before next read
        }

        return result
    }

    private fun toolReadScreen(service: AgentAccessibilityService): String {
        val json = service.captureScreenState()
        val parsed = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return "Error reading screen" }

        val pkg = parsed.get("package_name")?.asString ?: "unknown"
        val screenW = parsed.get("screen_width")?.asInt ?: 0
        val screenH = parsed.get("screen_height")?.asInt ?: 0
        val elements = parsed.getAsJsonArray("elements") ?: return "No elements on screen (app: $pkg)"

        val sb = StringBuilder()
        sb.appendLine("=== SCREEN STATE ===")
        sb.appendLine("App: $pkg | Screen: ${screenW}x${screenH} | Elements: ${elements.size()}")
        sb.appendLine()

        var visibleCount = 0
        for (i in 0 until elements.size()) {
            val el = elements[i].asJsonObject
            val text = el.get("text")?.asString ?: ""
            val desc = el.get("content_desc")?.asString ?: ""
            val hint = el.get("hint")?.asString ?: ""
            val className = el.get("class_name")?.asString ?: ""
            val clickable = el.has("clickable")
            val editable = el.has("editable")
            val scrollable = el.has("scrollable")
            val selected = el.has("selected")
            val checked = el.get("checked")?.asBoolean
            val focused = el.has("focused")
            val enabled = el.get("enabled")?.asBoolean ?: true
            val bounds = el.getAsJsonArray("bounds")
            val boundsStr = if (bounds != null && bounds.size() == 4)
                "(${bounds[0].asInt},${bounds[1].asInt},${bounds[2].asInt},${bounds[3].asInt})"
            else ""

            val label = when {
                text.isNotEmpty() -> text
                desc.isNotEmpty() -> desc
                hint.isNotEmpty() -> "[$hint]"
                editable -> "[empty input field]"
                else -> continue
            }

            val flags = mutableListOf<String>()
            if (clickable) flags.add("clickable")
            if (editable) flags.add("editable")
            if (scrollable) flags.add("scrollable")
            if (selected) flags.add("SELECTED")
            if (checked != null) flags.add(if (checked) "CHECKED" else "unchecked")
            if (focused) flags.add("focused")
            if (!enabled) flags.add("disabled")

            val flagStr = if (flags.isNotEmpty()) " [${flags.joinToString(",")}]" else ""
            sb.appendLine("  [$i] $label$flagStr $boundsStr")
            visibleCount++

            // Safety: truncate at 120 elements to stay within token limits
            if (visibleCount >= Flags.MAX_SCREEN_ELEMENTS) {
                sb.appendLine("  ... (${elements.size() - i - 1} more elements truncated)")
                break
            }
        }

        if (visibleCount == 0) {
            sb.appendLine("  (no visible text elements — the screen may have images/custom views)")
        }

        return sb.toString()
    }

    private suspend fun toolTapText(service: AgentAccessibilityService, input: JsonObject): String {
        val targetText = input.get("text")?.asString ?: return "Error: no text specified"
        val nearText = input.get("near_text")?.asString

        // First try fast accessibility-native click (most reliable)
        val clicked = service.clickNodeByText(targetText)
        if (clicked) return "Tapped '$targetText' (native click)."

        // Fallback: find in screen state and gesture-tap
        val json = service.captureScreenState()
        val parsed = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return "Error reading screen" }
        val elements = parsed.getAsJsonArray("elements") ?: return "No elements found"

        data class Match(val index: Int, val el: JsonObject, val cx: Int, val cy: Int, val score: Int)

        val matches = mutableListOf<Match>()
        for (i in 0 until elements.size()) {
            val el = elements[i].asJsonObject
            val text = el.get("text")?.asString ?: ""
            val desc = el.get("content_desc")?.asString ?: ""
            val combined = "$text $desc"

            val score = when {
                text.equals(targetText, ignoreCase = true) || desc.equals(targetText, ignoreCase = true) -> 3
                text.startsWith(targetText, ignoreCase = true) || desc.startsWith(targetText, ignoreCase = true) -> 2
                combined.contains(targetText, ignoreCase = true) -> 1
                else -> 0
            }
            if (score > 0) {
                val bounds = el.getAsJsonArray("bounds")
                if (bounds != null && bounds.size() == 4) {
                    val cx = (bounds[0].asInt + bounds[2].asInt) / 2
                    val cy = (bounds[1].asInt + bounds[3].asInt) / 2
                    matches.add(Match(i, el, cx, cy, score))
                }
            }
        }

        if (matches.isEmpty()) return "Could not find '$targetText' on screen. Call read_screen() to see what's visible."

        matches.sortByDescending { it.score }

        val target = if (nearText != null && matches.size > 1) {
            var nearEl: Match? = null
            var nearDist = Int.MAX_VALUE
            for (i in 0 until elements.size()) {
                val el = elements[i].asJsonObject
                val t = (el.get("text")?.asString ?: "") + " " + (el.get("content_desc")?.asString ?: "")
                if (t.contains(nearText, ignoreCase = true)) {
                    val b = el.getAsJsonArray("bounds")
                    if (b != null && b.size() == 4) {
                        val nx = (b[0].asInt + b[2].asInt) / 2
                        val ny = (b[1].asInt + b[3].asInt) / 2
                        for (m in matches) {
                            val d = Math.abs(m.cx - nx) + Math.abs(m.cy - ny)
                            if (d < nearDist) { nearDist = d; nearEl = m }
                        }
                    }
                }
            }
            nearEl ?: matches.first()
        } else {
            matches.first()
        }

        val done = CompletableDeferred<Unit>()
        service.executeTap(target.cx, target.cy) { done.complete(Unit) }
        withTimeoutOrNull(3000) { done.await() }

        return "Tapped '${target.el.get("text")?.asString ?: targetText}' at (${target.cx}, ${target.cy})."
    }

    private suspend fun toolTapElement(service: AgentAccessibilityService, input: JsonObject): String {
        val index = input.get("index")?.asInt ?: return "Error: no index specified"

        val json = service.captureScreenState()
        val parsed = try { JsonParser.parseString(json).asJsonObject } catch (_: Exception) { return "Error reading screen" }
        val elements = parsed.getAsJsonArray("elements") ?: return "No elements"

        if (index < 0 || index >= elements.size()) return "Invalid index $index (max: ${elements.size() - 1})"

        val el = elements[index].asJsonObject
        val bounds = el.getAsJsonArray("bounds")
        if (bounds == null || bounds.size() != 4) return "Element $index has no bounds"

        val cx = (bounds[0].asInt + bounds[2].asInt) / 2
        val cy = (bounds[1].asInt + bounds[3].asInt) / 2

        val done = CompletableDeferred<Unit>()
        service.executeTap(cx, cy) { done.complete(Unit) }
        withTimeoutOrNull(3000) { done.await() }

        return "Tapped element [$index] '${el.get("text")?.asString ?: ""}' at ($cx, $cy)."
    }

    private suspend fun toolTapCoordinates(service: AgentAccessibilityService, input: JsonObject): String {
        val x = input.get("x")?.asInt ?: return "Error: no x coordinate"
        val y = input.get("y")?.asInt ?: return "Error: no y coordinate"

        val done = CompletableDeferred<Unit>()
        service.executeTap(x, y) { done.complete(Unit) }
        withTimeoutOrNull(3000) { done.await() }

        return "Tapped at ($x, $y)."
    }

    private fun toolTypeText(service: AgentAccessibilityService, input: JsonObject): String {
        val text = input.get("text")?.asString ?: return "Error: no text specified"
        service.executeType(text)
        return "Typed '$text'."
    }

    private fun toolSwipe(service: AgentAccessibilityService, input: JsonObject): String {
        val direction = input.get("direction")?.asString ?: "down"
        service.executeSwipe(direction)
        return "Swiped $direction."
    }

    private fun toolLaunchApp(input: JsonObject): String {
        val appName = input.get("app_name")?.asString?.lowercase() ?: return "Error: no app name"
        val packages = mapOf(
            "blinkit" to "com.grofers.customerapp",
            "zomato" to "com.application.zomato",
            "swiggy" to "in.swiggy.android",
            "uber" to "com.ubercab",
            "ola" to "com.olacabs.customer",
            "rapido" to "com.rapido.passenger",
            "zepto" to "com.zeptonow.app",
            "amazon" to "in.amazon.mShop.android.shopping",
            "flipkart" to "com.flipkart.android",
            "whatsapp" to "com.whatsapp",
            "instagram" to "com.instagram.android",
            "youtube" to "com.google.android.youtube",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "phone" to "com.google.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "camera" to "com.android.camera2",
            "photos" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "calculator" to "com.google.android.calculator",
            "clock" to "com.google.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "paytm" to "net.one97.paytm",
            "phonepe" to "com.phonepe.app",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "spotify" to "com.spotify.music",
            "myntra" to "com.myntra.android",
            "bigbasket" to "com.bigbasket.mobileapp",
            "dunzo" to "com.dunzo.user",
        )

        val pkg = packages[appName]

        val service = AgentAccessibilityService.instance
        if (service == null) return "Can't launch app: accessibility service not running."

        if (pkg != null) {
            val success = service.launchApp(pkg)
            return if (success) "Launched $appName. Wait a moment for it to load, then call read_screen()."
                   else "Failed to launch $appName. Is it installed?"
        }

        // Try fuzzy match on known packages
        for ((name, p) in packages) {
            if (name.contains(appName) || appName.contains(name)) {
                val success = service.launchApp(p)
                return if (success) "Launched $name. Wait for it to load, then call read_screen()."
                       else "Failed to launch $name."
            }
        }

        return "Unknown app: '$appName'. Known apps: ${packages.keys.joinToString()}. You can also try the exact package name."
    }
}
