package blbl.cat3399.core.cdn

import okhttp3.OkHttpClient
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A source-scoped session. Route and measurement state never outlive a PlayURL/episode. */
class CdnSession(
    val mode: String,
    private val manualHost: String,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val probeFactory: (OkHttpClient) -> CdnProber = { CdnRangeProbe(it) },
    private val onEvent: (String) -> Unit = {},
) : AutoCloseable {
    private val routes = ArrayList<CdnRoute>()
    private val events = ArrayDeque<String>()
    private val coordinator = Executors.newSingleThreadExecutor { Thread(it, "cdn-race").apply { isDaemon = true } }
    private val workers = Executors.newFixedThreadPool(3) { Thread(it, "cdn-probe").apply { isDaemon = true } }
    private var races = 0
    private var switches = 0
    private var cooldownMs = 0L
    private var nextRaceAt = 0L
    @Volatile private var racing = false
    @Volatile private var closed = false

    @Synchronized fun register(urls: List<String>, bandwidth: Long, label: String, client: OkHttpClient): CdnRoute? {
        val candidates = CdnRouting.candidates(urls)
        if (candidates.isEmpty()) return null
        return CdnRoute(candidates, bandwidth, label, probeFactory(client), now, ::record).also { routes += it }
    }

    @Synchronized private fun record(event: String) {
        if (closed) return
        if (events.size == 10) events.removeFirst()
        events.addLast("${now() / 1000}s $event")
        onEvent(event)
    }

    /** Called from the player application thread; no I/O here. One race globally, not per representation. */
    @Synchronized fun update(playing: Boolean, bufferingMs: Long, bufferMs: Long, speed: Float) {
        if (closed || !playing || racing || mode == "off" || races >= CdnRouting.MAX_RACES || switches >= CdnRouting.MAX_SWITCHES || now() < nextRaceAt) return
        val route = routes.filter { it.active() && it.probeRange().second >= 16_000 }.firstOrNull { r ->
            if (mode == "manual") r.needsManual(manualHost) else r.trigger(playing, bufferingMs, bufferMs, speed) != null
        } ?: return
        val reason = if (mode == "manual") "manual" else route.trigger(playing, bufferingMs, bufferMs, speed) ?: return
        val current = route.preferred()
        val challengers = if (mode == "manual") route.candidates.filter { it.host == manualHost && it.host != current.host }
            else CdnRouting.challengers(route.candidates, current.host, route.measured(), route.unavailable())
        if (challengers.isEmpty()) {
            if (mode == "manual") record("${route.label} 手动节点无兼容签名，保留 ${current.host}")
            route.manualDone = true
            return
        }
        val (offset, bytes) = route.probeRange()
        if (bytes < 16_000) return // init/index and tiny tails are not throughput samples
        racing = true
        races++
        record("${route.label} race#$races $reason")
        coordinator.execute {
            try {
                val picks = listOf(current) + challengers
                val futures = picks.map { c -> workers.submit<CdnProbeResult> { route.probe.measure(c, offset, bytes) } }
                val results = futures.mapIndexed { i, f ->
                    try { f.get(5, TimeUnit.SECONDS) } catch (_: InterruptedException) {
                        futures.forEach { it.cancel(true) }
                        Thread.currentThread().interrupt()
                        return@execute
                    } catch (_: Exception) {
                        f.cancel(true)
                        CdnProbeResult(picks[i].host, 0, 0, error = "cancelled/timeout")
                    }
                }
                synchronized(this) {
                    if (closed) return@execute
                    results.forEach { route.learn(it) }
                    val baseline = results.firstOrNull { it.host == current.host && it.ok }
                    val selected = if (mode == "manual") results.firstOrNull {
                        it.host == manualHost && it.ok && (baseline == null || it.digest == baseline.digest)
                    } else CdnRouting.winner(results, current.host, route.referenceRate(reason))
                    record(results.joinToString(" ") {
                        val name = it.host.removePrefix("upos-sz-mirror").removeSuffix(".bilivideo.com")
                        "$name=${if (it.ok) "%.1fMbps".format(Locale.US, it.bps / 1e6) else it.error}"
                    })
                    val switched = selected != null && route.preferred().host == current.host
                    if (switched && selected != null) {
                        route.select(selected.host)
                        switches++
                        record("${route.label} ${current.host} → ${selected.host} ${"%.1f".format(Locale.US, selected.bps / 1e6)} Mbps ($reason)")
                    } else {
                        record("${route.label} 保持 ${current.host}：无可用且明显更快节点")
                    }
                    route.manualDone = true
                    cooldownMs = CdnRouting.cooldown(switched, switches, cooldownMs)
                    nextRaceAt = now() + cooldownMs
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                // A source replacement/release may close the worker pool during submission.
            } finally { racing = false }
        }
    }

    @Synchronized fun diagnostics(): String {
        val active = routes.filter { it.active() }
        val current = active.joinToString("\n") { it.describe() }
        return "CDN加速=${CdnRouting.modeLabel(mode)} race=$races/${CdnRouting.MAX_RACES} switch=$switches/${CdnRouting.MAX_SWITCHES}${if (racing) " 测速中" else ""}\n" +
            current + "\n" + events.toList().takeLast(3).joinToString("\n")
    }

    @Synchronized override fun close() {
        closed = true
        routes.forEach { it.probe.close() }
        coordinator.shutdownNow()
        workers.shutdownNow()
        routes.clear()
    }
}

class CdnRoute internal constructor(
    val candidates: List<CdnCandidate>, private val bandwidth: Long, val label: String,
    internal val probe: CdnProber, private val now: () -> Long, private val event: (String) -> Unit,
) {
    private var chosen = candidates.first()
    private val estimators = HashMap<String, CdnGoodput>()
    private val scores = HashMap<String, Double>()
    private val rests = HashMap<String, Long>()
    private val failedGenerated = HashSet<String>()
    private val errors = HashMap<String, ArrayDeque<Long>>()
    private var activeAt = -1L
    private var requestStart = 0L
    private var requestOffset = 0L
    private var requestTotal = -1L
    private var loaded = 0L
    private var requestHost = chosen.host
    private var reading = false
    private var readyAt = -1L
    @Volatile var manualDone = false

    @Synchronized fun preferred(): CdnCandidate = chosen
    @Synchronized fun openOrder(): List<CdnCandidate> = (listOf(chosen) + candidates.filter { it.issued }).distinctBy { it.url }
        .filter { it.host !in failedGenerated }
    @Synchronized fun select(host: String) { chosen = candidates.first { it.host == host } }
    @Synchronized fun needsManual(host: String): Boolean = !manualDone && chosen.host != host
    @Synchronized fun active(): Boolean = reading || (activeAt >= 0 && now() - activeAt < 10_000)
    @Synchronized fun measured(): Map<String, Double> = scores.toMap()
    @Synchronized fun unavailable(): Set<String> = failedGenerated + rests.filterValues { now() < it }.keys
    @Synchronized fun probeRange(): Pair<Long, Int> {
        val remaining = if (requestTotal < 0) CdnRouting.RACE_BYTES.toLong() else (requestTotal - loaded).coerceAtLeast(0)
        return (requestOffset + loaded) to minOf(remaining, CdnRouting.RACE_BYTES.toLong()).toInt()
    }
    @Synchronized fun begin(candidate: CdnCandidate, offset: Long, total: Long) {
        requestHost = candidate.host
        requestStart = now()
        requestOffset = offset
        requestTotal = total
        loaded = 0
        activeAt = now()
        reading = true
        readyAt = -1L
    }
    @Synchronized fun opened(total: Long) {
        if (total >= 0) requestTotal = total
        readyAt = now()
    }
    @Synchronized fun progress(bytes: Int) { loaded += bytes; activeAt = now() }
    @Synchronized fun end() {
        if (!reading) return
        estimators.getOrPut(requestHost) { CdnGoodput() }.sample(now() - requestStart, loaded)
        reading = false
    }
    // Call the event sink outside the route monitor: session locks always precede route locks.
    fun failed(candidate: CdnCandidate) {
        val fallback = synchronized(this) {
            errors.getOrPut(candidate.host) { ArrayDeque() }.addLast(now())
            rests[candidate.host] = now() + 60_000
            if (!candidate.issued) failedGenerated += candidate.host
            if (chosen.host == candidate.host) chosen = candidates.first()
            chosen.host
        }
        event("$label ${candidate.host} 失败，回退 $fallback（保留原始签名）")
    }
    @Synchronized fun accepted(candidate: CdnCandidate) { chosen = candidate }
    @Synchronized fun learn(result: CdnProbeResult) {
        if (result.ok) scores[result.host] = result.bps else rests[result.host] = now() + 60_000
        if (result.ok && result.host != chosen.host) rests[result.host] = now() + 60_000
    }
    @Synchronized fun referenceRate(reason: String): Double {
        // One isolated slow connection doesn't erase a host's sustained goodput.
        return if (recentErrors(chosen.host) >= 2 || reason == "buffering") 0.0 else estimators[chosen.host]?.estimate() ?: 0.0
    }
    @Synchronized private fun recentErrors(host: String): Int {
        val recent = errors[host] ?: return 0
        while (recent.isNotEmpty() && now() - recent.first > 30_000) recent.removeFirst()
        return recent.size
    }
    @Synchronized fun trigger(playing: Boolean, bufferingMs: Long, bufferMs: Long, speed: Float): String? {
        val estimator = estimators[requestHost]
        val ms = if (reading) now() - requestStart else 0L
        val rate = if (reading && loaded >= 128_000 && ms >= 500) loaded * 8000.0 / ms else estimator?.estimate() ?: 0.0
        return CdnPolicy.trigger(CdnHealth(playing, bufferingMs, bufferMs, bandwidth * speed.toDouble(), rate,
            (estimator?.bytes ?: 0) + if (reading) loaded else 0, (estimator?.elapsedMs ?: 0) + ms, ms, if (reading) loaded else -1,
            if (reading && requestTotal >= 0) requestTotal - loaded else -1, recentErrors(requestHost)))
    }
    @Synchronized fun describe(): String {
        val ms = now() - requestStart
        val rate = if (reading && loaded >= 16_000 && ms > 0) loaded * 8000.0 / ms else estimators[requestHost]?.estimate() ?: 0.0
        return "$label $requestHost ${"%.2f".format(Locale.US, rate / 1e6)} Mbps → ${chosen.host}"
    }
}
