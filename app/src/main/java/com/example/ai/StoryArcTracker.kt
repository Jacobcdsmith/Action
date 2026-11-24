package com.example.ai

import com.example.schemas.BuffType
import com.example.schemas.QuestObjectiveType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveQuest(
    val id: String,
    val title: String,
    val giverName: String,
    val objectiveType: QuestObjectiveType,
    val targetCount: Int,
    val currentCount: Int = 0,
    val rewardScore: Int = 120,
    val rewardBuff: BuffType = BuffType.SPEED_SURGE,
    val completed: Boolean = false
)

data class PlayerBuff(
    val type: BuffType,
    val label: String,
    val remainingSec: Float,
    val isPermanent: Boolean = false
)

data class StoryArcSnapshot(
    val chapterNumber: Int = 1,
    val worldThemeTitle: String = "",
    val companionName: String? = null,
    val activeQuests: List<ActiveQuest> = emptyList(),
    val completedQuestsCount: Int = 0,
    val activeBuffs: List<PlayerBuff> = emptyList(),
    val narrativeBeats: List<String> = emptyList()
)

/**
 * Maintains multi-chunk narrative memory, active quests, player buffs, and story beats
 * shared between the PlatformerEngine, NpcDialogueManager, and ChunkLookaheadBuffer.
 */
class StoryArcTracker {

    private val _snapshot = MutableStateFlow(StoryArcSnapshot())
    val snapshot: StateFlow<StoryArcSnapshot> = _snapshot.asStateFlow()

    fun reset(seedPrompt: String) {
        _snapshot.value = StoryArcSnapshot(
            chapterNumber = 1,
            worldThemeTitle = seedPrompt.trim().take(64),
            narrativeBeats = listOf("Entered the world: ${seedPrompt.trim().take(80)}")
        )
    }

    fun onChunkEntered(chunkIndex: Int, biomeName: String, storyText: String) {
        val nextChapter = (chunkIndex / 2) + 1
        _snapshot.update { curr ->
            val beat = "Ch.$nextChapter ($biomeName): ${storyText.take(90)}"
            val updatedBeats = if (curr.narrativeBeats.lastOrNull() == beat) {
                curr.narrativeBeats
            } else {
                (curr.narrativeBeats + beat).takeLast(12)
            }
            curr.copy(
                chapterNumber = nextChapter,
                narrativeBeats = updatedBeats
            )
        }
    }

    fun addNarrativeBeat(beat: String) {
        val trimmed = beat.trim()
        if (trimmed.isEmpty()) return
        _snapshot.update { curr ->
            curr.copy(narrativeBeats = (curr.narrativeBeats + trimmed).takeLast(12))
        }
    }

    fun setCompanion(name: String) {
        _snapshot.update { curr ->
            curr.copy(
                companionName = name,
                narrativeBeats = (curr.narrativeBeats + "$name joined as your combat companion.").takeLast(12)
            )
        }
    }

    fun addQuest(quest: ActiveQuest) {
        _snapshot.update { curr ->
            if (curr.activeQuests.any { it.title == quest.title && !it.completed }) {
                curr
            } else {
                curr.copy(
                    activeQuests = (curr.activeQuests + quest).takeLast(4),
                    narrativeBeats = (curr.narrativeBeats + "Quest accepted from ${quest.giverName}: ${quest.title}").takeLast(12)
                )
            }
        }
    }

    fun recordQuestProgress(
        type: QuestObjectiveType,
        amount: Int = 1,
        absoluteValue: Int? = null
    ): List<ActiveQuest> {
        val newlyCompleted = mutableListOf<ActiveQuest>()
        _snapshot.update { curr ->
            var completedDelta = 0
            val updated = curr.activeQuests.map { quest ->
                if (quest.completed || quest.objectiveType != type) {
                    quest
                } else {
                    val nextCount = if (absoluteValue != null) {
                        maxOf(quest.currentCount, absoluteValue)
                    } else {
                        quest.currentCount + amount
                    }.coerceAtMost(quest.targetCount)

                    if (nextCount >= quest.targetCount) {
                        val done = quest.copy(currentCount = quest.targetCount, completed = true)
                        newlyCompleted += done
                        completedDelta++
                        done
                    } else {
                        quest.copy(currentCount = nextCount)
                    }
                }
            }
            val newBeats = if (newlyCompleted.isNotEmpty()) {
                (curr.narrativeBeats + newlyCompleted.map { "Quest completed: ${it.title}!" }).takeLast(12)
            } else {
                curr.narrativeBeats
            }
            curr.copy(
                activeQuests = updated,
                completedQuestsCount = curr.completedQuestsCount + completedDelta,
                narrativeBeats = newBeats
            )
        }
        return newlyCompleted
    }

    fun addOrRefreshBuff(type: BuffType, durationSec: Float) {
        val label = when (type) {
            BuffType.JUMP_BOOST -> "Sky Boots"
            BuffType.SPEED_SURGE -> "Overdrive"
            BuffType.SHIELD -> "Aegis Shield"
            BuffType.MAGNET -> "Shard Magnet"
            BuffType.LOW_GRAVITY -> "Moon Tether"
            BuffType.DOUBLE_SCORE -> "2x Resonance"
        }
        val isPerm = durationSec <= 0f
        _snapshot.update { curr ->
            val filtered = curr.activeBuffs.filterNot { it.type == type }
            curr.copy(
                activeBuffs = filtered + PlayerBuff(
                    type = type,
                    label = label,
                    remainingSec = if (isPerm) 999f else durationSec,
                    isPermanent = isPerm
                )
            )
        }
    }

    fun consumeBuff(type: BuffType) {
        _snapshot.update { curr ->
            curr.copy(activeBuffs = curr.activeBuffs.filterNot { it.type == type })
        }
    }

    fun tickBuffs(dtSec: Float) {
        val current = _snapshot.value.activeBuffs
        if (current.none { !it.isPermanent }) return
        _snapshot.update { curr ->
            val next = curr.activeBuffs.mapNotNull { buff ->
                if (buff.isPermanent) {
                    buff
                } else {
                    val left = buff.remainingSec - dtSec
                    if (left <= 0f) null else buff.copy(remainingSec = left)
                }
            }
            curr.copy(activeBuffs = next)
        }
    }

    fun hasBuff(type: BuffType): Boolean = _snapshot.value.activeBuffs.any { it.type == type }

    fun buildPromptSummary(): String {
        val snap = _snapshot.value
        val activeQ = snap.activeQuests.filter { !it.completed }
            .joinToString("; ") { "${it.title} (${it.currentCount}/${it.targetCount})" }
            .ifBlank { "None" }
        val buffs = snap.activeBuffs.joinToString(", ") { it.label }.ifBlank { "None" }
        val recentBeats = snap.narrativeBeats.takeLast(4).joinToString(" -> ")
        return """
            Story Chapter: ${snap.chapterNumber}
            Companion: ${snap.companionName ?: "None"}
            Active Quests: $activeQ (Completed total: ${snap.completedQuestsCount})
            Active Buffs: $buffs
            Recent Narrative Memory: $recentBeats
        """.trimIndent()
    }
}
