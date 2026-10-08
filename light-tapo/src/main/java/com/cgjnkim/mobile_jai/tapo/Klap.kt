package com.cgjnkim.mobile_jai.tapo

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * TP-Link's KLAP v2 local protocol, the crypto half: no I/O here, so it runs on the JVM
 * in tests. Worked out from python-kasa's `KlapTransportV2` and checked against a Tapo
 * P110 (fw 1.3.0) on 2026-10-08.
 *
 * Two handshakes over HTTP prove that both sides know `authHash` -- derived from the TP-Link
 * account the plug is bound to -- without sending it, and seed a session:
 *
 *  1. POST /app/handshake1 with 16 random bytes. The plug answers with its own 16 bytes and
 *     `sha256(local + remote + authHash)`, which tells us which credentials it holds.
 *  2. POST /app/handshake2 with `sha256(remote + local + authHash)`.
 *
 * Each request is then AES-128-CBC with a per-request IV (12 fixed bytes and a 4-byte
 * sequence number), prefixed with a SHA-256 signature.
 */
class Klap(val localSeed: ByteArray, val remoteSeed: ByteArray, val authHash: ByteArray) {

    private val key = sha256("lsk".toByteArray(), localSeed, remoteSeed, authHash).copyOf(16)
    private val ivBase: ByteArray
    private val sig = sha256("ldk".toByteArray(), localSeed, remoteSeed, authHash).copyOf(28)

    /** Incremented before every request; the plug checks it against the URL's `seq`. */
    var seq: Int
        private set

    init {
        val iv = sha256("iv".toByteArray(), localSeed, remoteSeed, authHash)
        ivBase = iv.copyOf(12)
        seq = ((iv[28].toInt() and 0xff) shl 24) or ((iv[29].toInt() and 0xff) shl 16) or
            ((iv[30].toInt() and 0xff) shl 8) or (iv[31].toInt() and 0xff)
    }

    /** What to POST to /app/handshake2. */
    fun handshake2(): ByteArray = sha256(remoteSeed, localSeed, authHash)

    /** Encrypts [json] under the next sequence number; returns the body and that number. */
    fun encrypt(json: String): Pair<ByteArray, Int> {
        seq++
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv(seq)))
        val ct = cipher.doFinal(json.toByteArray())
        return (sha256(sig, int(seq), ct) + ct) to seq
    }

    /** Decrypts the plug's answer to the request sent as [seq]. */
    fun decrypt(body: ByteArray, seq: Int): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv(seq)))
        return String(cipher.doFinal(body, 32, body.size - 32))
    }

    private fun iv(seq: Int) = ivBase + int(seq)

    companion object {
        /**
         * What an unbound plug -- factory default, never added to the Tapo app -- accepts.
         * Blank first, then TP-Link's setup account, as python-kasa tries them; the P110
         * on the rig took the second.
         */
        val DEFAULT_CREDENTIALS = listOf("" to "", "test@tp-link.net" to "test", "kasa@tp-link.net" to "kasaSetup")

        fun authHash(username: String, password: String): ByteArray =
            sha256(sha1(username.toByteArray()), sha1(password.toByteArray()))

        fun newSeed(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

        /**
         * Which of [candidates] (auth hashes) the plug's handshake1 answer was made with, or
         * null if none: the plug is bound to an account we do not know.
         */
        fun match(localSeed: ByteArray, answer: ByteArray, candidates: List<ByteArray>): ByteArray? {
            if (answer.size < 48) return null
            val remote = answer.copyOf(16)
            val proof = answer.copyOfRange(16, 48)
            return candidates.firstOrNull { sha256(localSeed, remote, it).contentEquals(proof) }
        }

        internal fun sha256(vararg parts: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

        private fun sha1(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-1").digest(b)

        private fun int(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    }
}
