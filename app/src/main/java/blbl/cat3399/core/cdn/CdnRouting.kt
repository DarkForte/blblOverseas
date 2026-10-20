package blbl.cat3399.core.cdn

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Routing rules adapted from realzza/bilibili-accelerator (MIT); see THIRD_PARTY_NOTICES.md. */
object CdnRouting {
    const val RACE_BYTES = 768 * 1024
    const val RACE_TIMEOUT_MS = 4_000L
    const val MAX_SWITCHES = 4
    const val MAX_RACES = 12
    val mirrors = listOf(
        "upos-sz-mirrorcosov.bilivideo.com", "upos-sz-mirroraliov.bilivideo.com",
        "upos-sz-mirrorhwov.bilivideo.com", "upos-sz-mirrorali.bilivideo.com",
        "upos-tf-all-hw.bilivideo.com", "upos-sz-mirrorhw.bilivideo.com",
        "upos-sz-mirrorcos.bilivideo.com", "upos-tf-all-tx.bilivideo.com",
    )

    fun isUpos(host: String): Boolean = Regex("^upos-[a-z0-9-]+\\.bilivideo\\.com$").matches(host)
    fun fileKey(url: String): String? = url.toHttpUrlOrNull()?.encodedPath?.substringAfterLast('/')
        ?.takeIf { Regex(".+\\.(m4s|mp4|flv)", RegexOption.IGNORE_CASE).matches(it) }

    /** An issued URL always wins over a synthesized URL: never transplant an Akamai signature. */
    fun candidates(issuedUrls: List<String>): List<CdnCandidate> {
        val original = issuedUrls.firstOrNull()?.toHttpUrlOrNull() ?: return emptyList()
        val key = fileKey(original.toString()) ?: return emptyList()
        val issued = issuedUrls.mapNotNull { raw ->
            val u = raw.toHttpUrlOrNull() ?: return@mapNotNull null
            if (fileKey(raw) != key || u.username.isNotEmpty() || u.password.isNotEmpty()) return@mapNotNull null
            CdnCandidate(u.host, raw, true)
        }.distinctBy { it.host }
        val source = issued.firstOrNull {
            val u = it.url.toHttpUrlOrNull()!!
            isUpos(it.host) && !it.host.contains("302") && !it.host.contains("mirror14b") && u.port == (if (u.isHttps) 443 else 80)
        }?.url?.toHttpUrlOrNull()
        val generated = if (source == null) emptyList() else mirrors.filter { host -> issued.none { it.host == host } }
            .map { CdnCandidate(it, source.newBuilder().scheme("https").host(it).port(443).build().toString(), false) }
        return issued + generated
    }

    fun mode(value: String?): String = value?.takeIf { it in listOf("off", "auto", "manual") } ?: "auto"
    fun modeLabel(value: String): String = when (mode(value)) { "off" -> "关闭"; "manual" -> "手动"; else -> "自动（卡顿时测速）" }

    fun cooldown(switched: Boolean, switches: Int, previousMs: Long): Long =
        if (switched) min(120_000L, 10_000L * (1L shl (switches - 1).coerceIn(0, 4)))
        else if (previousMs == 0L) 15_000L else min(120_000L, previousMs * 2)

    /** Two challengers: unmeasured issued alternative first, then known speed, mainland tie-break, exploration. */
    fun challengers(
        candidates: List<CdnCandidate>, current: String, measured: Map<String, Double>,
        unavailable: Set<String>, random: Double = kotlin.random.Random.nextDouble(),
    ): List<CdnCandidate> {
        val pool = candidates.filter { it.host != current && it.host !in unavailable }
        val known = pool.mapNotNull { measured[it.host] }.sorted()
        val typical = known.getOrNull((known.size - 1).coerceAtLeast(0) / 2) ?: 10_000_000.0
        val ordered = pool.sortedWith(compareByDescending<CdnCandidate> { measured[it.host] ?: typical }
            .thenBy { it.host.contains("ov.") || it.host.endsWith(".akamaized.net") })
        val picks = (pool.filter { it.issued && it.host !in measured }.take(1) + ordered).distinctBy { it.host }.take(2).toMutableList()
        if (picks.size == 2 && random < 0.2) {
            val rest = pool.filter { p -> picks.none { it.host == p.host } }
            if (rest.isNotEmpty()) picks[1] = rest[((random / 0.2) * rest.size).toInt().coerceAtMost(rest.lastIndex)]
        }
        return picks
    }

