package com.arena.core.data.repo

/**
 * On-device "Recommended for you" engine.
 *
 * Spec: *track which models the user selects per task type after 5
 * interactions and surface a Recommended for you row at the top of the model
 * switcher. Keep this local and private.*
 *
 * Pure Kotlin — no Android dependencies — so the behaviour is unit-testable
 * and provably offline (nothing is uploaded anywhere).
 */
object ModelRecommender {

    const val MIN_INTERACTIONS_PER_TASK = 5

    data class Interaction(
        val modelId: String,
        val taskType: String? = null,
    )

    /**
     * Models to recommend for [taskType] (e.g. "code", "image", "chat").
     *
     * Returns an empty list until the user has made at least
     * [minInteractions] selections for that task type, then the most-used
     * models first. A `null` [taskType] aggregates across all tasks.
     */
    fun recommend(
        interactions: List<Interaction>,
        taskType: String? = null,
        minInteractions: Int = MIN_INTERACTIONS_PER_TASK,
        limit: Int = 5,
    ): List<String> {
        val scoped = if (taskType == null) {
            interactions
        } else {
            interactions.filter { it.taskType == taskType }
        }
        if (scoped.size < minInteractions) return emptyList()

        return scoped.groupingBy { it.modelId }
            .eachCount()
            .toList()
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(limit)
            .map { it.first }
    }

    /**
     * Frequency-ordered modes for the adaptive home screen. Usage counts are
     * already aggregated by UsageStatsRepository.observeModeFrequency().
     */
    fun adaptiveOrder(
        modeFrequency: Map<String, Int>,
        defaultOrder: List<String>,
        enabled: Boolean = true,
    ): List<String> {
        if (!enabled || modeFrequency.isEmpty()) return defaultOrder
        return modeFrequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
            .plus(defaultOrder.filter { it !in modeFrequency })
    }
}
