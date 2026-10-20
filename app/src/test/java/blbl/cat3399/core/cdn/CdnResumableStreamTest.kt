package blbl.cat3399.core.cdn

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

class CdnResumableStreamTest {
    private val original = CdnCandidate("upos-sz-mirroraliov.bilivideo.com", "https://upos-sz-mirroraliov.bilivideo.com/123-1-30080.m4s?sign=original", true)
    private val other = CdnCandidate("upos-sz-mirrorali.bilivideo.com", "https://upos-sz-mirrorali.bilivideo.com/123-1-30080.m4s?sign=original", false)
    private fun route() = CdnRoute(listOf(original, other), 1_000_000, "VIDEO", CdnRangeProbe(OkHttpClient()), { 5000 }, {})
    private val bytes = ByteArray(25_000) { (it % 251).toByte() }
    private fun drain(stream: CdnResumableStream): ByteArray {
        val result = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) { val n = stream.read(buf, 0, buf.size); if (n < 0) break; result.write(buf, 0, n) }
        return result.toByteArray()
    }
    private fun source(start: (Long) -> Unit = {}, failAfter: Int = Int.MAX_VALUE): CdnByteStream = object : CdnByteStream {
        var pos = 0
        override fun open(consumed: Long): Long { start(consumed); pos = consumed.toInt(); return bytes.size - consumed }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= failAfter) throw IOException("simulated mid-read failure")
            if (pos == bytes.size) return -1
            val n = minOf(length, bytes.size - pos, failAfter - pos)
            bytes.copyInto(buffer, offset, pos, pos + n); pos += n; return n
        }
        override fun close() {}
    }
    @Test fun switchPreservesExactBytesWithoutRestartingFromZero() {
        val r = route()
        val opened = mutableListOf<Pair<String, Long>>()
        val stream = CdnResumableStream(r, 512, bytes.size.toLong()) { c -> source({ opened += c.host to it }) }
        stream.open()
        val first = ByteArray(4096)
        assertEquals(4096, stream.read(first, 0, first.size))
        r.select(other.host)
        val rest = drain(stream)
        assertArrayEquals(bytes, first + rest)
        assertEquals(listOf(original.host to 0L, other.host to 4096L), opened)
        stream.close()
    }
    @Test fun midReadFailureRestoresOriginalUrlAtExactOffset() {
        val r = route().also { it.select(other.host) }
        val opened = mutableListOf<Pair<String, Long>>()
        val stream = CdnResumableStream(r, 0, bytes.size.toLong()) { c ->
            source({ opened += c.url to it }, if (c == other) 4096 else Int.MAX_VALUE)
        }
        stream.open()
        assertArrayEquals(bytes, drain(stream))
        assertEquals(listOf(other.url to 0L, original.url to 4096L), opened)
        assertEquals(original, r.preferred())
        assertFalse(r.openOrder().contains(other))
        stream.close()
    }
    @Test fun switchedNode403FallsBackAndRemembersFailure() {
        val r = route().also { it.select(other.host) }
        val stream = CdnResumableStream(r, 0, bytes.size.toLong()) { c ->
            if (c == other) source({ throw IOException("HTTP 403") }) else source()
        }
        stream.open()
        assertArrayEquals(bytes, drain(stream))
        assertEquals(original, r.preferred())
        assertTrue(other.host in r.unavailable())
        stream.close()
    }
    @Test fun truncatedBodyIsRetriedWithoutDuplicatingBytes() {
        val r = route().also { it.select(other.host) }
        val stream = CdnResumableStream(r, 0, bytes.size.toLong()) { c ->
            if (c == other) object : CdnByteStream {
                var delivered = false
                override fun open(consumed: Long) = bytes.size.toLong()
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (delivered) return -1
                    bytes.copyInto(buffer, offset, 0, 100); delivered = true; return 100
                }
                override fun close() {}
            } else source()
        }
        stream.open()
        assertArrayEquals(bytes, drain(stream))
        stream.close()
    }
    @Test fun wrongLengthAndAllFailedRoutesPropagateInsteadOfLooping() {
        val r = route()
        var attempts = 0
        val stream = CdnResumableStream(r, 0, bytes.size.toLong()) { _ -> object : CdnByteStream {
            override fun open(consumed: Long): Long { attempts++; return 5 }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun close() {}
        } }
        try { stream.open(); fail("must fail") } catch (_: IOException) {}
        assertEquals(1, attempts) // untested synthesized alternatives are never playback fallback
        stream.close()
    }
    @Test fun oldHostErrorsDoNotPoisonHealthyBackup() {
        val r = route()
        r.failed(original)
        r.failed(original)
        r.accepted(other)
        r.begin(other, 0, 4_000_000)
        r.opened(4_000_000)
        r.progress(500_000)
        assertNull(r.trigger(true, 0, 50_000, 1f))
        r.probe.close()
    }

}
