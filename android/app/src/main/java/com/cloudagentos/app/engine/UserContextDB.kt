package com.cloudagentos.app.engine

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

private const val TAG = "UserContext"
private const val DB_NAME = "deka_user.db"
private const val DB_VERSION = 1

data class OrderRecord(
    val id: Long = 0,
    val timestamp: String,
    val app: String,
    val action: String,
    val query: String,
    val items: List<OrderItem>,
    val total: Double = 0.0,
    val couponUsed: String? = null,
    val status: String = "completed"
)

data class OrderItem(
    val name: String,
    val qty: Int = 1,
    val price: Double = 0.0,
    val variant: String? = null
)

data class Preference(
    val category: String,
    val preferredItem: String,
    val preferredVariant: String? = null,
    val preferredApp: String? = null,
    val confidence: Float = 0.5f,
    val timesChosen: Int = 1,
    val alternatives: List<String> = emptyList()
)

data class Routine(
    val id: Long = 0,
    val pattern: String,
    val frequency: String,
    val typicalDay: String? = null,
    val typicalTime: String? = null,
    val app: String,
    val items: List<String>,
    val autoSuggest: Boolean = true
)

/**
 * On-device user context database.
 * Stores order history, learned preferences, routines.
 * All data stays on the phone — never sent to server unencrypted.
 */
