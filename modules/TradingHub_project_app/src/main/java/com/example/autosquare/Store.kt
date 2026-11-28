package com.example.autosquare
import android.content.Context
import android.content.SharedPreferences

class Store(ctx: Context) {
    private val sp: SharedPreferences = com.example.hub.safeEncryptedPrefs(ctx, "cfg")

    init { com.example.angelwatch.Store.init(ctx) }

    /** Angel One login (key, client, PIN, TOTP) falls back to what was entered in AngelWatch, so it is typed only once. */
    fun get(k: String, d: String = ""): String {
        val v = sp.getString(k, null)
        if (!v.isNullOrEmpty()) return v
        val c = com.example.angelwatch.Store.creds()
        val shared = when (k) {
            "apiKey" -> c.apiKey
            "client" -> c.client
            "pin" -> c.pin
            "totp" -> c.totp
            else -> ""
        }
        return shared.ifEmpty { d }
    }
    fun put(vararg kv: Pair<String, String>) { val e = sp.edit(); kv.forEach { e.putString(it.first, it.second) }; e.apply() }
}
