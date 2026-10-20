package blbl.cat3399.core.cdn

import org.junit.Assert.*
import org.junit.Test

class CdnRoutingTest {
    private val aliov = "upos-sz-mirroraliov.bilivideo.com"
    private val ali = "upos-sz-mirrorali.bilivideo.com"
    private val akamai = "upos-hz-mirrorakam.akamaized.net"
    private fun url(host: String, query: String = "deadline=123&sign=a%2Fb%2Bz&uipk=secret") = "https://$host/upgcxcode/01/02/123/123-1-30080.m4s?$query"

    @Test fun keepsIssuedHostSignaturesAndEscapedQuery() {
        val issuedAli = url(ali, "sign=own-ali-signature&deadline=999")
        val candidates = CdnRouting.candidates(listOf(url(aliov), issuedAli, url(akamai, "token=akamai-only")))
        assertEquals(issuedAli, candidates.first { it.host == ali }.url)
        assertEquals(url(akamai, "token=akamai-only"), candidates.first { it.host == akamai }.url)
        val hw = candidates.first { it.host == "upos-sz-mirrorhw.bilivideo.com" }
        assertEquals(url(hw.host), hw.url)
        assertFalse(hw.issued)
    }
    @Test fun neverSwapsAkamaiOnlySignatureToUpos() {
        val candidates = CdnRouting.candidates(listOf(url(akamai)))
        assertEquals(listOf(akamai), candidates.map { it.host })
    }
    @Test fun ignoresDifferentRepresentationsAndNonMediaUrls() {
        assertTrue(CdnRouting.candidates(listOf("https://api.bilibili.com/x/player/playurl")).isEmpty())
        assertTrue(CdnRouting.candidates(listOf("https://www.bilibili.com/a.ass")).isEmpty())
        val candidates = CdnRouting.candidates(listOf(url(aliov), url(ali).replace("30080.m4s", "30280.m4s")))
        assertFalse(candidates.first { it.host == ali }.issued)
        assertEquals(1, candidates.count { it.issued })
    }
    @Test fun refusesHostSwapFromNonDefaultPortOrUnrelatedHost() {
        assertEquals(1, CdnRouting.candidates(listOf(url(aliov).replace(".com/", ".com:8443/"))).size)
        assertEquals(1, CdnRouting.candidates(listOf(url("example.com"))).size)
    }
    @Test fun favorsIssuedBackupButNotAStaticVendor() {
        val candidates = CdnRouting.candidates(listOf(url(aliov), url(ali)))
        val choices = CdnRouting.challengers(candidates, aliov, emptyMap(), emptySet(), 0.5)
        assertEquals(ali, choices.first().host)
        val hw = "upos-sz-mirrorhw.bilivideo.com"
        assertEquals(hw, CdnRouting.challengers(candidates, aliov, mapOf(ali to 2.9e6, hw to 26.5e6), emptySet(), 0.5).first().host)
    }
    @Test fun restsFailuresAndExploresOtherHosts() {
        val candidates = CdnRouting.candidates(listOf(url(aliov)))
        val normal = CdnRouting.challengers(candidates, aliov, emptyMap(), setOf(ali), 0.5)
        assertFalse(normal.any { it.host == ali || it.host == aliov })
        val explored = CdnRouting.challengers(candidates, aliov, emptyMap(), setOf(ali), 0.1)
        assertNotEquals(normal, explored)
        assertEquals(2, explored.size)
    }
    @Test fun requiresOnePointFiveGainAndMatchingBytes() {
        val current = CdnProbeResult(aliov, CdnRouting.RACE_BYTES, 2170, "same") // ~2.9 Mbps
        val fast = CdnProbeResult(ali, CdnRouting.RACE_BYTES, 237, "same") // ~26.5 Mbps
        assertEquals(ali, CdnRouting.winner(listOf(current, fast), aliov, 2.9e6)?.host)
        assertNull(CdnRouting.winner(listOf(current, fast.copy(elapsedMs = 2000)), aliov, 2.9e6))
        assertNull(CdnRouting.winner(listOf(current, fast.copy(digest = "other-file")), aliov, 0.0))
        assertNull(CdnRouting.winner(listOf(current, fast.copy(error = "HTTP 403")), aliov, 0.0))
    }
    @Test fun goodSustainedHostIsNotReplacedByOneBadConnection() {
        val current = CdnProbeResult(aliov, CdnRouting.RACE_BYTES, 2000, "same")
        val alternative = CdnProbeResult(ali, CdnRouting.RACE_BYTES, 500, "same")
        assertNull(CdnRouting.winner(listOf(current, alternative), aliov, 30e6))
    }
    @Test fun cachedCurrentProbeDoesNotHideSustainedShortfall() {
        val current = CdnProbeResult(aliov, CdnRouting.RACE_BYTES, 50, "same")
        val alternative = CdnProbeResult(ali, CdnRouting.RACE_BYTES, 237, "same")
        assertEquals(ali, CdnRouting.winner(listOf(current, alternative), aliov, 2.9e6)?.host)
    }
    @Test fun cooldownGrowsAndCaps() {
        assertEquals(10_000L, CdnRouting.cooldown(true, 1, 0))
        assertEquals(80_000L, CdnRouting.cooldown(true, 4, 0))
        assertEquals(15_000L, CdnRouting.cooldown(false, 0, 0))
        assertEquals(120_000L, CdnRouting.cooldown(false, 0, 120_000))
        assertEquals("auto", CdnRouting.mode("corrupt"))
        assertEquals("off", CdnRouting.mode("off"))
    }
    @Test fun estimatorRejectsTinyInitSamplesAndIgnoresIdleTime() {
        val e = CdnGoodput()
        e.sample(100, 1024)
        assertNull(e.estimate())
        e.sample(1000, 125_000)
        assertNull(e.estimate())
        e.sample(1000, 125_000)
        assertEquals(1e6, e.estimate()!!, 1.0)
        assertEquals(2000L, e.elapsedMs)
        e.sample(1000, 500_000)
        assertTrue(e.estimate()!! > 1e6)
        assertTrue(e.estimate()!! < 4e6)
    }
    private fun healthy() = CdnHealth(true, 0, 20_000, 5e6, 20e6, 8_000_000, 10_000, 1000, 500_000, 100_000, 0)
    @Test fun healthyPlaybackPauseAndFullBufferDoNotRace() {
        assertNull(CdnPolicy.trigger(healthy()))
        assertNull(CdnPolicy.trigger(healthy().copy(playing = false, bufferingMs = 10000, recentErrors = 4)))
        assertNull(CdnPolicy.trigger(healthy().copy(bufferMs = 60000, rateBps = 1e6)))
    }
    @Test fun detectsSustainedShortfallAndUrgentStuckRequests() {
        assertEquals("shortfall", CdnPolicy.trigger(healthy().copy(rateBps = 1e6)))
        assertEquals("buffering", CdnPolicy.trigger(healthy().copy(bufferingMs = 4000, bufferMs = 0)))
        assertEquals("no-first-byte", CdnPolicy.trigger(healthy().copy(bufferMs = 1000, loaded = 0)))
        assertEquals("slow-fragment", CdnPolicy.trigger(healthy().copy(rateBps = 1e6, bufferMs = 3000, remaining = 1_000_000)))
        assertEquals("errors", CdnPolicy.trigger(healthy().copy(recentErrors = 2)))
        assertNull(CdnPolicy.trigger(healthy().copy(requiredBps = 0.0, rateBps = 1e6)))
    }
}
