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
