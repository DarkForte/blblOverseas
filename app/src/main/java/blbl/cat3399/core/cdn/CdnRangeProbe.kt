package blbl.cat3399.core.cdn

import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface CdnProber : AutoCloseable {
    fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult
}

/** Bounded Range GETs on a dedicated client. Never touches API/login/danmaku requests. */
class CdnRangeProbe(client: OkHttpClient) : CdnProber {
    private val client = client.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(CdnRouting.RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).build()
    private val calls = java.util.Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())
    @Volatile private var closed = false

    override fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult {
        if (closed) return CdnProbeResult(candidate.host, 0, 0, error = "cancelled")
        if (start < 0 || bytes !in 16_000..CdnRouting.RACE_BYTES || start > Long.MAX_VALUE - bytes)
            return CdnProbeResult(candidate.host, 0, 0, error = "invalid range")
        val begin = System.nanoTime()
        val end = start + bytes - 1
        val req = Request.Builder().url(candidate.url).header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity").header("Cache-Control", "no-cache").build()
        val call = client.newCall(req)
        calls += call
        if (closed) call.cancel()
        return try {
            call.execute().use { response ->
                if (response.code != 206) throw ProbeFailure("HTTP ${response.code}")
                val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                    ?: throw IOException("invalid Content-Range")
                if (match.groupValues[1].toLongOrNull() != start || match.groupValues[2].toLongOrNull() != end ||
                    (match.groupValues[3].toLongOrNull() ?: 0) <= end || response.header("Content-Encoding")?.let { it != "identity" } == true
                ) throw IOException("wrong range")
                val body = response.body ?: throw IOException("empty body")
                if (body.contentLength() != -1L && body.contentLength() != bytes.toLong()) throw IOException("wrong length")
                val hash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(16 * 1024)
                var read = 0
                body.byteStream().use { stream ->
                    while (read < bytes) {
                        val n = stream.read(buffer, 0, minOf(buffer.size, bytes - read))
                        if (n < 0) throw IOException("short body")
                        hash.update(buffer, 0, n)
                        read += n
                    }
                }
                CdnProbeResult(candidate.host, read, ((System.nanoTime() - begin) / 1_000_000).coerceAtLeast(1),
                    digest = hash.digest().joinToString("") { "%02x".format(it) })
            }
        } catch (e: IOException) {
            // Store categories only: exception messages may contain signed URLs.
            CdnProbeResult(candidate.host, 0, ((System.nanoTime() - begin) / 1_000_000).coerceAtLeast(1),
                error = if (call.isCanceled()) "cancelled/timeout" else if (e is ProbeFailure) e.message else "range/network")
        } finally {
            calls -= call
        }
    }

    private class ProbeFailure(message: String) : IOException(message)

    override fun close() {
        closed = true
        calls.forEach { it.cancel() }
    }
}
