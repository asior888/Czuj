package com.asior.czuj

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Eksport ustawień do pliku zaszyfrowanego hasłem (zamiast konta i logowania).
// Format: "CZUJ1" | sól 16 B | IV 12 B | AES-256-GCM. Klucz: PBKDF2-SHA256, 310 tys. iteracji.
// Czysty Kotlin (javax.crypto): testowany w app/src/test.
object Backup {
    private val MAGIC = "CZUJ1".toByteArray()
    const val ITER = 310_000
    const val MIN_PASS = 8

    private fun key(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pass, salt, iter, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun seal(plain: ByteArray, pass: String, iter: Int = ITER): ByteArray {
        require(pass.length >= MIN_PASS) { "hasło za krótkie" }
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass.toCharArray(), salt, iter), GCMParameterSpec(128, iv))
        c.updateAAD(MAGIC)
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    /** null = złe hasło, uszkodzony albo obcy plik. */
    fun open(data: ByteArray, pass: String, iter: Int = ITER): ByteArray? {
        val h = MAGIC.size
        if (data.size < h + 28 + 16 || !data.copyOfRange(0, h).contentEquals(MAGIC)) return null
        return try {
            val salt = data.copyOfRange(h, h + 16)
            val iv = data.copyOfRange(h + 16, h + 28)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(pass.toCharArray(), salt, iter), GCMParameterSpec(128, iv))
            c.updateAAD(MAGIC)
            c.doFinal(data, h + 28, data.size - h - 28)
        } catch (e: Exception) {
            null
        }
    }
}
