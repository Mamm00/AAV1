package com.arena.core.data.repo

import com.arena.core.data.db.ConversationDao
import com.arena.core.data.db.ConversationEntity
import com.arena.core.data.db.LeaderboardDao
import com.arena.core.data.db.LeaderboardEntryEntity
import com.arena.core.data.db.MessageEntity
import com.arena.core.data.db.ModelDao
import com.arena.core.data.db.ModelEntity
import com.arena.core.data.db.UsageEventDao
import com.arena.core.data.db.UsageEventEntity
import com.arena.core.data.db.VoteDao
import com.arena.core.data.db.VoteEntity
import com.arena.core.data.model.ArenaModel
import com.arena.core.data.model.Modality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// --------------------------------------------------------------------------- //
// Model catalog
// --------------------------------------------------------------------------- //

@Singleton
class ModelRepository @Inject constructor(
    private val modelDao: ModelDao,
    private val okHttpClient: OkHttpClient,
) {
    fun observeAll(): Flow<List<ArenaModel>> = modelDao.observeAll().map { rows -> rows.map { it.toModel() } }

    fun search(query: String, modality: Modality?): Flow<List<ArenaModel>> =
        modelDao.search(query.trim(), modality?.name).map { rows -> rows.map { it.toModel() } }

    /** Refresh the full 300+ catalog from Arena; keeps seed data on failure. */
    suspend fun refresh(): Result<Int> = runCatching {
        val request = Request.Builder().url("$API_BASE/models").get().build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "models HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val models = parseModels(body)
            if (models.isNotEmpty()) modelDao.upsertAll(models)
            models.size
        }
    }

    suspend fun count(): Int = modelDao.count()

    private fun parseModels(body: String): List<ModelEntity> {
        val array = runCatching { JSONArray(body) }.getOrElse {
            runCatching { JSONObject(body).getJSONArray("models") }.getOrDefault(JSONArray())
        }
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id").ifBlank { o.optString("model") }.ifBlank { continue }
                val name = o.optString("name").ifBlank { o.optString("display_name") }.ifBlank { id }
                val modalityRaw = o.optString("modality").ifBlank { o.optString("type") }
                    .ifBlank { "TEXT" }.uppercase()
                val modality = runCatching { Modality.valueOf(modalityRaw) }.getOrDefault(Modality.TEXT)
                add(
                    ModelEntity(
                        id = id,
                        displayName = name,
                        provider = o.optString("provider").ifBlank { null },
                        modality = modality.name,
                        arenaRank = o.optInt("rank", -1).takeIf { it > 0 },
                        votes = o.optInt("votes", -1).takeIf { it >= 0 },
                        contextWindow = o.optInt("context_window", -1).takeIf { it > 0 },
                        description = o.optString("description").ifBlank { null },
                    ),
                )
            }
        }
    }

    companion object {
        const val API_BASE = "https://arena.ai/api"
    }
}

private fun ModelEntity.toModel(): ArenaModel = ArenaModel(
    id = id,
    displayName = displayName,
    provider = provider,
    modality = runCatching { Modality.valueOf(modality) }.getOrDefault(Modality.TEXT),
    arenaRank = arenaRank,
    votes = votes,
    contextWindow = contextWindow,
    description = description,
)

// --------------------------------------------------------------------------- //
// Votes (Battle Mode, offline-capable)
// --------------------------------------------------------------------------- //

