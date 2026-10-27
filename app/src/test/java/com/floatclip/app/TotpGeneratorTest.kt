package com.floatclip.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TotpGeneratorTest {
    @Test
    fun matchesRfc6238Sha1VectorAt59Seconds() {
        val secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
        assertEquals("287082", TotpGenerator.generate(secret, 59_000L))
    }

    @Test
    fun acceptsWhitespaceAndBase32Padding() {
        val secret = "GEZDGNBV GY3TQOJQGEZDGNBVGY3TQOJQ=="
        assertEquals("287082", TotpGenerator.generate(secret, 59_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSecretCharacters() {
        TotpGenerator.generate("this-is-not-a-valid-base32-secret-0")
    }
}
