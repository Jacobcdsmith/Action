package com.example.ai

import android.util.Log
import com.example.engine.RuntimeNpc
import com.example.schemas.BiomeConfig
import com.example.schemas.BuffType
import com.example.schemas.ConsequenceType
import com.example.schemas.NpcRole
import com.example.schemas.QuestObjectiveType
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

private const val TAG = "NpcDialogueManager"

data class DialogueMessage(
    val id: String,
    val isPlayer: Boolean,
    val text: String,
    val consequenceBadge: String? = null
)

data class DialogueChoice(
    val id: String,
    val label: String,
    val intentHint: String
)

data class DialogueSessionState(
    val activeNpc: RuntimeNpc? = null,
    val biomeName: String = "",
    val messages: List<DialogueMessage> = emptyList(),
    val suggestedChoices: List<DialogueChoice> = emptyList(),
    val isThinking: Boolean = false,
    val errorNotice: String? = null,
    val activeRouteLabel: String? = null,
    val lastConsequenceToast: String? = null
)

/**
 * Orchestrates full pause-and-converse branching NPC conversations routed via AiModelRouter
 * (BYOK providers + Firebase AI Logic Auto Router, with deterministic offline branching fallback).
 */
class NpcDialogueManager(
    private val storyArcTracker: StoryArcTracker,
    private val router: AiModelRouter
) {
    private val _session = MutableStateFlow(DialogueSessionState())
    val session: StateFlow<DialogueSessionState> = _session.asStateFlow()

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
    }

    private val responseAdapter by lazy {
        moshi.adapter(GeminiNpcDialogueResponseDto::class.java)
    }

    private val systemInstruction = """
        You roleplay as an NPC inside a generative 2D action-platformer.
        Stay strictly in character for the NPC's role, personality, and current biome.
        Respond with structured JSON containing:
        - replyText: 1-3 evocative sentences responding to the player.
        - dispositionDelta: integer in [-35, +35].
        - narrativeBeat: optional 1-sentence story memory note if something meaningful happened.
        - suggestedChoices: 2 to 3 short player reply options with clear intentHint tags.
        - consequence: ALWAYS set type to NONE unless the player clearly agreed to a deal, accepted a quest, recruited a companion, provoked/challenged a rival, or asked to reshape the next sector.
          - If MERCHANT_TRADE: set scoreCost (e.g. 30 or 50), buffType, buffDurationSec (30), or healAmount (1..2).
          - If GRANT_QUEST: set questTitle, questObjective (DEFEAT_ENEMIES, COLLECT_SHARDS, REACH_CHUNK, or DEFEAT_RIVAL), questTargetCount (2..5), questRewardScore (100..250).
          - If GRANT_BUFF: set buffType and buffDurationSec (25..40).
          - If RECRUIT_COMPANION: only for COMPANION (or persuaded RIVAL) joining the player.
          - If TRIGGER_RIVAL_DUEL: when a RIVAL is challenged or insulted enough to fight.
          - If MUTATE_NEXT_CHUNK: set chunkMutationPrompt (e.g. "Low-gravity crystal spire storm") to reshape Chunk N+1!
    """.trimIndent()

    private val byokJsonContract = """
        {
          "replyText": string,
          "dispositionDelta": int (-35..35),
          "narrativeBeat": string|null,
          "suggestedChoices": [ { "id": string, "label": string, "intentHint": string } ],
          "consequence": {
            "type": "NONE"|"GRANT_QUEST"|"GRANT_BUFF"|"MERCHANT_TRADE"|"RECRUIT_COMPANION"|"TRIGGER_RIVAL_DUEL"|"MUTATE_NEXT_CHUNK",
            "summary": string,
            "questTitle": string|null,
            "questObjective": "DEFEAT_ENEMIES"|"COLLECT_SHARDS"|"REACH_CHUNK"|"DEFEAT_RIVAL"|null,
            "questTargetCount": int|null,
            "questRewardScore": int|null,
            "buffType": "JUMP_BOOST"|"SPEED_SURGE"|"SHIELD"|"MAGNET"|"LOW_GRAVITY"|"DOUBLE_SCORE"|null,
            "buffDurationSec": float|null,
            "scoreCost": int|null,
            "healAmount": int|null,
            "chunkMutationPrompt": string|null
          }
        }
    """.trimIndent()

    fun openDialogue(npc: RuntimeNpc, biome: BiomeConfig) {
        npc.talkedTo = true
        val initialChoices = defaultChoicesForRole(npc)
        _session.value = DialogueSessionState(
            activeNpc = npc,
            biomeName = biome.name,
            messages = listOf(
                DialogueMessage(
                    id = "open_${npc.id}",
                    isPlayer = false,
                    text = npc.openingLine
                )
            ),
            suggestedChoices = initialChoices,
            isThinking = false,
            errorNotice = null,
            activeRouteLabel = null,
            lastConsequenceToast = null
        )
    }

    fun closeDialogue() {
        _session.value = DialogueSessionState()
    }

    suspend fun sendTurn(
        playerMessage: String,
        biome: BiomeConfig,
        playerScore: Int,
        playerHp: Int,
        maxHp: Int,
        onApplyConsequence: (GeminiDialogueConsequenceDto, RuntimeNpc) -> String?
    ) {
        val npc = _session.value.activeNpc ?: return
        val cleaned = playerMessage.trim()
        if (cleaned.isEmpty() || _session.value.isThinking) return

        val playerMsg = DialogueMessage(
            id = "p_${System.currentTimeMillis()}",
            isPlayer = true,
            text = cleaned
        )
        _session.update { curr ->
            curr.copy(
                messages = curr.messages + playerMsg,
                isThinking = true,
                errorNotice = null
            )
        }

        val outcome = requestNpcReply(
            npc = npc,
            biome = biome,
            playerScore = playerScore,
            playerHp = playerHp,
            maxHp = maxHp,
            transcript = _session.value.messages
        )

        npc.disposition = (npc.disposition + outcome.dto.dispositionDelta).coerceIn(-100, 100)
        outcome.dto.narrativeBeat?.let { storyArcTracker.addNarrativeBeat(it) }

        val badge = onApplyConsequence(outcome.dto.consequence, npc)
        val npcMsg = DialogueMessage(
            id = "n_${System.currentTimeMillis()}",
            isPlayer = false,
            text = outcome.dto.replyText,
            consequenceBadge = badge
        )

        _session.update { curr ->
            curr.copy(
                activeNpc = npc,
                messages = curr.messages + npcMsg,
                suggestedChoices = outcome.dto.suggestedChoices
                    .ifEmpty { defaultChoicesForRole(npc).map { it.toDto() } }
                    .map { DialogueChoice(it.id, it.label, it.intentHint) },
                isThinking = false,
                errorNotice = outcome.fallbackNotice,
                activeRouteLabel = outcome.routeLabel,
                lastConsequenceToast = badge ?: curr.lastConsequenceToast
            )
        }
    }

    private suspend fun requestNpcReply(
        npc: RuntimeNpc,
        biome: BiomeConfig,
        playerScore: Int,
        playerHp: Int,
        maxHp: Int,
        transcript: List<DialogueMessage>
    ): NpcReplyOutcome = withContext(Dispatchers.IO) {
        val historyText = transcript.takeLast(8).joinToString("\n") { msg ->
            if (msg.isPlayer) "Player: ${msg.text}" else "${npc.name}: ${msg.text}"
        }
        val prompt = """
            NPC: ${npc.name} (Role: ${npc.role}, Disposition: ${npc.disposition})
            Personality: ${npc.personality}
            Biome: ${biome.name} (${biome.loreWhisper})
            Player Status: Score=$playerScore, HP=$playerHp/$maxHp
            ${storyArcTracker.buildPromptSummary()}

            Recent Conversation:
            $historyText

            Generate the next in-character response and structured choices/consequences.
        """.trimIndent()

        try {
            when (
                val routed = router.generateStructured(
                    taskKind = AiTaskKind.NPC_DIALOGUE,
                    systemInstruction = systemInstruction,
                    userPrompt = prompt,
                    byokJsonContract = byokJsonContract
                )
            ) {
                is RoutedAiResult.Success -> {
                    val parsed = responseAdapter.fromJson(routed.text)
                        ?: throw IllegalStateException("Failed to deserialize dialogue JSON from ${routed.routeLabel}")
                    NpcReplyOutcome(dto = parsed, routeLabel = routed.routeLabel, fallbackNotice = null)
                }

                is RoutedAiResult.Failure -> {
                    NpcReplyOutcome(
                        dto = offlineFallbackReply(npc, transcript.lastOrNull()?.text.orEmpty(), playerScore),
                        routeLabel = null,
                        fallbackNotice = "Offline dialogue fallback (${routed.errorMessage})"
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "NPC dialogue failed", e)
            NpcReplyOutcome(
                dto = offlineFallbackReply(npc, transcript.lastOrNull()?.text.orEmpty(), playerScore),
                routeLabel = null,
                fallbackNotice = "Offline dialogue fallback (${e.localizedMessage ?: "error"})"
            )
        }
    }

    private fun defaultChoicesForRole(npc: RuntimeNpc): List<DialogueChoice> = when (npc.role) {
        NpcRole.GUIDE_MERCHANT -> listOf(
            DialogueChoice("quest", "Do you have a bounty for me?", "Accept Quest"),
            DialogueChoice("trade", "Trade 30 Score for Sky Boots (+Jump)", "Trade 30 Score"),
            DialogueChoice("lore", "What lies ahead in the next sector?", "Story & Hints")
        )
        NpcRole.LOREKEEPER -> listOf(
            DialogueChoice("mutate", "Rewrite the next sector into low-gravity ruins", "Mutate Chunk N+1"),
            DialogueChoice("blessing", "Share an ancient resonance blessing", "Grant Buff"),
            DialogueChoice("quest", "Give me a chronicle trial to complete", "Accept Quest")
        )
        NpcRole.COMPANION -> listOf(
            DialogueChoice("recruit", "Link tethers and fight beside me!", "Recruit Companion"),
            DialogueChoice("buff", "Lend me your shield pulse first", "Grant Shield"),
            DialogueChoice("scout", "What anomalies are nearby?", "Sector Intel")
        )
        NpcRole.RIVAL -> listOf(
            DialogueChoice("duel", "Enough talk—draw sparks and fight me!", "Start Rival Duel"),
            DialogueChoice("ally", "Truce? Share your speed technique instead.", "Negotiate Buff"),
            DialogueChoice("wager", "Name a trial to prove my speed.", "Rival Quest")
        )
    }

    private fun offlineFallbackReply(
        npc: RuntimeNpc,
        playerText: String,
        playerScore: Int
    ): GeminiNpcDialogueResponseDto {
        val lower = playerText.lowercase()
        return when {
            lower.contains("recruit") || lower.contains("fight beside") || lower.contains("tether") ->
                GeminiNpcDialogueResponseDto(
                    replyText = "Tether locked! My targeting servos are synced to your cloak—let's clear these ruins together.",
                    dispositionDelta = 25,
                    narrativeBeat = "${npc.name} joined the player as an active combat companion.",
                    suggestedChoices = listOf(
                        DialogueChoice("move", "Let's move out!", "Close & Fight").toDto()
                    ),
                    consequence = GeminiDialogueConsequenceDto(
                        type = ConsequenceType.RECRUIT_COMPANION,
                        summary = "${npc.name} joined as Combat Companion!"
                    )
                )

            lower.contains("duel") || lower.contains("fight me") || lower.contains("sparks") ->
                GeminiNpcDialogueResponseDto(
                    replyText = "Ha! That's the fire I wanted to see. Show me if your reflexes match your pride!",
                    dispositionDelta = -30,
                    narrativeBeat = "Challenged ${npc.name} to a rival duel.",
                    suggestedChoices = listOf(
                        DialogueChoice("engage", "Bring it on!", "Begin Duel").toDto()
                    ),
                    consequence = GeminiDialogueConsequenceDto(
                        type = ConsequenceType.TRIGGER_RIVAL_DUEL,
                        summary = "${npc.name} turned hostile—defeat them for bonus score!"
                    )
                )

            lower.contains("trade") || lower.contains("buy") || lower.contains("30") -> {
                if (playerScore >= 30) {
                    GeminiNpcDialogueResponseDto(
                        replyText = "Pleasure doing business! Strapping Sky Boots onto your greaves—feel that lift.",
                        dispositionDelta = 15,
                        narrativeBeat = "Traded 30 score with ${npc.name} for Sky Boots.",
                        suggestedChoices = defaultChoicesForRole(npc).map { it.toDto() },
                        consequence = GeminiDialogueConsequenceDto(
                            type = ConsequenceType.MERCHANT_TRADE,
                            summary = "Spent 30 Score -> Gained Sky Boots (35s)!",
                            buffType = BuffType.JUMP_BOOST,
                            buffDurationSec = 35f,
                            scoreCost = 30
                        )
                    )
                } else {
                    GeminiNpcDialogueResponseDto(
                        replyText = "Your pouch is light—gather 30 Score from shards or foes first, then come back.",
                        dispositionDelta = 0,
                        suggestedChoices = defaultChoicesForRole(npc).map { it.toDto() },
                        consequence = GeminiDialogueConsequenceDto(
                            type = ConsequenceType.NONE,
                            summary = "Need 30 Score to trade"
                        )
                    )
                }
            }

            lower.contains("rewrite") || lower.contains("mutate") || lower.contains("next sector") ->
                GeminiNpcDialogueResponseDto(
                    replyText = "So be it. I etch your command into the horizon—the upcoming sector will bend to your will.",
                    dispositionDelta = 15,
                    narrativeBeat = "${npc.name} altered the fabric of the upcoming sector.",
                    suggestedChoices = defaultChoicesForRole(npc).map { it.toDto() },
                    consequence = GeminiDialogueConsequenceDto(
                        type = ConsequenceType.MUTATE_NEXT_CHUNK,
                        summary = "Mutated Chunk N+1: Low-gravity crystal spire ruins!",
                        chunkMutationPrompt = "Low-gravity crystal spire ruins filled with glowing shards"
                    )
                )

            lower.contains("quest") || lower.contains("bounty") || lower.contains("trial") ->
                GeminiNpcDialogueResponseDto(
                    replyText = "Clear out 3 anomalies ahead and I'll wire an Overdrive core straight into your rig.",
                    dispositionDelta = 15,
                    narrativeBeat = "Accepted bounty quest from ${npc.name}.",
                    suggestedChoices = defaultChoicesForRole(npc).map { it.toDto() },
                    consequence = GeminiDialogueConsequenceDto(
                        type = ConsequenceType.GRANT_QUEST,
                        summary = "New Quest: Purge 3 Anomalies (+150 Score & Overdrive)",
                        questTitle = "Purge Sector Anomalies",
                        questObjective = QuestObjectiveType.DEFEAT_ENEMIES,
                        questTargetCount = 3,
                        questRewardScore = 150,
                        buffType = BuffType.SPEED_SURGE
                    )
                )

            else ->
                GeminiNpcDialogueResponseDto(
                    replyText = "Keep your momentum sharp—take this Aegis Shield pulse to weather the next chasm.",
                    dispositionDelta = 10,
                    suggestedChoices = defaultChoicesForRole(npc).map { it.toDto() },
                    consequence = GeminiDialogueConsequenceDto(
                        type = ConsequenceType.GRANT_BUFF,
                        summary = "Granted Aegis Shield (blocks 1 hit)!",
                        buffType = BuffType.SHIELD,
                        buffDurationSec = 0f
                    )
                )
        }
    }

    private fun DialogueChoice.toDto() = GeminiDialogueChoiceDto(id, label, intentHint)

    private data class NpcReplyOutcome(
        val dto: GeminiNpcDialogueResponseDto,
        val routeLabel: String?,
        val fallbackNotice: String?
    )
}