    fun winner(results: List<CdnProbeResult>, current: String, currentBps: Double): CdnProbeResult? {
        val baseline = results.firstOrNull { it.host == current && it.ok }
        // Actual player goodput takes precedence over a potentially warm/cached probe.
        val rate = if (currentBps > 0) currentBps else baseline?.bps ?: 0.0
        return results.filter { it.ok && it.host != current && (baseline == null || it.digest == baseline.digest) }
            .maxByOrNull { it.bps }?.takeIf { rate <= 0 || it.bps >= rate * 1.5 }
    }
}

data class CdnCandidate(val host: String, val url: String, val issued: Boolean)
data class CdnProbeResult(val host: String, val bytes: Int, val elapsedMs: Long, val digest: String? = null, val error: String? = null) {
    val ok: Boolean get() = error == null && bytes >= 16_000 && elapsedMs > 0
    val bps: Double get() = if (ok) bytes * 8000.0 / elapsedMs else 0.0
}

/** Shaka-style dual EWMA, request latency included; idle/full-buffer time is never a sample. */
class CdnGoodput {
    private var fast = 0.0
    private var slow = 0.0
    var bytes = 0L; private set
    var elapsedMs = 0L; private set
    fun sample(ms: Long, count: Long) {
        if (ms <= 0 || count < 16_000) return
        val bps = count * 8000.0 / ms
        fast = update(fast, bps, ms, 2.0)
        slow = update(slow, bps, ms, 5.0)
        elapsedMs += ms
        bytes += count
    }
    private fun update(value: Double, sample: Double, ms: Long, halfLife: Double): Double {
        val alpha = exp(ln(0.5) * ms / 1000.0 / halfLife)
        return sample * (1 - alpha) + value * alpha
    }
    fun estimate(): Double? {
        if (bytes < 128_000) return null
        val seconds = elapsedMs / 1000.0
        return min(fast / (1 - exp(ln(0.5) * seconds / 2)), slow / (1 - exp(ln(0.5) * seconds / 5)))
    }
}

data class CdnHealth(
    val playing: Boolean, val bufferingMs: Long, val bufferMs: Long, val requiredBps: Double,
    val rateBps: Double, val measuredBytes: Long, val measuredMs: Long,
    val activeMs: Long, val loaded: Long, val remaining: Long, val recentErrors: Int,
)

object CdnPolicy {
    fun trigger(h: CdnHealth): String? {
        if (!h.playing) return null
        if (h.recentErrors >= 2) return "errors"
        if (h.bufferingMs >= 4_000 && h.bufferMs < 3_000) return "buffering"
        if (h.activeMs >= 1_000 && h.loaded == 0L && h.bufferMs < 3_000) return "no-first-byte"
        if (h.requiredBps <= 0) return null
        if (h.remaining >= 128 * 1024 && h.activeMs >= 500 && h.loaded >= 128 * 1024 &&
            h.rateBps < h.requiredBps * 1.3 &&
            (if (h.rateBps > 0) h.remaining * 8000.0 / h.rateBps else Double.POSITIVE_INFINITY) > maxOf(1_000.0, h.bufferMs - 2_000.0)
        ) return "slow-fragment"
        if ((h.measuredBytes >= 4 * 1024 * 1024 || h.measuredMs >= 8_000) &&
            h.rateBps > 0 && h.rateBps < h.requiredBps * 1.2 && h.bufferMs < 30_000
        ) return "shortfall"
        return null
    }
}
