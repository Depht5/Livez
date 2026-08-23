package dev.livefork.core.model

import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque

/**
 * 重复弹幕摘要。
 * 对应 Dart `LiveRepeatedDanmuSummary`（lib/src/model/live_repeated_danmu_summary.dart）。
 */
data class LiveRepeatedDanmuSummary(
    val text: String,
    val count: Int,
    val firstSeenAt: Instant? = null,
    val lastSeenAt: Instant? = null,
) {
    val displayText: String get() = "$text x$count"
}

/**
 * 聚合统计窗口内重复出现的弹幕。
 * 对应 Dart `LiveRepeatedDanmuAggregator`。
 */
class LiveRepeatedDanmuAggregator(
    val minDisplayCount: Int = 5,
    val maxDisplayItems: Int = 3,
    val countWindow: Duration = Duration.ofSeconds(30),
) {
    init {
        require(minDisplayCount > 0)
        require(maxDisplayItems > 0)
        require(!countWindow.isNegative)
    }

    private val counters = LinkedHashMap<String, RepeatedDanmuCounter>()
    private var sequence = 0

    fun add(text: String, now: Instant = Instant.now()): LiveRepeatedDanmuSummary? {
        val value = normalizeText(text)
        if (value.isEmpty()) {
            return null
        }
        pruneExpired(now)
        val counter = counters[value]
        return if (counter == null) {
            val nextCounter = RepeatedDanmuCounter(text = value, sequence = sequence++)
            nextCounter.add(now)
            counters[value] = nextCounter
            summaryFor(nextCounter)
        } else {
            counter.add(now)
            summaryFor(counter)
        }
    }

    fun drain(now: Instant = Instant.now(), displayTtl: Duration? = null): List<LiveRepeatedDanmuSummary> {
        val result = preview(now = now, displayTtl = displayTtl)
        clear()
        return result
    }

    fun preview(now: Instant = Instant.now(), displayTtl: Duration? = null): List<LiveRepeatedDanmuSummary> {
        pruneExpired(now)
        return buildSummaries(now, displayTtl)
    }

    fun clear() {
        counters.clear()
        sequence = 0
    }

    private fun buildSummaries(now: Instant, displayTtl: Duration?): List<LiveRepeatedDanmuSummary> {
        val list = counters.values
            .filter { item ->
                if (item.count < minDisplayCount) return@filter false
                val lastSeen = item.lastSeenAt ?: return@filter false
                displayTtl == null || Duration.between(lastSeen, now) <= displayTtl
            }
            .sortedWith(compareByDescending<RepeatedDanmuCounter> { it.count }
                .thenByDescending { it.lastSeenAt ?: Instant.EPOCH }
                .thenBy { it.sequence })
        return list.take(maxDisplayItems).map { summaryFor(it) }
    }

    private fun normalizeText(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private fun summaryFor(item: RepeatedDanmuCounter): LiveRepeatedDanmuSummary =
        LiveRepeatedDanmuSummary(
            text = item.text,
            count = item.count,
            firstSeenAt = item.firstSeenAt,
            lastSeenAt = item.lastSeenAt,
        )

    private fun pruneExpired(now: Instant) {
        val removeKeys = mutableListOf<String>()
        for ((key, item) in counters) {
            item.prune(now, countWindow)
            if (item.count <= 0) {
                removeKeys.add(key)
            }
        }
        for (key in removeKeys) {
            counters.remove(key)
        }
    }

    private class RepeatedDanmuCounter(
        val text: String,
        val sequence: Int,
    ) {
        private val hitTimes = ArrayDeque<Instant>()

        val count: Int get() = hitTimes.size
        val firstSeenAt: Instant? get() = hitTimes.peekFirst()
        val lastSeenAt: Instant? get() = hitTimes.peekLast()

        fun add(time: Instant) {
            hitTimes.addLast(time)
        }

        fun prune(now: Instant, countWindow: Duration) {
            while (hitTimes.isNotEmpty() &&
                Duration.between(hitTimes.peekFirst(), now) > countWindow
            ) {
                hitTimes.removeFirst()
            }
        }
    }
}
