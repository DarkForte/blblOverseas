package blbl.cat3399.core.cdn

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CdnRangeProbeTest {
    private fun runProbe(status: Int, range: String?, body: ByteArray, declaredLength: Int = body.size): Pair<CdnProbeResult, String> {
        val request = AtomicReference("")
        val server = ServerSocket(0)
        val worker = Executors.newSingleThreadExecutor()
        val future = worker.submit {
            server.accept().use { socket ->
                socket.soTimeout = 3000
                val reader = socket.getInputStream().bufferedReader()
                val headers = StringBuilder()
                while (true) { val line = reader.readLine() ?: break; if (line.isEmpty()) break; headers.append(line).append('\n') }
                request.set(headers.toString())
                val output = socket.getOutputStream()
                val header = "HTTP/1.1 $status Test\r\nContent-Length: $declaredLength\r\nConnection: close\r\n" +
                    (range?.let { "Content-Range: $it\r\n" } ?: "") + "\r\n"
                output.write(header.toByteArray()); output.write(body); output.flush()
            }
        }
        val probe = CdnRangeProbe(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build())
        try {
            val result = probe.measure(CdnCandidate("127.0.0.1", "http://127.0.0.1:${server.localPort}/123.m4s?sign=secret", true), 100, 20_000)
            future.get(3, TimeUnit.SECONDS)
            return result to request.get()
        } finally { probe.close(); server.close(); worker.shutdownNow() }
    }
    @Test fun sendsBoundedRangeAndAcceptsExact206() {
        val (result, request) = runProbe(206, "bytes 100-20099/100000", ByteArray(20_000) { 42 })
        assertTrue(result.ok)
        assertEquals(20_000, result.bytes)
        assertNotNull(result.digest)
        assertTrue(request.contains("Range: bytes=100-20099", ignoreCase = true))
        assertTrue(request.contains("Accept-Encoding: identity", ignoreCase = true))
        assertFalse(request.contains("Cookie:", ignoreCase = true))
    }
    @Test fun rejectsFullFile200EvenWhenBodyIsFast() {
        assertFalse(runProbe(200, null, ByteArray(20_000)).first.ok)
    }
    @Test fun rejects403WrongOffsetWrongLengthAndTruncation() {
        assertFalse(runProbe(403, null, ByteArray(0)).first.ok)
        assertFalse(runProbe(206, "bytes 0-19999/100000", ByteArray(20_000)).first.ok)
        assertFalse(runProbe(206, "bytes 100-20099/999999999999999999999999999", ByteArray(20_000)).first.ok)
        assertFalse(runProbe(206, "bytes 100-20099/100000", ByteArray(19_000)).first.ok)
        assertFalse(runProbe(206, "bytes 100-20099/100000", ByteArray(10_000), 20_000).first.ok)
    }
    @Test fun closedProbeCannotStartNetworkRequests() {
        val p = CdnRangeProbe(OkHttpClient())
        p.close()
        val result = p.measure(CdnCandidate("localhost", "http://localhost:1/a.m4s", true), 0, 20_000)
        assertFalse(result.ok)
        assertEquals("cancelled", result.error)
    }
}
