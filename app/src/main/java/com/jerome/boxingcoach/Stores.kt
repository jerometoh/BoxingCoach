package com.jerome.boxingcoach

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Workout history persisted as JSON in app-private storage. */
class HistoryStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val gson = Gson()

    fun load(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<HistoryEntry>>() {}.type
            gson.fromJson<List<HistoryEntry>>(file.readText(), type) ?: emptyList()
        }.getOrDefault(emptyList())
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
        voiceCommands = prefs.getBoolean("voiceCommands", false),
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("voiceMode", s.voiceMode.name)
            .putString("stance", s.stance.name)
            .putBoolean("restCoaching", s.restCoaching)
            .putBoolean("voiceCommands", s.voiceCommands)
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
