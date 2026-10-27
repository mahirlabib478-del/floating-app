package com.floatclip.app

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object TotpGenerator {
    private const val PERIOD_SECONDS = 30L
    private const val DIGITS = 6
    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun generate(secret: String, timeMillis: Long = System.currentTimeMillis()): String {
        val normalized = secret
            .uppercase(Locale.US)
            .filterNot { it.isWhitespace() || it == '-' || it == '=' }

        require(normalized.isNotEmpty()) {
            "Clipboard is empty. Copy a TOTP secret key first."
        }

        val key = decodeBase32(normalized)
        require(key.isNotEmpty()) { "The clipboard does not contain a valid Base32 secret." }

        val counter = (timeMillis / 1000L) / PERIOD_SECONDS
        val message = ByteBuffer.allocate(8).putLong(counter).array()
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        val hash = mac.doFinal(message)

        val offset = hash[hash.lastIndex].toInt() and 0x0F
        val binaryCode =
            ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)

        return (binaryCode % 1_000_000).toString().padStart(DIGITS, '0')
    }

    fun secondsRemaining(timeMillis: Long = System.currentTimeMillis()): Int {
        val elapsed = (timeMillis / 1000L) % PERIOD_SECONDS
        return (PERIOD_SECONDS - elapsed).toInt()
    }

    private fun decodeBase32(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0

        for (character in value) {
            val digit = BASE32_ALPHABET.indexOf(character)
            require(digit >= 0) {
                "Invalid Base32 character in clipboard. Copy the secret key, not the current code."
            }

            buffer = (buffer shl 5) or digit
            bitsLeft += 5

            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.write((buffer shr bitsLeft) and 0xFF)
            }
        }

        return output.toByteArray()
    }
}
