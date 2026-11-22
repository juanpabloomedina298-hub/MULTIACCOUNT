package com.example.multichatmanager

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-slot encrypted token storage. AES-256 key is generated and retained by Android Keystore. */
class SecureAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("account_auth_v1", Context.MODE_PRIVATE)
    private val alias = "multiaccount_auth_aes_v1"

    data class Session(
        val provider: String,
        val displayName: String,
        val accessToken: String,
        val expiresAt: Long = 0L,
        val refreshToken: String? = null,
        /** true si el token fue pegado a mano (no se revoca en el proveedor al desconectar). */
        val manual: Boolean = false
    )

    @Synchronized fun save(accountId: Int, session: Session) {
        val json = JSONObject()
            .put("provider", session.provider)
            .put("displayName", session.displayName)
            .put("accessToken", session.accessToken)
            .put("expiresAt", session.expiresAt)
            .put("manual", session.manual)
        session.refreshToken?.let { json.put("refreshToken", it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString("slot_$accountId", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    @Synchronized fun get(accountId: Int): Session? {
        return try {
            val encoded = prefs.getString("slot_$accountId", null) ?: return null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 12)
            val iv = packed.copyOfRange(0, 12)
            val encrypted = packed.copyOfRange(12, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            Session(
                provider = json.getString("provider"),
                displayName = json.getString("displayName"),
                accessToken = json.getString("accessToken"),
                expiresAt = json.optLong("expiresAt", 0L),
                refreshToken = json.optString("refreshToken").takeIf { it.isNotBlank() },
                manual = json.optBoolean("manual", false)
            )
        } catch (_: Exception) { null }
    }

    @Synchronized fun remove(accountId: Int) { prefs.edit().remove("slot_$accountId").apply() }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
