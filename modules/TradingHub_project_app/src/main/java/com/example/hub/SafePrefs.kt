package com.example.hub

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * EncryptedSharedPreferences crashes the whole screen if the saved file and the
 * Android Keystore key no longer match (reinstall, restore, new signing key).
 * In that case wipe the unreadable file + key and start fresh instead of crashing.
 */
fun safeEncryptedPrefs(ctx: Context, name: String): SharedPreferences {
    fun create(): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx, name, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    return try { create() } catch (e: Throwable) {
        try { ctx.deleteSharedPreferences(name) } catch (_: Throwable) {}
        try { File(ctx.applicationInfo.dataDir, "shared_prefs/$name.xml").delete() } catch (_: Throwable) {}
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null)
            ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (_: Throwable) {}
        create()
    }
}
