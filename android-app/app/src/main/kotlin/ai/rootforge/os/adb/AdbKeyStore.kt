package ai.rootforge.os.adb

import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Persists the host's ADB key pair. The key lives in the app's private,
 * backup-excluded directory, the same trust level as `~/.android/adbkey` on a
 * desktop. Deleting it makes the target ask for authorisation again.
 */
class AdbKeyStore(private val dir: File) {
    private val privateFile get() = File(dir, "adbkey.pk8")
    private val publicFile get() = File(dir, "adbkey.x509")

    fun loadOrCreate(): AdbKey {
        load()?.let { return it }
        val key = SoftwareAdbKey.generate()
        persist(key)
        return key
    }

    fun reset(): AdbKey {
        privateFile.delete()
        publicFile.delete()
        return loadOrCreate()
    }

    private fun load(): AdbKey? {
        if (!privateFile.isFile || !publicFile.isFile) return null
        return runCatching {
            val kf = KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes()))
            val pub = kf.generatePublic(X509EncodedKeySpec(publicFile.readBytes()))
            SoftwareAdbKey(KeyPair(pub, priv))
        }.getOrNull()
    }

    private fun persist(key: SoftwareAdbKey) {
        dir.mkdirs()
        val pair = key.keyPairForStorage()
        privateFile.writeBytes(pair.private.encoded)
        publicFile.writeBytes(pair.public.encoded)
        privateFile.setReadable(false, false); privateFile.setReadable(true, true)
        privateFile.setWritable(false, false); privateFile.setWritable(true, true)
    }
}
