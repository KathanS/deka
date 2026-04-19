# Deka

**Your AI that controls your phone apps.** Just text what you want, and Deka opens the app, taps through it, and gets it done.

![Deka demo](demo.gif)

## What it does

- "Order 6 Diet Coke from Blinkit" → Opens Blinkit, searches, adds to cart
- "Check Uber fare to Mango Restaurant" → Opens Uber, enters destination, shows all fares
- "Send hi to Mom on WhatsApp" → Opens WhatsApp, finds contact, types and sends

**No API integrations. No partnerships.** Deka reads the screen and taps through apps, just like you would.

## How it works

```
User: "Order milk from Blinkit"
  ↓
Deka (on phone) → calls GPT-5.4 / Claude with tools
  ↓
GPT decides: launch_app("blinkit")
  ↓
Deka launches Blinkit → read_screen() → tap_text("Search") → type_text("milk") → ...
  ↓
Reports back: "Added Amul Toned Milk 500ml (₹29) to your Blinkit cart"
```

The AI agent runs entirely on the phone. It uses the Android AccessibilityService to:
- **Read the screen**, extracts all UI elements (text, buttons, inputs) with positions
- **Take screenshots**, sends actual images to the vision model for complex screens
- **Tap, type, swipe**, executes gestures like a human would
- **Navigate apps**, launches any installed app, presses back, scrolls

## Architecture

```
┌─────────────────────────┐
│     Deka Android App     │
│                          │
│  ┌────────────────────┐  │
│  │    DekaAgent        │  │ ← AI agent loop (tool calling)
│  │  ┌──────────────┐  │  │
│  │  │ GPT-5.4/Claude │  │  │ ← LLM decides actions
│  │  └──────────────┘  │  │
│  └────────────────────┘  │
│           │               │
│  ┌────────────────────┐  │
│  │ AccessibilityService│  │ ← Reads screen, taps, types
│  └────────────────────┘  │
│           │               │
│     Any Android App       │ ← Blinkit, Zomato, Uber, etc.
└─────────────────────────┘
```

**No server. No backend. Everything runs on-device.** The only external call is to the LLM API.

## Supported Apps

Works with **any** installed app. Pre-mapped shortcuts for:

Blinkit · Zomato · Swiggy · Uber · Ola · Rapido · Zepto · Amazon · Flipkart · WhatsApp · Instagram · YouTube · Maps · Chrome · Paytm · PhonePe · GPay · Spotify · Myntra · BigBasket

## Setup

### 1. Get an API key

You need an OpenAI or Anthropic API key:
- OpenAI: [platform.openai.com/api-keys](https://platform.openai.com/api-keys)
- Anthropic: [console.anthropic.com](https://console.anthropic.com)

### 2. Build the APK

```bash
cd android
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

### 3. Install & configure

1. Install the APK on your Android phone
2. Open Deka → enter your API key
3. Enable **Settings → Accessibility → Deka**
4. Start texting commands!

## Tech Stack

- **Android**: Kotlin, Jetpack Compose, Material 3
- **AI**: OpenAI GPT-5.4 / Claude (via Chat Completions API)
- **Screen control**: Android AccessibilityService (extracts text, content descriptions, resource IDs, class names, bounds, and interaction state for every UI element)
- **Vision**: Screenshots → JPEG → base64 → sent to vision model
- **No frameworks**: Custom agent loop, ~500 lines of Kotlin

## Configuration

All settings in [`Flags.kt`](android/app/src/main/java/com/cloudagentos/app/config/Flags.kt):

```kotlin
DEFAULT_MODEL = ModelBackend.GPT54  // or ModelBackend.CLAUDE
VISION_ENABLED = true
SCREENSHOT_QUALITY = 60             // JPEG quality
MAX_TURNS = 50                      // Max tool-calling steps
POST_ACTION_DELAY_MS = 800          // Wait after each tap/type
```

## Project Structure

```
android/app/src/main/java/com/cloudagentos/app/
├── agent/
│   └── DekaAgent.kt          # AI agent with tool-calling loop
├── accessibility/
│   └── AgentAccessibilityService.kt  # Screen reader + gesture executor
├── auth/
│   └── ApiKeyAuth.kt         # API key management
├── config/
│   └── Flags.kt              # Feature flags
├── ui/
│   ├── screens/
│   │   └── ChatScreen.kt     # Main chat UI
│   ├── components/
│   │   ├── MessageBubble.kt  # Chat bubbles with markdown
│   │   └── VoiceButton.kt    # Voice input
│   └── theme/                # Premium dark theme
└── viewmodel/
    └── ChatViewModel.kt      # State management
```

## License

[GNU AGPL v3.0](LICENSE)

Deka is free and open source. If you modify it and run it as a service, you must share your changes under the same license. For commercial use without these obligations, contact the author for a separate license.

## Follow

[@dekaaiagent](https://x.com/dekaaiagent) on X
