package ai.patnub.identity

import ai.patnub.data.local.PatnubDatabase
import ai.patnub.data.local.entity.UserEntity
import ai.patnub.data.local.seedDemoData
import ai.patnub.data.local.entity.UserRole
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed interface SelfProfile {
    data object Loading : SelfProfile
    data object Missing : SelfProfile
    data class Ready(val user: UserEntity) : SelfProfile
}

/**
 * The one patient who owns this phone (spec v1.2): their profile row in `users` and an
 * X25519 key pair. Clinics encrypt records to the public key, shown in the check-in code.
 * The private key is stored encrypted with an AES key that never leaves Android Keystore.
 */
class PatientIdentity(context: Context, private val db: PatnubDatabase) {
    private val prefs = context.getSharedPreferences("identity", Context.MODE_PRIVATE)

    private val _profile = MutableStateFlow<SelfProfile>(SelfProfile.Loading)
    val profile: StateFlow<SelfProfile> = _profile.asStateFlow()

    suspend fun load() {
        val user = prefs.getString(KEY_USER_ID, null)?.let { db.userDao().getById(it) }
        _profile.value = if (user != null && prefs.contains(KEY_PUBLIC)) SelfProfile.Ready(user) else SelfProfile.Missing
    }

    suspend fun create(name: String, age: Int, barangay: String) {
        val user = UserEntity(
            userId = UUID.randomUUID().toString(),
            role = UserRole.PATIENT,
            displayName = name.trim(),
            age = age,
            barangay = barangay.trim().ifEmpty { null },
        )
        val private = X25519.generatePrivateKey()
        val public = X25519.publicFromPrivate(private)
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, keystoreKey()) }
        val sealed = cipher.doFinal(private)
        private.fill(0)

        db.userDao().insertOrIgnore(listOf(user))
        // commit, not apply: the profile must not appear without its key after a crash.
        prefs.edit(commit = true) {
            putString(KEY_USER_ID, user.userId)
            putString(KEY_PUBLIC, b64(public))
            putString(KEY_IV, b64(cipher.iv))
            putString(KEY_SEALED, b64(sealed))
        }
        _profile.value = SelfProfile.Ready(user)
    }

    /** 32-byte X25519 public key, or null before onboarding. */
    fun publicKey(): ByteArray? = prefs.getString(KEY_PUBLIC, null)?.let(::unb64)

    /** 32-byte X25519 private key. Callers should zero it after use. */
    fun privateKey(): ByteArray {
        val iv = unb64(checkNotNull(prefs.getString(KEY_IV, null)) { "no identity" })
        return privateFromSealed(iv, unb64(prefs.getString(KEY_SEALED, null)!!))
    }

    /** Debug-only demo reset: wipes the profile, keys and every local record. */
    suspend fun resetForDemo() {
        db.clearAllTables()
        db.seedDemoData()
        prefs.edit(commit = true) { clear() }
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        _profile.value = SelfProfile.Missing
    }

    private fun privateFromSealed(iv: ByteArray, sealed: ByteArray): ByteArray =
        Cipher.getInstance(AES_GCM)
            .apply { init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, iv)) }
            .doFinal(sealed)

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "patnub_identity_wrap"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val KEY_USER_ID = "user_id"
        const val KEY_PUBLIC = "x25519_public"
        const val KEY_IV = "x25519_private_iv"
        const val KEY_SEALED = "x25519_private_sealed"

        fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
        fun unb64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)
    }
}

/** Onboarding form check. Returns an error message, or null when the input is valid. */
fun validateProfile(name: String, ageText: String): String? {
    if (name.isBlank()) return "Enter your name."
    val age = ageText.trim().toIntOrNull() ?: return "Enter your age as a number."
    if (age !in 0..120) return "Enter an age between 0 and 120."
    return null
}
