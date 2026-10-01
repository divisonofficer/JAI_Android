package com.cgjnkim.mobile_jai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.security.bc.BCSecurityProvider
import java.io.File
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The lab's SMB share as an export destination: \\host\share\folder, a subfolder per export.
 *
 * The password is typed in on the phone and kept only there, encrypted with a key that
 * never leaves the Android Keystore; it is not in the code or the repository.
 */
object NetworkStorage {

    data class Config(val host: String, val share: String, val folder: String, val user: String, val domain: String) {
        val display: String get() = "\\\\$host\\$share\\$folder"
    }

    private const val PREFS = "network_storage"
    private const val KEY_ALIAS = "mobile_jai_smb"

    /** Where the lab keeps data; the user and password are the user's to enter. */
    private val DEFAULT = Config(host = "bean.postech.ac.kr", share = "data", folder = "jinnyeong", user = "", domain = "")

    fun config(context: Context): Config {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            host = p.getString("host", DEFAULT.host)!!,
            share = p.getString("share", DEFAULT.share)!!,
            folder = p.getString("folder", DEFAULT.folder)!!,
            user = p.getString("user", DEFAULT.user)!!,
            domain = p.getString("domain", DEFAULT.domain)!!,
        )
    }

    fun hasPassword(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("password")

    /** Saves the settings; a null [password] keeps the one stored. */
    fun save(context: Context, config: Config, password: CharArray?) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("host", config.host.trim())
            .putString("share", config.share.trim().trim('\\', '/'))
            .putString("folder", config.folder.trim().trim('\\', '/'))
            .putString("user", config.user.trim())
            .putString("domain", config.domain.trim())
        if (password != null) edit.putString("password", encrypt(String(password)))
        edit.apply()
    }

    private fun password(context: Context): CharArray {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("password", null)
            ?: throw IllegalStateException("no password set for the network storage")
        return decrypt(stored).toCharArray()
    }

    /**
     * Logs in, opens the share and looks for the folder. Returns a line for the user:
     * what was found, or why it failed.
     */
    fun test(context: Context): Result<String> = runCatching {
        val c = config(context)
        session(context, c) { share ->
            if (!share.folderExists(c.folder)) throw IllegalStateException("${c.display} does not exist")
            val count = share.list(c.folder).count { it.fileName != "." && it.fileName != ".." }
            "${c.display}: connected, $count entries"
        }
    }

    /**
     * Copies [folder] and everything under it to \\host\share\folder\<folder name>.
     * @param onProgress (bytes sent, bytes in all), often: throttle what it draws
     */
    fun upload(context: Context, folder: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): Result<String> = runCatching {
        val c = config(context)
        val files = folder.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }
        var sent = 0L
        session(context, c) { share ->
            val root = "${c.folder}\\${folder.name}"
            mkdirs(share, root)
            onProgress(0, total)
            val buffer = ByteArray(1 shl 16)
            for (f in files) {
                val rel = f.relativeTo(folder).path.replace('/', '\\')
                rel.substringBeforeLast('\\', "").takeIf { it.isNotEmpty() }?.let { mkdirs(share, "$root\\$it") }
                share.openFile(
                    "$root\\$rel",
                    setOf(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null,
                ).use { remote ->
                    remote.outputStream.use { out ->
                        f.inputStream().use { input ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                sent += n
                                onProgress(sent, total)
                            }
                        }
                    }
                }
            }
            onProgress(total, total)
            "\\\\${c.host}\\${c.share}\\$root (${files.size} files, ${"%.1f".format(total / 1e6)} MB)"
        }
    }

    private fun mkdirs(share: DiskShare, path: String) {
        var cur = ""
        for (part in path.split('\\').filter { it.isNotEmpty() }) {
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!share.folderExists(cur)) share.mkdir(cur)
        }
    }

    private fun <T> session(context: Context, c: Config, block: (DiskShare) -> T): T {
        // Bouncy Castle's lightweight API rather than JCA, which on Android resolves "BC"
        // to the platform's stripped-down copy without the MD4 that NTLM needs.
        val client = SMBClient(
            SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        val pw = password(context)
        try {
            client.connect(c.host).use { connection ->
                val session = connection.authenticate(AuthenticationContext(c.user, pw, c.domain.ifEmpty { null }))
                (session.connectShare(c.share) as DiskShare).use { share -> return block(share) }
            }
        } finally {
            pw.fill('\u0000')
            client.close()
        }
    }

    // ---- password at rest -----------------------------------------------------------

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, sealed) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(sealed))
    }
}