@Singleton
class VoteRepository @Inject constructor(
    private val voteDao: VoteDao,
) {
    fun observeVotes(): Flow<List<VoteEntity>> = voteDao.observeVotes()

    fun observeChoiceCount(choice: String): Flow<Int> = voteDao.observeCountForChoice(choice)

    suspend fun submitVote(
        battleId: String,
        choice: String,
        modelAId: String?,
        modelBId: String?,
        generationId: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        voteDao.insert(
            VoteEntity(
                id = id,
                battleId = battleId,
                choice = choice,
                modelAId = modelAId,
                modelBId = modelBId,
                generationId = generationId,
                votedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun count(): Int = voteDao.count()
}

// --------------------------------------------------------------------------- //
// Leaderboards (cached for offline browsing)
// --------------------------------------------------------------------------- //

data class LeaderboardCategory(val key: String, val label: String) {
    companion object {
        val TEXT = LeaderboardCategory("TEXT", "Text")
        val CODE = LeaderboardCategory("CODE", "Code")
        val IMAGE = LeaderboardCategory("IMAGE", "Image")
        val ALL = listOf(TEXT, CODE, IMAGE)
    }
}

@Singleton
class LeaderboardRepository @Inject constructor(
    private val leaderboardDao: LeaderboardDao,
    private val okHttpClient: OkHttpClient,
) {
    fun observe(category: LeaderboardCategory): Flow<List<LeaderboardEntryEntity>> =
        leaderboardDao.observeCategory(category.key)

    /**
     * Pull the live leaderboard for [category]; no-op failure keeps the cached
     * rows so OfflineLeaderboardScreen always renders something.
     */
    suspend fun refresh(category: LeaderboardCategory): Result<Int> = runCatching {
        val request = Request.Builder()
            .url("${ModelRepository.API_BASE}/leaderboard?category=${category.key.lowercase()}")
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "leaderboard HTTP ${response.code}" }
            val body = response.body?.string().orEmpty()
            val entries = parseEntries(category, body)
            if (entries.isNotEmpty()) {
                leaderboardDao.clearCategory(category.key)
                leaderboardDao.upsertAll(entries)
            }
            entries.size
        }
    }

    suspend fun lastUpdated(category: LeaderboardCategory): Long? =
        leaderboardDao.lastUpdated(category.key)

    private fun parseEntries(category: LeaderboardCategory, body: String): List<LeaderboardEntryEntity> {
        val array = runCatching { JSONArray(body) }.getOrElse {
            runCatching { JSONObject(body).getJSONArray("leaderboard") }.getOrDefault(JSONArray())
        }
        val now = System.currentTimeMillis()
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val modelId = o.optString("model_id").ifBlank { o.optString("model") }.ifBlank { continue }
                val name = o.optString("name").ifBlank { o.optString("display_name") }.ifBlank { modelId }
                val rank = o.optInt("rank", i + 1)
                add(
                    LeaderboardEntryEntity(
                        key = "${category.key}:$modelId",
                        category = category.key,
                        modelId = modelId,
                        displayName = name,
                        provider = o.optString("provider").ifBlank { null },
                        rank = rank,
                        rating = o.optDouble("score", o.optDouble("elo", 0.0)),
                        votes = o.optInt("votes", 0),
                        updatedAtEpochMs = now,
                    ),
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- //
// Conversations (offline last-history fallback)
// --------------------------------------------------------------------------- //

data class OfflineSnapshot(
    val conversation: ConversationEntity,
    val messages: List<MessageEntity>,
)

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
) {
    fun observeLatest(): Flow<OfflineSnapshot?> =
        conversationDao.observeLatestConversation().let { convFlow ->
            kotlinx.coroutines.flow.combine(convFlow, conversationDao.observeLatestMessages()) { conv, msgs ->
                conv?.let { OfflineSnapshot(it, msgs) }
            }
        }

    suspend fun record(
        mode: String,
        conversationId: String = UUID.randomUUID().toString(),
        title: String,
        role: String,
        content: String,
        modelId: String?,
        slot: Int = 0,
    ): String {
        val now = System.currentTimeMillis()
        conversationDao.upsertConversation(
            ConversationEntity(id = conversationId, title = title, mode = mode, updatedAtEpochMs = now),
        )
        val messageId = UUID.randomUUID().toString()
        conversationDao.insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = role,
                content = content,
                modelId = modelId,
                slot = slot,
                createdAtEpochMs = now,
            ),
        )
        return messageId
    }

    suspend fun snapshotForOffline(): OfflineSnapshot? {
        // One-shot read used by the offline fallback surface.
        val conversation = conversationDao.observeLatestConversation().first() ?: return null
        return OfflineSnapshot(conversation, conversationDao.messagesSnapshot(conversation.id))
    }
}

// --------------------------------------------------------------------------- //
// Usage statistics (adaptive home + local recommendations)
// --------------------------------------------------------------------------- //

@Singleton
class UsageStatsRepository @Inject constructor(
    private val usageEventDao: UsageEventDao,
) {
    suspend fun record(mode: String, modelId: String?, taskType: String? = null) {
        usageEventDao.insert(
            UsageEventEntity(
                mode = mode,
                modelId = modelId,
                taskType = taskType,
                atEpochMs = System.currentTimeMillis(),
            ),
        )
    }

    fun observeModeFrequency(): Flow<Map<String, Int>> =
        usageEventDao.observeModeFrequency().map { rows -> rows.associate { it.mode to it.n } }

    fun observeModelFrequency(): Flow<Map<String, Int>> =
        usageEventDao.observeModelFrequency().map { rows -> rows.associate { it.modelId to it.n } }

    /** Recent selections for the local "Recommended for you" row. */
    fun observeInteractions(): Flow<List<ModelRecommender.Interaction>> =
        usageEventDao.observeRecent(500).map { rows ->
            rows.mapNotNull { row ->
                row.modelId?.let { ModelRecommender.Interaction(modelId = it, taskType = row.taskType) }
            }
        }

    suspend fun totalEvents(): Int = usageEventDao.count()
}
