package ai.rootforge.os.adb

import ai.rootforge.os.core.leBuffer
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/** The host's ADB identity: signs auth tokens and exports the public key in Android's format. */
interface AdbKey {
    /** Signs the 20-byte AUTH_TOKEN the way adbd verifies it (PKCS#1 v1.5 over a SHA-1 DigestInfo). */
    fun signToken(token: ByteArray): ByteArray

    /** The AUTH_RSAPUBLICKEY payload: base64 of the mincrypt struct, a label, and a NUL. */
    fun publicKeyPayload(): ByteArray

    /** The MD5 fingerprint of the key text, the value the target's "Allow USB debugging?" dialog shows. */
    fun fingerprint(): String
}

class SoftwareAdbKey(private val keyPair: KeyPair, private val label: String = "rootforge@android") : AdbKey {
    constructor(privateKey: PrivateKey, publicKey: RSAPublicKey, label: String = "rootforge@android") :
        this(KeyPair(publicKey, privateKey), label)

    private val publicKey = keyPair.public as RSAPublicKey

    override fun signToken(token: ByteArray): ByteArray {
        require(token.size == 20) { "AUTH_TOKEN must be 20 bytes, got ${token.size}" }
        // NONEwithRSA applies PKCS#1 type-1 padding without hashing; the token is already the digest.
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(keyPair.private)
        sig.update(SHA1_DIGEST_INFO_PREFIX + token)
        return sig.sign()
    }

    override fun publicKeyPayload(): ByteArray = AdbKeyFormat.publicKeyPayload(publicKey, label)

    internal fun keyPairForStorage(): KeyPair = keyPair

    override fun fingerprint(): String {
        val text = Base64.getEncoder().encode(AdbKeyFormat.encodePublicKey(publicKey))
        return java.security.MessageDigest.getInstance("MD5").digest(text)
            .joinToString(":") { "%02X".format(it) }
    }

    companion object {
        fun generate(): SoftwareAdbKey {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            return SoftwareAdbKey(gen.generateKeyPair())
        }

        val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}

/** Android's `RSAPublicKey` struct from libmincrypt, which adbd parses from AUTH_RSAPUBLICKEY. */
object AdbKeyFormat {
    private const val WORDS = 64 // 2048-bit modulus

    fun encodePublicKey(key: RSAPublicKey): ByteArray {
        val n = key.modulus
        require(n.bitLength() in 2041..2048) { "ADB requires a 2048-bit RSA key" }
        val two32 = BigInteger.ONE.shiftLeft(32)
        // n0inv = -(n^-1) mod 2^32
        val n0inv = two32.subtract(n.mod(two32).modInverse(two32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(WORDS * 32 * 2).mod(n)
        val buf = leBuffer(4 + 4 + WORDS * 4 + WORDS * 4 + 4)
        buf.putInt(WORDS)
        buf.putInt(n0inv)
        buf.put(toLittleEndian(n, WORDS * 4))
        buf.put(toLittleEndian(rr, WORDS * 4))
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }

    fun publicKeyPayload(key: RSAPublicKey, label: String): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(encodePublicKey(key))
        return "$b64 $label".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }

    private fun toLittleEndian(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
        val out = ByteArray(size)
        for (i in be.indices) out[i] = be[be.size - 1 - i]
        return out
    }
}
