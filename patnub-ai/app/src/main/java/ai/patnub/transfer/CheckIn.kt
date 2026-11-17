package ai.patnub.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** What the phone shows the clinic (`contract/README.md`, check-in code). Field order is part of the format. */
@Serializable
data class CheckIn(
    val name: String,
    val age: Int,
    val brgy: String? = null,
    /** Phone's X25519 public key, unpadded base64url. */
    val pub: String,
) {
    fun encode(): String = PREFIX + Base45.encode(json.encodeToString(serializer(), this).encodeToByteArray())

    companion object {
        const val PREFIX = "PNBC1:"
        private val json = Json { explicitNulls = false }

        fun of(name: String, age: Int, barangay: String?, publicKey: ByteArray) =
            CheckIn(name, age, barangay, Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey))
    }
}
