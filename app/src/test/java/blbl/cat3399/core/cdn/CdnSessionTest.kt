package blbl.cat3399.core.cdn

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class CdnSessionTest {
    private val original = "upos-sz-mirroraliov.bilivideo.com"
    private val fast = "upos-sz-mirrorali.bilivideo.com"
    private val urls = listOf("https://$original/a/123-1-30080.m4s?sign=ov", "https://$fast/a/123-1-30080.m4s?sign=ali")
    private fun waitFor(check: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000
        while (!check() && System.nanoTime() < until) Thread.sleep(5)
        assertTrue("asynchronous race did not finish", check())
    }
    @Test fun healthyStreamNotProbedButBufferingRacesAndSelectsFastHost() {
        val count = AtomicInteger()
        val clock = AtomicLong(10_000)
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult {
                count.incrementAndGet()
                return CdnProbeResult(candidate.host, bytes, if (candidate.host == fast) 237 else 2170, "same")
            }
            override fun close() {}
        }
        CdnSession("auto", fast, clock::get, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 200_000, 4_000_000)
            route.opened(4_000_000)
            route.progress(500_000)
            session.update(true, 0, 50_000, 1f)
            assertEquals(0, count.get())
            session.update(false, 5000, 0, 1f)
            assertEquals(0, count.get())
            session.update(true, 5000, 0, 1f)
            waitFor { route.preferred().host == fast && route.manualDone }
            assertTrue(session.diagnostics().contains("→ $fast"))
            assertEquals(3, count.get())
            clock.addAndGet(5000)
            session.update(true, 5000, 0, 1f)
            assertEquals(3, count.get()) // switch cooldown
        }
    }
    @Test fun manualVerifiesAvailabilityWithoutRequiringSpeedGain() {
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int) = CdnProbeResult(candidate.host, bytes, if (candidate.host == fast) 1000 else 100, "same")
            override fun close() {}
        }
        CdnSession("manual", fast, { 10_000 }, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 0, 4_000_000)
            session.update(true, 0, 10_000, 1f)
            waitFor { route.preferred().host == fast && route.manualDone }
            assertTrue(route.manualDone)
        }
    }
    @Test fun failedManualProbePreservesOriginalAndDoesNotLoop() {
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int) =
                CdnProbeResult(candidate.host, if (candidate.host == fast) 0 else bytes, 100, "same", if (candidate.host == fast) "HTTP 403" else null)
            override fun close() {}
        }
        CdnSession("manual", fast, { 10_000 }, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 0, 4_000_000)
            session.update(true, 0, 10_000, 1f)
            waitFor { route.manualDone }
            assertEquals(original, route.preferred().host)
            assertFalse(session.diagnostics().contains("sign="))
        }
    }
    @Test fun noSwitchRacesAreBoundedAndOffDoesNothing() {
        val count = AtomicInteger()
        val clock = AtomicLong(10_000)
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult {
                count.incrementAndGet()
                return CdnProbeResult(candidate.host, bytes, 100, "same")
            }
            override fun close() {}
        }
        CdnSession("auto", fast, clock::get, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 0, 4_000_000)
            repeat(CdnRouting.MAX_RACES) { i ->
                clock.addAndGet(130_000)
                session.update(true, 5000, 0, 1f)
                waitFor { count.get() >= (i + 1) * 3 && !session.diagnostics().contains("测速中") }
            }
            clock.addAndGet(130_000)
            session.update(true, 5000, 0, 1f)
            assertEquals(36, count.get())
        }
        CdnSession("off", fast, clock::get, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 0, 4_000_000)
            session.update(true, 10000, 0, 1f)
            assertEquals(36, count.get())
        }
    }
    @Test fun switchingStopsAtFourEvenIfBufferingContinues() {
        val clock = AtomicLong(10_000)
        val count = AtomicInteger()
        val current = java.util.concurrent.atomic.AtomicReference(original)
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult {
                count.incrementAndGet()
                return CdnProbeResult(candidate.host, bytes, if (candidate.host == current.get()) 2000 else 200, "same")
            }
            override fun close() {}
        }
        CdnSession("auto", fast, clock::get, { probe }).use { session ->
            val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
            route.begin(route.preferred(), 0, 4_000_000)
            repeat(4) { i ->
                current.set(route.preferred().host)
                clock.addAndGet(130_000)
                session.update(true, 5000, 0, 1f)
                waitFor { count.get() == (i + 1) * 3 && route.preferred().host != current.get() && !session.diagnostics().contains("测速中") }
            }
            current.set(route.preferred().host)
            clock.addAndGet(130_000)
            session.update(true, 10000, 0, 1f)
            assertEquals(12, count.get())
            assertEquals(current.get(), route.preferred().host)
            assertTrue(session.diagnostics().contains("switch=4/4"))
        }
    }

    @Test fun closingSourceCancelsProbeAndCannotCommitLateWinner() {
        val started = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = object : CdnProber {
            override fun measure(candidate: CdnCandidate, start: Long, bytes: Int): CdnProbeResult {
                started.countDown()
                released.await(2, java.util.concurrent.TimeUnit.SECONDS)
                return CdnProbeResult(candidate.host, bytes, if (candidate.host == fast) 100 else 2000, "same")
            }
            override fun close() { closed.set(true); released.countDown() }
        }
        val session = CdnSession("auto", fast, { 10_000 }, { probe })
        val route = session.register(urls, 5_000_000, "VIDEO", OkHttpClient())!!
        route.begin(route.preferred(), 0, 4_000_000)
        session.update(true, 5000, 0, 1f)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        session.close()
        assertTrue(closed.get())
        assertEquals(original, route.preferred().host)
    }

}
