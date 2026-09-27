package app.voidfiles.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

data class VaultEntry(val id: String, val name: String, val size: Long, val added: Long, val origin: String)

/**
 * Encrypted safe inside the app's private storage. Files are encrypted with an AES-256 key that lives in
 * the Android Keystore (it never leaves the secure hardware); the list of names is encrypted too.
 */
class Vault(context: Context) {
    private val dir = File(context.filesDir, "vault").apply { mkdirs() }
    private val indexFile = File(dir, "index.bin")
    private val lock = Any()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_CBC)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypting(out: OutputStream): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        out.write(cipher.iv)
        return CipherOutputStream(out, cipher)
    }

    private fun decrypting(input: InputStream): InputStream {
        val iv = ByteArray(16)
        var read = 0
        while (read < 16) {
            val n = input.read(iv, read, 16 - read)
            if (n < 0) throw IOException("Tresor-Datei beschädigt")
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), IvParameterSpec(iv))
        return CipherInputStream(input, cipher)
    }

    fun entries(): List<VaultEntry> = synchronized(lock) {
        if (!indexFile.exists()) return emptyList()
        val bytes = decrypting(FileInputStream(indexFile)).use { it.readBytes() }
        val arr = JSONArray(String(bytes))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VaultEntry(o.getString("id"), o.getString("name"), o.optLong("size"), o.optLong("added"), o.optString("origin"))
        }.sortedByDescending { it.added }
    }

    private fun saveIndex(list: List<VaultEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("size", it.size).put("added", it.added).put("origin", it.origin))
        }
        val tmp = File(dir, "index.tmp")
        encrypting(FileOutputStream(tmp)).use { it.write(arr.toString().toByteArray()) }
        if (!tmp.renameTo(indexFile)) {
            indexFile.delete(); tmp.renameTo(indexFile)
        }
    }

    /** Encrypts [node] into the vault. The original is not touched here. */
    fun add(fs: FileSystem, node: Node, origin: String, sink: ProgressSink): VaultEntry {
        if (node.isDirectory) throw IOException("Ordner bitte zuerst als ZIP komprimieren")
        val id = UUID.randomUUID().toString()
        val target = File(dir, "$id.bin")
        sink.onFile(node.name)
        try {
            fs.openInput(node).use { input -> encrypting(FileOutputStream(target)).use { out -> fs.copyStreamRaw(input, out, sink) } }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        val entry = VaultEntry(id, node.name, node.size, System.currentTimeMillis(), origin)
        synchronized(lock) { saveIndex(entries() + entry) }
        return entry
    }

    /** Decrypts [entry] into [destDir] and returns the new file. */
    fun export(fs: FileSystem, entry: VaultEntry, destDir: Node, sink: ProgressSink): Node {
        val target = fs.createFile(destDir, fs.uniqueName(destDir, entry.name))
        try {
            sink.onFile(entry.name)
            decrypting(FileInputStream(File(dir, "${entry.id}.bin"))).use { input ->
                fs.openOutput(target).use { out -> fs.copyStreamRaw(input, out, sink) }
            }
        } catch (e: Throwable) {
            runCatching { fs.delete(target) }
            throw e
        }
        return target
    }

    /** Decrypts into a private temp folder so the file can be previewed. */
    fun decryptToTemp(entry: VaultEntry, tempDir: File): File {
        tempDir.mkdirs()
        val out = File(tempDir, entry.name)
        decrypting(FileInputStream(File(dir, "${entry.id}.bin"))).use { input -> FileOutputStream(out).use { input.copyTo(it, 256 * 1024) } }
        return out
    }

    fun remove(entry: VaultEntry) = synchronized(lock) {
        File(dir, "${entry.id}.bin").delete()
        saveIndex(entries().filterNot { it.id == entry.id })
    }

    /** Small self check so the UI can tell the user when the keystore is unusable. */
    fun selfTest(): Boolean = runCatching {
        val buf = ByteArrayOutputStream()
        encrypting(buf).use { it.write(42) }
        decrypting(buf.toByteArray().inputStream()).use { it.read() } == 42
    }.getOrDefault(false)

    companion object {
        private const val ALIAS = "void_files_vault"
        private const val TRANSFORMATION = "AES/CBC/PKCS7Padding"
    }
}
