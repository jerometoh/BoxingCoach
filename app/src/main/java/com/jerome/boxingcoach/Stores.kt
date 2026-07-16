package com.jerome.boxingcoach

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.io.File

/** Workout history persisted as JSON in app-private storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val gson = Gson()

    fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        // Parsed field-by-field so pre-slider entries (which stored difficulty/intensity
        // as enum strings) migrate to the 1–10 scale instead of failing the whole load.
        return runCatching {
            JsonParser.parseString(file.readText()).asJsonArray.map { el ->
                val o = el.asJsonObject
                HistoryEntry(
                    completedAt = o.get("completedAt").asLong,
                    durationSec = o.get("durationSec").asInt,
                    complexity = scaleField(o, "complexity", "difficulty"),
                    intensity = scaleField(o, "intensity", "intensity"),
                    summary = o.get("summary")?.asString ?: "",
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Read a 1–10 scale value: use the numeric field if present, else map a legacy
     *  enum string (BEGINNER/LOW→2, INTERMEDIATE/MEDIUM→5, ADVANCED/HIGH→8). */
    private fun scaleField(o: JsonObject, newKey: String, oldKey: String): Int {
        o.get(newKey)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { return it.asInt }
        val s = o.get(oldKey)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return when (s?.uppercase()) {
            "BEGINNER", "LOW" -> 2
            "ADVANCED", "HIGH" -> 8
            else -> 5
        }
    }

    fun add(entry: HistoryEntry) {
        val all = load() + entry
        file.writeText(gson.toJson(all))
    }
}

/** App settings persisted in SharedPreferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        voiceMode = VoiceMode.valueOf(prefs.getString("voiceMode", VoiceMode.DUCK_MUSIC.name)!!),
        stance = Stance.valueOf(prefs.getString("stance", Stance.ORTHODOX.name)!!),
        restCoaching = prefs.getBoolean("restCoaching", true),
        countReps = prefs.getBoolean("countReps", true),
        voiceCommands = prefs.getBoolean("voiceCommands", false),
        keepScreenOn = prefs.getBoolean("keepScreenOn", true),
        warnSound = prefs.getBoolean("warnSound", true),
        endBell = prefs.getBoolean("endBell", true),
        voiceName = prefs.getString("voiceName", "")!!,
        tryEmbeddedVoice = prefs.getBoolean("tryEmbeddedVoice", false),
        elevenApiKey = prefs.getString("elevenApiKey", "")!!,
        elevenVoiceId = prefs.getString("elevenVoiceId", "")!!,
        elevenEnabled = prefs.getBoolean("elevenEnabled", true),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("voiceMode", s.voiceMode.name)
            .putString("stance", s.stance.name)
            .putBoolean("restCoaching", s.restCoaching)
            .putBoolean("countReps", s.countReps)
            .putBoolean("voiceCommands", s.voiceCommands)
            .putBoolean("keepScreenOn", s.keepScreenOn)
            .putBoolean("warnSound", s.warnSound)
            .putBoolean("endBell", s.endBell)
            .putString("voiceName", s.voiceName)
            .putBoolean("tryEmbeddedVoice", s.tryEmbeddedVoice)
            .putString("elevenApiKey", s.elevenApiKey)
            .putString("elevenVoiceId", s.elevenVoiceId)
            .putBoolean("elevenEnabled", s.elevenEnabled)
            .apply()
    }

    fun loadParams(): RoutineParams {
        val json = prefs.getString("params", null) ?: return RoutineParams()
        return runCatching { Gson().fromJson(json, RoutineParams::class.java) }
            .getOrDefault(RoutineParams())
    }

    fun saveParams(p: RoutineParams) {
        prefs.edit().putString("params", Gson().toJson(p)).apply()
    }
}
