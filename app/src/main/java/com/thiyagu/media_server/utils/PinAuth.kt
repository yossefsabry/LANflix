package com.thiyagu.media_server.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object PinAuth {
    private const val SALT_BYTES = 16

    fun generateSalt(): String {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$pin:$salt".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidPin(pin: String): Boolean {
        return pin.length in 4..8 && pin.all { it.isDigit() }
    }
}
