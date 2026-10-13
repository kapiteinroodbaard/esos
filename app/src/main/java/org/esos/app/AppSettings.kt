package org.esos.app

import android.content.Context
import android.telephony.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject

data class FamilyContact(val name: String, val number: String)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        if (prefs.contains("address") || prefs.contains("smsStatus")) {
            prefs.edit().remove("address").remove("smsStatus").apply()
        }
    }

    var contacts: List<FamilyContact>
        get() = runCatching {
            val entries = JSONArray(prefs.getString("contacts", "[]"))
            (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                FamilyContact(entry.getString("name"), entry.getString("number"))
            }
        }.getOrDefault(emptyList())
        set(value) {
            val entries = JSONArray()
            value.forEach { contact ->
                entries.put(JSONObject().put("name", contact.name.trim())
                    .put("number", PhoneNumberUtils.normalizeNumber(contact.number)))
            }
            prefs.edit().putString("contacts", entries.toString()).apply()
        }
    var setupComplete: Boolean
        get() = prefs.getBoolean("setupComplete", false)
        set(value) { prefs.edit().putBoolean("setupComplete", value).apply() }
    var simId: Int
        get() = prefs.getInt("sim", -1)
        set(value) { prefs.edit().putInt("sim", value).apply() }
    var shareLocation: Boolean
        get() = prefs.getBoolean("location", true)
        set(value) { prefs.edit().putBoolean("location", value).apply() }
    var smsEnabled: Boolean
        get() = prefs.getBoolean("smsEnabled", true)
        set(value) { prefs.edit().putBoolean("smsEnabled", value).apply() }
    var callEnabled: Boolean
        get() = prefs.getBoolean("callEnabled", false)
        set(value) { prefs.edit().putBoolean("callEnabled", value).apply() }
    var speechDetectionEnabled: Boolean
        get() = prefs.getBoolean("speechDetectionEnabled", false)
        set(value) { prefs.edit().putBoolean("speechDetectionEnabled", value).apply() }
    var wakePhrases: List<String>
        get() {
            val stored = prefs.getString("wakePhrases", null) ?: return WakePhrases.DEFAULTS
            return runCatching {
                val entries = JSONArray(stored)
                WakePhrases.unique((0 until entries.length()).map { entries.getString(it) })
            }.getOrDefault(WakePhrases.DEFAULTS)
        }
        set(value) {
            val entries = JSONArray()
            WakePhrases.unique(value).forEach(entries::put)
            prefs.edit().putString("wakePhrases", entries.toString()).apply()
        }
    var sosModeActive: Boolean
        get() = prefs.getBoolean("sosModeActive", false)
        set(value) { prefs.edit().putBoolean("sosModeActive", value).apply() }
    var fallDetectionEnabled: Boolean
        get() = prefs.getBoolean("fallDetection", false)
        set(value) { prefs.edit().putBoolean("fallDetection", value).apply() }
    var fallSensitivity: Int
        get() = prefs.getInt("fallSensitivity", 3).coerceIn(1, 5)
        set(value) { prefs.edit().putInt("fallSensitivity", value.coerceIn(1, 5)).apply() }
    var inactivityEnabled: Boolean
        get() = prefs.getBoolean("inactivityEnabled", prefs.getBoolean("checkIn", false))
        set(value) { prefs.edit().putBoolean("inactivityEnabled", value).putBoolean("checkIn", value).apply() }
    var inactivityHours: Int
        get() = prefs.getInt("inactivityHours", 24).coerceIn(1, 72)
        set(value) { prefs.edit().putInt("inactivityHours", value.coerceIn(1, 72)).apply() }
    var inactivitySleepStartMinute: Int
        get() = prefs.getInt("inactivitySleepStartMinute", InactivityTimes.DEFAULT_SLEEP_START_MINUTE).coerceIn(0, 1439)
        set(value) { prefs.edit().putInt("inactivitySleepStartMinute", value.coerceIn(0, 1439)).apply() }
    var inactivitySleepEndMinute: Int
        get() = prefs.getInt("inactivitySleepEndMinute", InactivityTimes.DEFAULT_SLEEP_END_MINUTE).coerceIn(0, 1439)
        set(value) { prefs.edit().putInt("inactivitySleepEndMinute", value.coerceIn(0, 1439)).apply() }
    var inactivityLockedSince: Long
        get() = prefs.getLong("inactivityLockedSince", 0L)
        set(value) { prefs.edit().putLong("inactivityLockedSince", value).apply() }
    var inactivityTriggered: Boolean
        get() = prefs.getBoolean("inactivityTriggered", false)
        set(value) { prefs.edit().putBoolean("inactivityTriggered", value).apply() }
}