class UserContextDB(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE orders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp TEXT NOT NULL,
                app TEXT NOT NULL,
                action TEXT NOT NULL,
                query TEXT,
                items TEXT NOT NULL,
                total REAL DEFAULT 0,
                coupon_used TEXT,
                status TEXT DEFAULT 'completed'
            )
        """)

        db.execSQL("""
            CREATE TABLE preferences (
                category TEXT PRIMARY KEY,
                preferred_item TEXT NOT NULL,
                preferred_variant TEXT,
                preferred_app TEXT,
                confidence REAL DEFAULT 0.5,
                times_chosen INTEGER DEFAULT 1,
                last_used TEXT,
                alternatives TEXT
            )
        """)

        db.execSQL("""
            CREATE TABLE routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pattern TEXT NOT NULL,
                frequency TEXT,
                typical_day TEXT,
                typical_time TEXT,
                app TEXT,
                items TEXT,
                auto_suggest INTEGER DEFAULT 1
            )
        """)

        db.execSQL("CREATE INDEX idx_orders_app ON orders(app)")
        db.execSQL("CREATE INDEX idx_orders_timestamp ON orders(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations go here
    }

    // ── Orders ──────────────────────────────────────────────────

    fun saveOrder(order: OrderRecord): Long {
        val values = ContentValues().apply {
            put("timestamp", order.timestamp)
            put("app", order.app)
            put("action", order.action)
            put("query", order.query)
            put("items", gson.toJson(order.items))
            put("total", order.total)
            put("coupon_used", order.couponUsed)
            put("status", order.status)
        }
        val id = writableDatabase.insert("orders", null, values)
        Log.i(TAG, "Saved order #$id: ${order.app} ${order.items.map { it.name }}")

        // Auto-learn preferences from this order
        learnFromOrder(order)
        return id
    }

    fun getRecentOrders(limit: Int = 10): List<OrderRecord> {
        val cursor = readableDatabase.query(
            "orders", null, null, null, null, null, "timestamp DESC", "$limit"
        )
        val orders = mutableListOf<OrderRecord>()
        while (cursor.moveToNext()) {
            orders.add(OrderRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
                app = cursor.getString(cursor.getColumnIndexOrThrow("app")),
                action = cursor.getString(cursor.getColumnIndexOrThrow("action")),
                query = cursor.getString(cursor.getColumnIndexOrThrow("query")),
                items = gson.fromJson(cursor.getString(cursor.getColumnIndexOrThrow("items")),
                    object : TypeToken<List<OrderItem>>() {}.type),
                total = cursor.getDouble(cursor.getColumnIndexOrThrow("total")),
                couponUsed = cursor.getString(cursor.getColumnIndexOrThrow("coupon_used")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
            ))
        }
        cursor.close()
        return orders
    }

    fun getOrdersByApp(app: String, limit: Int = 5): List<OrderRecord> {
        val cursor = readableDatabase.query(
            "orders", null, "app = ?", arrayOf(app), null, null, "timestamp DESC", "$limit"
        )
        val orders = mutableListOf<OrderRecord>()
        while (cursor.moveToNext()) {
            orders.add(OrderRecord(
                id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                timestamp = cursor.getString(cursor.getColumnIndexOrThrow("timestamp")),
                app = cursor.getString(cursor.getColumnIndexOrThrow("app")),
                action = cursor.getString(cursor.getColumnIndexOrThrow("action")),
                query = cursor.getString(cursor.getColumnIndexOrThrow("query")),
                items = gson.fromJson(cursor.getString(cursor.getColumnIndexOrThrow("items")),
                    object : TypeToken<List<OrderItem>>() {}.type),
                total = cursor.getDouble(cursor.getColumnIndexOrThrow("total")),
                couponUsed = cursor.getString(cursor.getColumnIndexOrThrow("coupon_used")),
                status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
            ))
        }
        cursor.close()
        return orders
    }

    // ── Preferences ─────────────────────────────────────────────

    fun getPreference(category: String): Preference? {
        val cursor = readableDatabase.query(
            "preferences", null, "category = ?", arrayOf(category.lowercase()), null, null, null
        )
        val pref = if (cursor.moveToFirst()) {
            Preference(
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                preferredItem = cursor.getString(cursor.getColumnIndexOrThrow("preferred_item")),
                preferredVariant = cursor.getString(cursor.getColumnIndexOrThrow("preferred_variant")),
                preferredApp = cursor.getString(cursor.getColumnIndexOrThrow("preferred_app")),
                confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                timesChosen = cursor.getInt(cursor.getColumnIndexOrThrow("times_chosen")),
                alternatives = cursor.getString(cursor.getColumnIndexOrThrow("alternatives"))
                    ?.let { gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type) }
                    ?: emptyList()
            )
        } else null
        cursor.close()
        return pref
    }

    fun getAllPreferences(): List<Preference> {
        val cursor = readableDatabase.query("preferences", null, null, null, null, null, "confidence DESC")
        val prefs = mutableListOf<Preference>()
        while (cursor.moveToNext()) {
            prefs.add(Preference(
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                preferredItem = cursor.getString(cursor.getColumnIndexOrThrow("preferred_item")),
                preferredVariant = cursor.getString(cursor.getColumnIndexOrThrow("preferred_variant")),
                preferredApp = cursor.getString(cursor.getColumnIndexOrThrow("preferred_app")),
                confidence = cursor.getFloat(cursor.getColumnIndexOrThrow("confidence")),
                timesChosen = cursor.getInt(cursor.getColumnIndexOrThrow("times_chosen")),
                alternatives = cursor.getString(cursor.getColumnIndexOrThrow("alternatives"))
                    ?.let { gson.fromJson<List<String>>(it, object : TypeToken<List<String>>() {}.type) }
                    ?: emptyList()
            ))
        }
        cursor.close()
        return prefs
    }

    /**
     * Auto-learn preferences from a completed order.
     * Called automatically after every order is saved.
     */
    private fun learnFromOrder(order: OrderRecord) {
        for (item in order.items) {
            val category = inferCategory(item.name)
            val existing = getPreference(category)

            if (existing == null) {
                // New category — save with initial confidence
                val values = ContentValues().apply {
                    put("category", category)
                    put("preferred_item", item.name)
                    put("preferred_variant", item.variant)
                    put("preferred_app", order.app)
                    put("confidence", 0.5f)
                    put("times_chosen", 1)
                    put("last_used", order.timestamp)
                    put("alternatives", "[]")
                }
                writableDatabase.insertWithOnConflict("preferences", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                Log.i(TAG, "New pref: $category → ${item.name}")
            } else if (existing.preferredItem.equals(item.name, ignoreCase = true)) {
                // Same item chosen again — increase confidence
                val newConf = minOf(0.99f, existing.confidence + 0.1f)
                val values = ContentValues().apply {
                    put("confidence", newConf)
                    put("times_chosen", existing.timesChosen + 1)
                    put("last_used", order.timestamp)
                }
                writableDatabase.update("preferences", values, "category = ?", arrayOf(category))
                Log.i(TAG, "Pref reinforced: $category → ${item.name} (conf=$newConf)")
            } else {
                // Different item — decrease confidence, add to alternatives
                val newConf = maxOf(0.1f, existing.confidence - 0.1f)
                val alts = existing.alternatives.toMutableList()
                if (!alts.contains(item.name)) alts.add(item.name)
                val values = ContentValues().apply {
                    put("confidence", newConf)
                    put("alternatives", gson.toJson(alts))
                    put("last_used", order.timestamp)
                }
                writableDatabase.update("preferences", values, "category = ?", arrayOf(category))
                Log.i(TAG, "Pref weakened: $category (conf=$newConf), alt: ${item.name}")
            }
        }
    }

    /**
     * Infer a general category from item name.
     * "Amul Taaza Toned Fresh Milk 500ml" → "milk"
     */
    private fun inferCategory(itemName: String): String {
        val lower = itemName.lowercase()
        val categories = mapOf(
            "milk" to listOf("milk", "dudh", "doodh"),
            "bread" to listOf("bread", "pav", "bun"),
            "eggs" to listOf("egg", "anda"),
            "rice" to listOf("rice", "chawal", "basmati"),
            "oil" to listOf("oil", "tel"),
            "sugar" to listOf("sugar", "cheeni", "shakkar"),
            "salt" to listOf("salt", "namak"),
            "butter" to listOf("butter", "makhan"),
            "curd" to listOf("curd", "dahi", "yogurt", "yoghurt"),
            "paneer" to listOf("paneer", "cottage cheese"),
            "chicken" to listOf("chicken", "murga", "murgi"),
            "onion" to listOf("onion", "pyaaz", "pyaj"),
            "tomato" to listOf("tomato", "tamatar"),
            "potato" to listOf("potato", "aloo", "alu"),
            "chips" to listOf("chips", "crisps", "lays", "kurkure"),
            "water" to listOf("water", "pani"),
            "juice" to listOf("juice", "ras"),
            "coffee" to listOf("coffee", "nescafe"),
            "tea" to listOf("tea", "chai"),
            "biscuit" to listOf("biscuit", "cookie", "oreo", "parle"),
            "soap" to listOf("soap", "sabun"),
            "shampoo" to listOf("shampoo"),
            "detergent" to listOf("detergent", "surf", "tide", "rin"),
            "biryani" to listOf("biryani", "biriyani"),
            "pizza" to listOf("pizza"),
            "burger" to listOf("burger"),
            "noodles" to listOf("noodles", "maggi", "ramen"),
            "dal" to listOf("dal", "daal", "lentil"),
            "atta" to listOf("atta", "flour", "wheat"),
        )

        for ((category, keywords) in categories) {
            if (keywords.any { lower.contains(it) }) return category
        }
        // Fallback: use first meaningful word
        return lower.split(" ").firstOrNull { it.length > 3 } ?: lower.take(20)
    }

    // ── Context for LLM ─────────────────────────────────────────

    /**
     * Generate a context summary for the LLM intent parser.
     * Includes recent orders and preferences.
     */
    fun getContextForLLM(): String {
        val recentOrders = getRecentOrders(5)
        val prefs = getAllPreferences().take(10)

        val sb = StringBuilder()
        if (recentOrders.isNotEmpty()) {
            sb.appendLine("Recent orders:")
            for (order in recentOrders) {
                sb.appendLine("  ${order.timestamp}: ${order.app} - ${order.items.joinToString { it.name }} (₹${order.total})")
            }
        }
        if (prefs.isNotEmpty()) {
            sb.appendLine("Preferences:")
            for (pref in prefs) {
                sb.appendLine("  ${pref.category}: ${pref.preferredItem}${pref.preferredVariant?.let { " ($it)" } ?: ""} [${(pref.confidence * 100).toInt()}%]")
            }
        }
        return sb.toString()
    }
}
