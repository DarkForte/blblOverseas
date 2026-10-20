package blbl.cat3399.core.cdn

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

/** Small network-independent byte transport, so mid-read recovery can be tested without Android. */
interface CdnByteStream {
    fun open(consumed: Long): Long
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

class CdnResumableStream(
    private val route: CdnRoute,
    private val offset: Long,
    private val requestedLength: Long,
    private val factory: (CdnCandidate) -> CdnByteStream,
) {
    private var upstream: CdnByteStream? = null
    private var candidate: CdnCandidate? = null
    private var consumed = 0L
    private var total = requestedLength
    private val failed = HashSet<String>()
    private var recoveries = 0

    fun open(): Long = reopen()

    private fun releaseUpstream() {
        runCatching { upstream?.close() }
        upstream = null
        route.end()
    }

    private fun reopen(): Long {
        releaseUpstream()
        var last: IOException? = null
        for (next in route.openOrder().filter { it.host !in failed }) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val stream = factory(next)
            route.begin(next, offset + consumed, if (total < 0) -1 else total - consumed)
            try {
                val remaining = stream.open(consumed)
                if (total < 0 && remaining >= 0) total = consumed + remaining
                if (total >= 0 && remaining >= 0 && remaining != total - consumed) throw IOException("CDN length mismatch")
                upstream = stream
                candidate = next
                route.opened(remaining)
                route.accepted(next)
                return if (total >= 0) total - consumed else remaining
            } catch (e: IOException) {
                runCatching { stream.close() }
                route.end()
                if (e is InterruptedIOException && Thread.currentThread().isInterrupted) throw e
                failed += next.host
                route.failed(next)
                last = e
            }
        }
        throw last ?: IOException("No remaining CDN route")
    }

    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (total >= 0 && consumed == total) return -1
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
            val current = checkNotNull(candidate) { "open first" }
            if (route.preferred().host != current.host && route.preferred().host !in failed && recoveries < 4) {
                recoveries++
                reopen()
            }
            try {
                val limit = if (total < 0) length else minOf(length.toLong(), total - consumed).toInt()
                val n = checkNotNull(upstream).read(buffer, offset, limit)
                if (n < 0 && total >= 0 && consumed < total) throw EOFException("Truncated CDN response")
                if (n > 0) { consumed += n; route.progress(n) }
                return n
            } catch (e: IOException) {
                if (e is InterruptedIOException && Thread.currentThread().isInterrupted) throw e
                val active = checkNotNull(candidate)
                failed += active.host
                route.failed(active)
                if (recoveries++ >= 4) throw e
                reopen() // DataSpec.subrange(consumed) preserves exact byte continuity
            }
        }
    }

    fun close() = releaseUpstream()
}
