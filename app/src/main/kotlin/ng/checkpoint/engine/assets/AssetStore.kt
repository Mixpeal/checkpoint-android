package ng.checkpoint.engine.assets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Fetches the model pack from the CDN once, then verifies it forever.
 *
 * This class exists because a truncated download is indistinguishable from a broken app.
 * In the web prototype tokenizer.json arrived 14,284,760 bytes of an expected 17,082,730
 * and the failure surfaced three debugging rounds later as an unrelated-looking parse
 * error. Every asset therefore carries a SHA-256 and a byte count, checked after download
 * AND on every cold start.
 */

@Serializable data class AssetEntry(val path: String, val bytes: Long, val sha256: String)
@Serializable data class AssetManifest(val version: String, val baseUrl: String, val files: List<AssetEntry>)

sealed interface AssetState {
    data object Ready : AssetState
    data class Fetching(val file: String, val percent: Int) : AssetState
    /** Hashing 123 MB is not instant. Say so rather than showing a still screen. */
    data class Checking(val file: String) : AssetState
    data class Failed(val file: String, val reason: String) : AssetState
}

class AssetStore(private val root: File) {

    fun fileFor(entry: AssetEntry): File = File(root, entry.path)

    /**
     * Places a model that was copied onto the device by hand, instead of downloading it.
     *
     * 123 MB is a real cost on Nigerian mobile data, and at a roadside it may be no cost
     * anyone can pay. Copying the file from a laptop or another phone is the cheap path.
     *
     * This only moves bytes into place. It deliberately does not verify: `ensure` is the
     * single gate every asset passes, so a hand-copied file gets exactly the same check a
     * downloaded one does, and the file is hashed once rather than three times.
     */
    suspend fun adopt(manifest: AssetManifest, from: File?): Int = withContext(Dispatchers.IO) {
        if (from == null || !from.isDirectory) return@withContext 0
        var placed = 0
        for (entry in manifest.files) {
            val target = fileFor(entry)
            if (target.isFile && target.length() == entry.bytes) continue

            // Size only. A wrong hash is caught by ensure; this just avoids copying
            // something that obviously is not the file.
            val candidate = File(from, File(entry.path).name)
            if (!candidate.isFile || candidate.length() != entry.bytes) continue

            target.parentFile?.mkdirs()
            candidate.copyTo(target, overwrite = true)
            placed++
        }
        placed
    }

    /** Downloads what is missing or corrupt. Never proceeds with an asset that fails its hash. */
    suspend fun ensure(manifest: AssetManifest, onState: (AssetState) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            for (entry in manifest.files) {
                val target = fileFor(entry)
                onState(AssetState.Checking(entry.path))
                if (verify(target, entry)) continue

                target.parentFile?.mkdirs()
                target.delete()               // a partial file must never be resumed into
                onState(AssetState.Fetching(entry.path, 0))
                try {
                    download("${manifest.baseUrl}/${entry.path}", target, entry.bytes) { pct ->
                        onState(AssetState.Fetching(entry.path, pct))
                    }
                } catch (err: Exception) {
                    target.delete()
                    onState(AssetState.Failed(entry.path, err.message ?: "download failed"))
                    return@withContext false
                }
                if (!verify(target, entry)) {
                    target.delete()
                    onState(AssetState.Failed(entry.path, "checksum mismatch"))
                    return@withContext false
                }
            }
            onState(AssetState.Ready)
            true
        }

    /** Size first because it is cheap, then the hash. Both must pass. */
    fun verify(file: File, entry: AssetEntry): Boolean =
        file.isFile && file.length() == entry.bytes && sha256(file) == entry.sha256

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, target: File, expected: Long, onPercent: (Int) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var total = 0L; var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n); total += n
                        if (expected > 0) {
                            val pct = ((total * 100) / expected).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onPercent(pct) }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true }
        fun parseManifest(raw: String): AssetManifest = json.decodeFromString(raw)
    }
}
