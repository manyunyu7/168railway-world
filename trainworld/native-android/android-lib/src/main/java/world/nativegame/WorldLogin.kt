// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class WorldLogin(context: Context) {
    private val prefs = context.getSharedPreferences("world-archive-session",0)
    private val alias = "world-archive-session-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun decode(text: String) = Base64.decode(text, Base64.URL_SAFE)
    private fun save(name: String, value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val text = b64(cipher.iv) + "." + b64(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
        check(prefs.edit().putString(name,text).commit())
    }
    fun get(name: String): JSONObject? {
        val record = prefs.getString(name,null) ?: return null
        return runCatching {
            val (iv, bytes) = record.split('.',limit=2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,decode(iv)))
            JSONObject(String(cipher.doFinal(decode(bytes)),Charsets.UTF_8)).takeIf { it.getLong("until") > System.currentTimeMillis() }
        }.getOrNull()
    }
    fun start(): Uri {
        fun random() = b64(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val state=random()
        val verifier=random()
        save("pending", JSONObject().put("state",state).put("verifier",verifier).put("until",System.currentTimeMillis()+600000))
        val challenge=b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        return Uri.parse("${WorldCloud.ORIGIN}/studio-native/authorize").buildUpon()
            .appendQueryParameter("state",state).appendQueryParameter("challenge",challenge).build()
    }
    fun complete(uri: Uri) {
        val p = checkNotNull(get("pending")) { "Permintaan login kedaluwarsa." }
        require(uri.scheme=="com.railway168.studio" && uri.host=="login" && uri.path=="/complete"
            && uri.getQueryParameter("state")==p.getString("state")) { "Callback login tidak cocok." }
        val code=uri.getQueryParameter("code") ?: error("Kode login tidak ditemukan.")
        require(Regex("[A-Za-z0-9_-]{43}").matches(code))
        val session=WorldCloud.exchange(code,p.getString("verifier"))
        save("session",session.put("until",System.currentTimeMillis()+session.getInt("expires_in")*1000L))
        prefs.edit().remove("pending").apply()
    }
    fun forget() { prefs.edit().remove("session").remove("pending").apply() }
}
