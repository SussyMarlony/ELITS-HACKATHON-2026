package ai.patnub.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidateProfileTest {
    @Test fun valid() = assertNull(validateProfile("Maria Dela Cruz", " 54 "))
    @Test fun blankName() = assertEquals("Enter your name.", validateProfile("  ", "54"))
    @Test fun nonNumericAge() = assertNotNull(validateProfile("Maria", "fifty"))
    @Test fun ageOutOfRange() = assertNotNull(validateProfile("Maria", "121"))
}
