package ai.patnub.transfer

import ai.patnub.data.local.entity.PrescribedMedication
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.util.Base64
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Error codes match `contract/contract.ts`. */
enum class OpenErrorCode { FORMAT, SIGNATURE, CHAIN, EXPIRED, NOT_FOR_THIS_PHONE }

class OpenRecordException(val code: OpenErrorCode, message: String = code.name) : Exception(message)

@Serializable
data class LicenseBody(
    val t: String, val clinic_id: String, val clinic_name: String, val admin_pub: String, val nbf: Long, val exp: Long,
)

@Serializable
data class StaffBody(
    val t: String, val clinic_id: String, val staff_id: String, val name: String, val profession: String,
    val license_no: String, val pub: String, val nbf: Long, val exp: Long,
)

/** The patient copy: clinician-approved structured fields only. */
@Serializable
data class PatientCopy(
    val rid: String,
    val rv: Int,
    val iat: Long,
    val exp: Long,
    val encounter_date: String,
    val assessment: String,
    val meds: List<PrescribedMedication>,
    val instructions: List<String>,
    val warnings: List<String>,
    val follow_up: String,
)

data class OpenedRecord(val record: PatientCopy, val license: LicenseBody, val staff: StaffBody)

/**
 * Patient half of the record QR (`contract/README.md`, "Opening a record"). Verifies the
 * whole chain before decrypting. Checked against `contract/vectors.json`.
 */
object RecordCodec {
    const val PREFIX = "PNB1:"
    private const val FORMAT_VERSION = 1
    private val HKDF_INFO = "patnub-record-v1".encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = true }
    private fun unb64u(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    private class Cert(val bodyBytes: ByteArray, val sig: ByteArray, val end: Int)

    private fun cert(buf: ByteArray, at: Int): Cert {
        if (at + 2 > buf.size) throw OpenRecordException(OpenErrorCode.FORMAT, "truncated certificate")
        val len = ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)
        val end = at + 2 + len + 64
        if (end > buf.size) throw OpenRecordException(OpenErrorCode.FORMAT, "truncated certificate")
        return Cert(buf.copyOfRange(at + 2, at + 2 + len), buf.copyOfRange(at + 2 + len, end), end)
    }

    private fun verified(pub: ByteArray, data: ByteArray, sig: ByteArray, what: String) {
        try {
            Ed25519Verify(pub).verify(sig, data)
        } catch (e: GeneralSecurityException) {
            throw OpenRecordException(OpenErrorCode.SIGNATURE, what)
        }
    }

    private inline fun <reified T> parse(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.decodeToString())
    } catch (e: Exception) {
        throw OpenRecordException(OpenErrorCode.FORMAT, "malformed ${T::class.simpleName}")
    }

    /**
     * @param patientPrivate this phone's X25519 private key (zero it after the call)
     * @param now Unix seconds
     */
    fun open(text: String, rootPub: ByteArray, patientPrivate: ByteArray, patientPublic: ByteArray, now: Long): OpenedRecord {
        if (!text.startsWith(PREFIX)) throw OpenRecordException(OpenErrorCode.FORMAT, "not a patnub record code")
        val buf = try {
            Base45.decode(text.substring(PREFIX.length))
        } catch (e: IllegalArgumentException) {
            throw OpenRecordException(OpenErrorCode.FORMAT, e.message ?: "bad base45")
        }
        if (buf.isEmpty() || buf[0].toInt() != FORMAT_VERSION) throw OpenRecordException(OpenErrorCode.FORMAT, "unsupported format version")

        val lic = cert(buf, 1)
        val staff = cert(buf, lic.end)
        val headerEnd = staff.end + 32 + 12
        if (buf.size < headerEnd + 16 + 64) throw OpenRecordException(OpenErrorCode.FORMAT, "truncated record")
        val sigAt = buf.size - 64

        val license = parse<LicenseBody>(lic.bodyBytes)
        val staffBody = parse<StaffBody>(staff.bodyBytes)
        if (license.t != "license" || staffBody.t != "staff") throw OpenRecordException(OpenErrorCode.FORMAT, "wrong certificate type")
        verified(rootPub, lic.bodyBytes, lic.sig, "license not signed by root")
        verified(unb64u(license.admin_pub), staff.bodyBytes, staff.sig, "staff not signed by clinic admin")
        if (staffBody.clinic_id != license.clinic_id) throw OpenRecordException(OpenErrorCode.CHAIN, "staff belongs to another clinic")
        verified(unb64u(staffBody.pub), buf.copyOfRange(0, sigAt), buf.copyOfRange(sigAt, buf.size), "record not signed by staff")

        val ephPub = buf.copyOfRange(staff.end, staff.end + 32)
        val nonce = buf.copyOfRange(staff.end + 32, headerEnd)
        val record = try {
            val shared = X25519.computeSharedSecret(patientPrivate, ephPub)
            val key = Hkdf.computeHkdf("HMACSHA256", shared, ephPub + patientPublic, HKDF_INFO, 32)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(buf, 0, headerEnd)
            }
            json.decodeFromString<PatientCopy>(inflate(cipher.doFinal(buf, headerEnd, sigAt - headerEnd)).decodeToString())
        } catch (e: Exception) {
            // The signature already passed, so a failure here means another patient's key.
            throw OpenRecordException(OpenErrorCode.NOT_FOR_THIS_PHONE)
        }

        // Judged at issue time so patients keep records after a clinic's license lapses (spec §8.1).
        fun within(nbf: Long, exp: Long) = record.iat in nbf..exp
        if (!within(license.nbf, license.exp) || !within(staffBody.nbf, staffBody.exp)) {
            throw OpenRecordException(OpenErrorCode.CHAIN, "certificate not valid at issue time")
        }
        if (now > record.exp) throw OpenRecordException(OpenErrorCode.EXPIRED)
        return OpenedRecord(record, license, staffBody)
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("truncated deflate stream")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}
