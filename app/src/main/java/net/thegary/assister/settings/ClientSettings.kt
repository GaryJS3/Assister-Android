package net.thegary.assister.settings

import android.content.Context
import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in encrypted private preferences; the AES key never leaves Android Keystore. */
@SuppressLint("ApplySharedPref", "UseKtx") // Check durable commits on Dispatchers.IO before HTTP; KTX edit discards the result.
class ClientSettings(context: Context) {
    private val prefs = context.getSharedPreferences("assister", Context.MODE_PRIVATE)
    var endpoint: String
        get() = prefs.getString("endpoint", DEFAULT_ENDPOINT)!!
        set(value) { persist("endpoint", value) }
    var conversationId: String
        get() = prefs.getString("conversation", "")!!
        set(value) { persist("conversation", value) }
    var pending: String
        get() = prefs.getString("pending", "")!!
        set(value) { persist("pending", value) }
    var draft: String
        get() = prefs.getString("draft", "")!!
        set(value) { prefs.edit().putString("draft", value).apply() }

    fun token(): String {
        val encoded = prefs.getString("credential", null) ?: return ""
        val parts = encoded.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }

    fun saveToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        persist("credential", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP))
    }

    private fun persist(name: String, value: String) {
        check(prefs.edit().putString(name, value).commit()) { "Could not persist client settings; request was not sent" }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "http://10.44.0.33:8081"
        private const val KEY_ALIAS = "assister-client-credential"
    }
}
