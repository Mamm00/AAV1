package com.arena.core.data.model

/** Model modality groups used by the ModelPickerBottomSheet sections. */
enum class Modality { TEXT, IMAGE, CODE, SEARCH, VIDEO }

/** UI-facing model descriptor (mapped from [com.arena.core.data.db.ModelEntity]). */
data class ArenaModel(
    val id: String,
    val displayName: String,
    val provider: String?,
    val modality: Modality,
    val arenaRank: Int?,
    val votes: Int?,
    val contextWindow: Int?,
    val description: String?,
) {
    /** e.g. "#12" badge on the picker row. */
    val rankLabel: String?
        get() = arenaRank?.let { "#$it" }
}
