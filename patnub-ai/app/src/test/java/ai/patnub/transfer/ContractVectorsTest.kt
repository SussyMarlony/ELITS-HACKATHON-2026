package ai.patnub.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

/** Kotlin side of `contract/vectors.json`. Tests run with the app module as working directory. */
class ContractVectorsTest {
    private val v = Json.parseToJsonElement(File("../../contract/vectors.json").readText()).jsonObject

    @Test fun base45_matchesVectors() {
        for (pair in v.getValue("base45").jsonArray) {
            val (plain, encoded) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(encoded, Base45.encode(plain.encodeToByteArray()))
            assertEquals(plain, Base45.decode(encoded).decodeToString())
        }
    }

    @Test fun base45_rejectsJunk() {
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("GGW") }
        assertThrows(IllegalArgumentException::class.java) { Base45.decode("abc") }
    }

    @Test fun checkIn_matchesVectorByteForByte() {
        val c = v.getValue("check_in").jsonObject
        val checkIn = CheckIn(
            name = c.getValue("name").jsonPrimitive.content,
            age = c.getValue("age").jsonPrimitive.int,
            brgy = c.getValue("brgy").jsonPrimitive.content,
            pub = c.getValue("pub").jsonPrimitive.content,
        )
        assertEquals(v.getValue("check_in_text").jsonPrimitive.content, checkIn.encode())
    }

    @Test fun checkIn_omitsMissingBarangay() {
        val text = CheckIn("Jose", 67, null, "x").encode()
        assertEquals("""{"name":"Jose","age":67,"pub":"x"}""", Base45.decode(text.removePrefix(CheckIn.PREFIX)).decodeToString())
    }
}

class RecordCodecVectorsTest {
    private val v = Json.parseToJsonElement(File("../../contract/vectors.json").readText()).jsonObject
    private fun key(name: String, part: String) =
        java.util.Base64.getUrlDecoder().decode(v.getValue(name).jsonObject.getValue(part).jsonPrimitive.content)
    private val root = java.util.Base64.getUrlDecoder().decode(v.getValue("root_pub").jsonPrimitive.content)
    private val qr = v.getValue("record_qr").jsonPrimitive.content
    private val now = v.getValue("now").jsonPrimitive.content.toLong()

    private fun open(text: String = qr, who: String = "patient", at: Long = now) =
        RecordCodec.open(text, root, key(who, "priv"), key(who, "pub"), at)

    private fun code(block: () -> Unit): OpenErrorCode =
        (runCatching(block).exceptionOrNull() as OpenRecordException).code

    @Test fun opensVectorRecord() {
        val out = open()
        val expected = v.getValue("expected_record").jsonObject
        assertEquals(expected.getValue("rid").jsonPrimitive.content, out.record.rid)
        assertEquals(expected.getValue("assessment").jsonPrimitive.content, out.record.assessment)
        assertEquals("Amlodipine", out.record.meds.single().name)
        assertEquals("once daily, in the morning", out.record.meds.single().frequency)
        assertEquals(4, out.record.warnings.size)
        assertEquals(v.getValue("expected_staff_name").jsonPrimitive.content, out.staff.name)
        assertEquals(v.getValue("expected_clinic_name").jsonPrimitive.content, out.license.clinic_name)
    }

    @Test fun otherPhoneCannotOpen() = assertEquals(OpenErrorCode.NOT_FOR_THIS_PHONE, code { open(who = "other_patient") })

    @Test fun expired() = assertEquals(OpenErrorCode.EXPIRED, code { open(at = now + 2 * 86_400) })

    @Test fun wrongRoot() = assertEquals(
        OpenErrorCode.SIGNATURE,
        code { RecordCodec.open(qr, ByteArray(32) { 9 }, key("patient", "priv"), key("patient", "pub"), now) },
    )

    @Test fun tampered() {
        val bytes = Base45.decode(qr.removePrefix(RecordCodec.PREFIX))
        bytes[bytes.size - 80] = (bytes[bytes.size - 80].toInt() xor 1).toByte()
        assertEquals(OpenErrorCode.SIGNATURE, code { open(RecordCodec.PREFIX + Base45.encode(bytes)) })
    }

    @Test fun junk() {
        assertEquals(OpenErrorCode.FORMAT, code { open("hello") })
        assertEquals(OpenErrorCode.FORMAT, code { open(RecordCodec.PREFIX + "abc") })
    }
}
