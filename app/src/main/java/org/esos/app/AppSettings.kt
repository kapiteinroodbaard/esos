package org.esos.app

import android.content.Context
import android.telephony.PhoneNumberUtils
import org.json.JSONArray
import org.json.JSONObject

data class FamilyContact(val name: String, val number: String)

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

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
    var homeAddress: String
        get() = prefs.getString("address", "") ?: ""
        set(value) { prefs.edit().putString("address", value.trim()).apply() }
    var simId: Int
        get() = prefs.getInt("sim", -1)
        set(value) { prefs.edit().putInt("sim", value).apply() }
    var shareLocation: Boolean
        get() = prefs.getBoolean("location", true)
        set(value) { prefs.edit().putBoolean("location", value).apply() }
    var smsEnabled: Boolean
        get() = prefs.getBoolean("smsEnabled", true)
        set(value) { prefs.edit().putBoolean("smsEnabled", value).apply() }
    var checkInEnabled: Boolean
        get() = prefs.getBoolean("checkIn", false)
        set(value) { prefs.edit().putBoolean("checkIn", value).apply() }
    var checkInHour: Int
        get() = prefs.getInt("hour", 10)
        set(value) { prefs.edit().putInt("hour", value).apply() }
    var checkInMinute: Int
        get() = prefs.getInt("minute", 0)
        set(value) { prefs.edit().putInt("minute", value).apply() }
    var checkInDeadline: Long
        get() = prefs.getLong("deadline", 0L)
        set(value) { prefs.edit().putLong("deadline", value).apply() }
    var smsStatus: String
        get() = prefs.getString("smsStatus", "Nog geen SMS-resultaat") ?: ""
        set(value) { prefs.edit().putString("smsStatus", value).apply() }
}