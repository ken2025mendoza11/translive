package app.translive.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class TargetApp(val pkg: String, val label: String, val enabled: Boolean, val mode: String)

class Prefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("translive", Context.MODE_PRIVATE)

    fun onboarded(): Boolean = p.getBoolean("onboarded", false)
    fun setOnboarded() = p.edit().putBoolean("onboarded", true).apply()

    fun paused(): Boolean = p.getBoolean("paused", false)
    fun setPaused(v: Boolean) = p.edit().putBoolean("paused", v).apply()

    fun showOriginal(): Boolean = p.getBoolean("original", false)
    fun setShowOriginal(v: Boolean) = p.edit().putBoolean("original", v).apply()

    fun ocr(): Boolean = p.getBoolean("ocr", true)
    fun setOcr(v: Boolean) = p.edit().putBoolean("ocr", v).apply()

    fun contribute(): Boolean = p.getBoolean("contribute", true)
    fun currency(): String = p.getString("currency", "PHP") ?: "PHP"
    fun setCurrency(v: String) = p.edit().putString("currency", v).apply()

    fun dataSaver(): Boolean = p.getBoolean("dataSaver", true)
    fun batterySaver(): Boolean = p.getBoolean("batterySaver", true)

    fun apps(): List<TargetApp> {
        val raw = p.getString("apps", null) ?: return defaults()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 4) null
            else TargetApp(parts[0], parts[1], parts[2] == "1", parts[3])
        }.ifEmpty { defaults() }
    }

    fun saveApps(apps: List<TargetApp>) {
        val raw = apps.joinToString("\n") { "${it.pkg}|${it.label}|${if (it.enabled) 1 else 0}|${it.mode}" }
        p.edit().putString("apps", raw).apply()
    }

    fun isEnabled(pkg: String): Boolean = apps().any { it.pkg == pkg && it.enabled }

    fun glossary(): Map<String, String> = decode(p.getString("glossary", "{}") ?: "{}")
    fun saveGloss(zh: String, en: String) {
        val map = glossary().toMutableMap()
        if (en.isBlank()) map.remove(zh) else map[zh] = en.trim()
        val obj = JSONObject()
        map.forEach { (key, value) -> obj.put(key, value) }
        p.edit().putString("glossary", obj.toString()).apply()
    }

    fun reports(): Set<String> = p.getStringSet("reports", emptySet()) ?: emptySet()
    fun report(zh: String) {
        p.edit().putStringSet("reports", reports() + zh).apply()
    }

    private fun decode(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = mutableMapOf<String, String>()
        for (key in obj.keys()) out[key] = obj.getString(key)
        return out
    }

    companion object {
        fun defaults() = listOf(
            TargetApp("com.xunmeng.pinduoduo", "Chinese shopping app", true, "fast"),
            TargetApp("com.taobao.taobao", "Marketplace app", false, "fast"),
            TargetApp("com.jingdong.app.mall", "Department store", false, "fast"),
            TargetApp("com.ss.android.ugc.aweme", "Short-video shop", false, "best"),
        )
    }
}

class PhoneCache(context: Context) : SQLiteOpenHelper(context.applicationContext, "translive.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cache (zh TEXT PRIMARY KEY, en TEXT NOT NULL, used INTEGER NOT NULL, pinned INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun get(zh: String): String? {
        val db = readableDatabase
        db.rawQuery("SELECT en FROM cache WHERE zh=?", arrayOf(zh)).use { c ->
            if (!c.moveToFirst()) return null
            return c.getString(0)
        }
    }

    fun put(zh: String, en: String, pinned: Boolean = false) {
        val values = android.content.ContentValues().apply {
            put("zh", zh)
            put("en", en)
            put("used", System.currentTimeMillis())
            put("pinned", if (pinned) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("cache", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun bytes(): Long {
        val file = readableDatabase.path
        return java.io.File(file).length()
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM cache WHERE pinned=0")
    }
}

object Dictionary {
    fun load(context: Context): Map<String, String> {
        val text = context.assets.open("dictionary/phrases.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(text).getJSONArray("phrases")
        val map = HashMap<String, String>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.getJSONObject(i)
            map[row.getString("zh")] = row.getString("en")
        }
        return map
    }
}
