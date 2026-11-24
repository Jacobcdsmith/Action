package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.ActiveQuest
import com.example.ai.AiDirector
import com.example.ai.AiModelRouter
import com.example.ai.AiProviderStore
import com.example.ai.ChunkLookaheadBuffer
import com.example.ai.GeminiDialogueConsequenceDto
import com.example.ai.LookaheadState
import com.example.ai.NpcDialogueManager
import com.example.audio.AudioEngine
import com.example.engine.EngineEvent
import com.example.engine.HudSnapshot
import com.example.engine.InputSnapshot
import com.example.engine.PlatformerEngine
import com.example.engine.RuntimeNpc
import com.example.schemas.BuffType
import com.example.schemas.ConsequenceType
import com.example.schemas.QuestObjectiveType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { PlatformerEngine() }
    val audioEngine = remember { AudioEngine() }
    val coroutineScope = rememberCoroutineScope()
    val providerStore = remember(context) { AiProviderStore(context) }
    val modelRouter = remember(providerStore) { AiModelRouter(providerStore) }
    val aiDirector = remember(modelRouter) { AiDirector(modelRouter) }
    val lookaheadBuffer = remember(coroutineScope, aiDirector) { ChunkLookaheadBuffer(aiDirector, coroutineScope) }
    val dialogueManager = remember(modelRouter) { NpcDialogueManager(engine.storyArcTracker, modelRouter) }

    val bufferStatus by lookaheadBuffer.status.collectAsState()
    val storyArc by engine.storyArcTracker.snapshot.collectAsState()
    val dialogueSession by dialogueManager.session.collectAsState()
    val routerSettings by providerStore.settings.collectAsState()
    val routerStatus by modelRouter.status.collectAsState()

    var showLauncher by remember { mutableStateOf(true) }
    var activeSeed by remember { mutableStateOf("Cyberpunk fungal swamp ruled by clockwork owls") }
    var showRealityShiftBar by remember { mutableStateOf(false) }
    var realityShiftText by remember { mutableStateOf("") }
    var statusBanner by remember { mutableStateOf<String?>(null) }
    var showTelemetryDrawer by remember { mutableStateOf(false) }
    var showRouterSettings by remember { mutableStateOf(false) }

    var hud by remember { mutableStateOf(HudSnapshot()) }
    var npcSpawnerState by remember { mutableStateOf(NpcSpawnerState()) }
    var isMuted by remember { mutableStateOf(false) }

    var moveLeftHeld by remember { mutableStateOf(false) }
    var moveRightHeld by remember { mutableStateOf(false) }
    var jumpHeld by remember { mutableStateOf(false) }
    var jumpJustPressed by remember { mutableStateOf(false) }
    var jumpJustReleased by remember { mutableStateOf(false) }
    var dashJustPressed by remember { mutableStateOf(false) }

    val isDialogueOpen = dialogueSession.activeNpc != null

    fun openNpcConversation(npc: RuntimeNpc) {
        moveLeftHeld = false
        moveRightHeld = false
        jumpHeld = false
        engine.isPausedForDialogue = true
        audioEngine.setDialogueDucking(true)
        dialogueManager.openDialogue(npc, engine.currentBiome())
        hud = engine.buildHudSnapshot()
    }

    fun closeNpcConversation() {
        val toast = dialogueSession.lastConsequenceToast
        dialogueManager.closeDialogue()
        engine.isPausedForDialogue = false
        audioEngine.setDialogueDucking(false)
        if (!toast.isNullOrBlank()) {
            statusBanner = toast
        }
        hud = engine.buildHudSnapshot()
    }

    fun applyNpcConsequence(dto: GeminiDialogueConsequenceDto, npc: RuntimeNpc): String? {
        return when (dto.type) {
            ConsequenceType.NONE -> null
            ConsequenceType.GRANT_QUEST -> {
                val quest = ActiveQuest(
                    id = "q_${npc.id}_${System.currentTimeMillis()}",
                    title = dto.questTitle ?: "Sector Trial",
                    giverName = npc.name,
                    objectiveType = dto.questObjective ?: QuestObjectiveType.DEFEAT_ENEMIES,
                    targetCount = (dto.questTargetCount ?: 3).coerceIn(1, 8),
                    rewardScore = (dto.questRewardScore ?: 140).coerceIn(50, 500),
                    rewardBuff = dto.buffType ?: BuffType.SPEED_SURGE
                )
                engine.storyArcTracker.addQuest(quest)
                "Quest Started: ${quest.title}"
            }
            ConsequenceType.GRANT_BUFF -> {
                val buff = dto.buffType ?: BuffType.SHIELD
                val dur = dto.buffDurationSec ?: 30f
                engine.storyArcTracker.addOrRefreshBuff(buff, dur)
                dto.healAmount?.let { engine.healPlayer(it) }
                dto.summary
            }
            ConsequenceType.MERCHANT_TRADE -> {
                val cost = (dto.scoreCost ?: 0).coerceAtLeast(0)
                if (engine.player.score >= cost) {
                    engine.applyRewardScore(-cost)
                    dto.buffType?.let {
                        engine.storyArcTracker.addOrRefreshBuff(it, dto.buffDurationSec ?: 35f)
                    }
                    dto.healAmount?.let { engine.healPlayer(it) }
                    dto.summary
                } else {
                    "Need $cost Score (current: ${engine.player.score})"
                }
            }
            ConsequenceType.RECRUIT_COMPANION -> {
                engine.promoteNpcToCompanion(npc)
                dto.summary
            }
            ConsequenceType.TRIGGER_RIVAL_DUEL -> {
                engine.convertRivalToHostile(npc)
                dto.summary
            }
            ConsequenceType.MUTATE_NEXT_CHUNK -> {
                val mutation = dto.chunkMutationPrompt?.takeIf { it.isNotBlank() }
                    ?: dto.summary
                lookaheadBuffer.queueRealityShift(
                    modifierPrompt = mutation,
                    currentChunkIndex = hud.currentChunkIndex,
                    currentBiome = engine.currentBiome(),
                    telemetry = engine.telemetry,
                    engine = engine
                )
                "Mutated Chunk #${hud.currentChunkIndex + 2}: $mutation"
            }
        }
    }

    BackHandler(enabled = !showRouterSettings && (isDialogueOpen || showTelemetryDrawer || showRealityShiftBar || !showLauncher)) {
        when {
            isDialogueOpen -> closeNpcConversation()
            showTelemetryDrawer -> showTelemetryDrawer = false
            showRealityShiftBar -> showRealityShiftBar = false
            !showLauncher -> {
                showLauncher = true
                audioEngine.pause()
            }
        }
    }

    DisposableEffect(Unit) {
        engine.onEngineEvent = { event, biome ->
            if (event == EngineEvent.BIOME_ENTERED) {
                audioEngine.setBiomeAudio(biome.audioParams)
            } else {
                audioEngine.triggerEvent(event)
            }
        }
        engine.onLookaheadNeeded = { nextChunkIdx, currentBiome, telemetry ->
            lookaheadBuffer.requestLookahead(
                nextChunkIndex = nextChunkIdx,
                currentBiome = currentBiome,
                telemetry = telemetry,
                engine = engine
            )
        }
        engine.onQuestCompleted = { quest ->
            statusBanner = "QUEST COMPLETE: ${quest.title} (+${quest.rewardScore} Score & ${quest.rewardBuff.name})"
        }
        onDispose {
            engine.onEngineEvent = null
            engine.onLookaheadNeeded = null
            engine.onQuestCompleted = null
            lookaheadBuffer.cancelAll()
            audioEngine.release()
        }
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(3400)
            statusBanner = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090A16))
            .onPreviewKeyEvent { keyEvent ->
                if (showLauncher || showRealityShiftBar || isDialogueOpen || showRouterSettings) return@onPreviewKeyEvent false
                val isDown = keyEvent.type == KeyEventType.KeyDown
                val isUp = keyEvent.type == KeyEventType.KeyUp
                when (keyEvent.key) {
                    Key.A, Key.DirectionLeft -> {
                        if (isDown) moveLeftHeld = true else if (isUp) moveLeftHeld = false
                        true
                    }
                    Key.D, Key.DirectionRight -> {
                        if (isDown) moveRightHeld = true else if (isUp) moveRightHeld = false
                        true
                    }
                    Key.Spacebar, Key.W, Key.DirectionUp -> {
                        if (isDown && !jumpHeld) {
                            jumpHeld = true
                            jumpJustPressed = true
                        } else if (isUp) {
                            jumpHeld = false
                            jumpJustReleased = true
                        }
                        true
                    }
                    Key.ShiftLeft, Key.ShiftRight -> {
                        if (isDown) dashJustPressed = true
                        true
                    }
                    Key.E, Key.F -> {
                        if (isDown) {
                            val npc = hud.nearbyNpc
                            if (npc != null) {
                                openNpcConversation(npc)
                            } else if (keyEvent.key == Key.E) {
                                dashJustPressed = true
                            }
                        }
                        true
                    }
                    Key.R, Key.Slash -> {
                        if (isDown) showRealityShiftBar = true
                        true
                    }
                    else -> false
                }
            }
    ) {
        GameLoop(
            engine = engine,
            isActive = !showLauncher,
            nearbyNpcId = hud.nearbyNpc?.id,
            consumeInputSnapshot = {
                val moveX = when {
                    moveLeftHeld && !moveRightHeld -> -1f
                    moveRightHeld && !moveLeftHeld -> 1f
                    else -> 0f
                }
                val input = InputSnapshot(
                    moveX = if (showRealityShiftBar || showTelemetryDrawer || isDialogueOpen || showRouterSettings) 0f else moveX,
                    jumpHeld = jumpHeld,
                    jumpJustPressed = jumpJustPressed,
                    jumpJustReleased = jumpJustReleased,
                    dashJustPressed = dashJustPressed
                )
                jumpJustPressed = false
                jumpJustReleased = false
                dashJustPressed = false
                input
            },
            onHudSnapshot = { hud = it },
            onNpcSpawnerState = { npcSpawnerState = it },
            modifier = Modifier.fillMaxSize()
        )

        if (!showLauncher) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Surface(
                            color = Color(0xAA0E1324),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    repeat(hud.maxHp) { idx ->
                                        Icon(
                                            imageVector = Icons.Filled.Favorite,
                                            contentDescription = "Health point ${idx + 1}",
                                            tint = if (idx < hud.hp) Color(0xFFFF4D6D) else Color(0xFF3A3F58),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "SCORE ${hud.score}",
                                        color = Color(0xFF00F5D4),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Ch.${storyArc.chapterNumber} • ${hud.currentBiomeName} (#${hud.currentChunkIndex + 1})",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                                val aiBadge = when (bufferStatus.state) {
                                    LookaheadState.GENERATING -> "AI N+1: SYNTHESIZING..."
                                    LookaheadState.READY -> "AI N+1: READY"
                                    LookaheadState.ERROR -> "AI FALLBACK ACTIVE"
                                    else -> bufferStatus.state.name
                                }
                                Text(
                                    text = "$aiBadge • Repairs: ${hud.autoRepairsApplied}",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 10.sp
                                )
                            }
                        }

                        // Diegetic Active Quests & Buffs Pills
                        val activeQuest = storyArc.activeQuests.lastOrNull { !it.completed }
                        if (activeQuest != null || storyArc.activeBuffs.isNotEmpty() || storyArc.companionName != null) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                activeQuest?.let { q ->
                                    Surface(
                                        color = Color(0xBB1E1638),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.border(1.dp, Color(0x66C084FC), RoundedCornerShape(10.dp))
                                    ) {
                                        Text(
                                            text = "Quest: ${q.title} (${q.currentCount}/${q.targetCount})",
                                            color = Color(0xFFE9D5FF),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                storyArc.companionName?.let { compName ->
                                    Surface(
                                        color = Color(0xBB0D2B1F),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.border(1.dp, Color(0x664ADE80), RoundedCornerShape(10.dp))
                                    ) {
                                        Text(
                                            text = "Ally: $compName",
                                            color = Color(0xFF86EFAC),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                                storyArc.activeBuffs.forEach { buff ->
                                    val timerText = if (buff.isPermanent) "ACTIVE" else "${buff.remainingSec.toInt()}s"
                                    Surface(
                                        color = Color(0xBB0D2538),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.border(1.dp, Color(0x6638BDF8), RoundedCornerShape(10.dp))
                                    ) {
                                        Text(
                                            text = "${buff.label} ($timerText)",
                                            color = Color(0xFF7DD3FC),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = if (showRealityShiftBar) Color(0xDD7E22CE) else Color(0xCC1E293B),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .clickable { showRealityShiftBar = !showRealityShiftBar }
                                .testTag("reality_shift_toggle_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = "Reality Shift",
                                    tint = Color(0xFFE879F9),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Shift",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Surface(color = Color(0xCC1E293B), shape = CircleShape) {
                            IconButton(
                                onClick = {
                                    isMuted = !isMuted
                                    audioEngine.isMuted = isMuted
                                },
                                modifier = Modifier.testTag("audio_mute_button")
                            ) {
                                Icon(
                                    imageVector = if (isMuted) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    contentDescription = if (isMuted) "Unmute Procedural Synth" else "Mute Procedural Synth",
                                    tint = Color.White
                                )
                            }
                        }

                        Surface(color = Color(0xCC1E293B), shape = CircleShape) {
                            IconButton(
                                onClick = { showTelemetryDrawer = !showTelemetryDrawer },
                                modifier = Modifier.testTag("telemetry_drawer_toggle_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.BugReport,
                                    contentDescription = "Toggle AI Telemetry & Story Inspector",
                                    tint = Color(0xFF00F5D4)
                                )
                            }
                        }

                        Surface(color = Color(0xCC1E293B), shape = CircleShape) {
                            IconButton(
                                onClick = {
                                    showLauncher = true
                                    audioEngine.pause()
                                },
                                modifier = Modifier.testTag("open_launcher_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Tune,
                                    contentDescription = "Open World Seed Launcher",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }

                if (hud.storyText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "“${hud.storyText}”",
                        color = Color(0xFFD6E0FA),
                        fontSize = 11.sp,
                        fontStyle = FontStyle.Italic,
                        modifier = Modifier
                            .background(Color(0x66090B1A), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                AnimatedVisibility(
                    visible = showRealityShiftBar,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    RealityShiftPromptBar(
                        text = realityShiftText,
                        onTextChange = { realityShiftText = it },
                        onSubmit = { prompt ->
                            val cleaned = prompt.trim()
                            if (cleaned.isNotEmpty()) {
                                lookaheadBuffer.queueRealityShift(
                                    modifierPrompt = cleaned,
                                    currentChunkIndex = hud.currentChunkIndex,
                                    currentBiome = engine.currentBiome(),
                                    telemetry = engine.telemetry,
                                    engine = engine
                                )
                                statusBanner = "REALITY SHIFT QUEUED FOR CHUNK #${hud.currentChunkIndex + 2}: $cleaned"
                                realityShiftText = ""
                                showRealityShiftBar = false
                            }
                        }
                    )
                }

                AnimatedVisibility(visible = statusBanner != null) {
                    statusBanner?.let { banner ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = Color(0xCC581C87),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.border(1.dp, Color(0xFFE879F9), RoundedCornerShape(10.dp))
                        ) {
                            Text(
                                text = banner,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            // Contextual Floating NPC Talk Prompt
            AnimatedVisibility(
                visible = hud.nearbyNpc != null && !isDialogueOpen && !hud.isGameOver,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 96.dp)
            ) {
                hud.nearbyNpc?.let { npc ->
                    Button(
                        onClick = { openNpcConversation(npc) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xEE0D152E),
                            contentColor = Color(0xFF00F5D4)
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .border(1.5.dp, Color(0xFF00F5D4), RoundedCornerShape(24.dp))
                            .testTag("talk_npc_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = "Talk to ${npc.name}",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Talk to ${npc.name}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // Game Over / Mutation Overlay
            if (hud.isGameOver) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99060812)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        color = Color(0xFF15192B),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .widthIn(max = 360.dp)
                            .border(1.dp, Color(0x44FF4D6D), RoundedCornerShape(22.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "SIGNAL LOST",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color(0xFFFF4D6D),
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Score: ${hud.score} • Ch.${storyArc.chapterNumber} • Sector #${hud.currentChunkIndex + 1}",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(18.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        showLauncher = true
                                        audioEngine.pause()
                                    },
                                    modifier = Modifier.testTag("gameover_new_seed_button")
                                ) {
                                    Text("New Seed")
                                }
                                Button(
                                    onClick = {
                                        dialogueManager.closeDialogue()
                                        lookaheadBuffer.initializeRun(activeSeed, engine)
                                        audioEngine.setBiomeAudio(engine.currentBiome().audioParams)
                                        hud = engine.buildHudSnapshot()
                                    },
                                    modifier = Modifier.testTag("restart_run_button")
                                ) {
                                    Text("Respawn")
                                }
                            }
                        }
                    }
                }
            }

            // Bottom Virtual Controls
            if (!isDialogueOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HoldActionPadButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Move Left",
                            testTag = "move_left_button",
                            onPressChange = { moveLeftHeld = it }
                        )
                        HoldActionPadButton(
                            icon = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Move Right",
                            testTag = "move_right_button",
                            onPressChange = { moveRightHeld = it }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HoldActionPadButton(
                            icon = Icons.Filled.Bolt,
                            contentDescription = "Dash",
                            testTag = "dash_button",
                            tint = Color(0xFFFFD166),
                            onPressChange = { pressed ->
                                if (pressed) dashJustPressed = true
                            }
                        )
                        HoldActionPadButton(
                            icon = Icons.Filled.ArrowUpward,
                            contentDescription = "Jump",
                            testTag = "jump_button",
                            tint = Color(0xFF00F5D4),
                            onPressChange = { pressed ->
                                if (pressed && !jumpHeld) {
                                    jumpHeld = true
                                    jumpJustPressed = true
                                } else if (!pressed) {
                                    jumpHeld = false
                                    jumpJustReleased = true
                                }
                            }
                        )
                    }
                }
            }

            // Full Pause-and-Converse Branching NPC Dialogue Overlay
            NpcDialogueOverlay(
                session = dialogueSession,
                playerScore = hud.score,
                onSelectChoice = { choice ->
                    coroutineScope.launch {
                        dialogueManager.sendTurn(
                            playerMessage = choice.label,
                            biome = engine.currentBiome(),
                            playerScore = engine.player.score,
                            playerHp = engine.player.hp,
                            maxHp = engine.player.maxHp,
                            onApplyConsequence = ::applyNpcConsequence
                        )
                        hud = engine.buildHudSnapshot()
                    }
                },
                onSendCustomMessage = { message ->
                    coroutineScope.launch {
                        dialogueManager.sendTurn(
                            playerMessage = message,
                            biome = engine.currentBiome(),
                            playerScore = engine.player.score,
                            playerHp = engine.player.hp,
                            maxHp = engine.player.maxHp,
                            onApplyConsequence = ::applyNpcConsequence
                        )
                        hud = engine.buildHudSnapshot()
                    }
                },
                onClose = { closeNpcConversation() }
            )

            // Collapsible AI Telemetry, Story Arc & JSON Inspector Drawer
            TelemetryDrawer(
                visible = showTelemetryDrawer,
                hud = hud,
                bufferStatus = bufferStatus,
                storyArc = storyArc,
                npcSpawnerState = npcSpawnerState,
                routerSummary = routerSettings.activeSummary(),
                routerStatus = routerStatus,
                onOpenAiRouterSettings = {
                    engine.isPausedForDialogue = true
                    showRouterSettings = true
                },
                onClose = { showTelemetryDrawer = false },
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }

        if (showLauncher) {
            LauncherOverlay(
                initialSeed = activeSeed,
                routerSummary = routerSettings.activeSummary(),
                onOpenAiRouterSettings = { showRouterSettings = true },
                onLaunchWorld = { chosenSeed ->
                    activeSeed = chosenSeed.ifBlank { "Cyberpunk fungal swamp ruled by clockwork owls" }
                    showLauncher = false
                    showRealityShiftBar = false
                    showTelemetryDrawer = false
                    dialogueManager.closeDialogue()
                    lookaheadBuffer.initializeRun(activeSeed, engine)
                    audioEngine.resume()
                    audioEngine.setBiomeAudio(engine.currentBiome().audioParams)
                    hud = engine.buildHudSnapshot()
                }
            )
        }

        AiRouterSettingsSheet(
            visible = showRouterSettings,
            settings = routerSettings,
            routerStatus = routerStatus,
            onSaveSettings = { updated -> providerStore.updateSettings(updated) },
            onClearAllKeys = { providerStore.clearAllApiKeys() },
            onClearCooldowns = { modelRouter.clearCooldowns() },
            onTestRoute = { target -> modelRouter.testProvider(target) },
            onClose = {
                showRouterSettings = false
                if (!isDialogueOpen) {
                    engine.isPausedForDialogue = false
                }
            }
        )
    }
}

@Composable
private fun RealityShiftPromptBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: (String) -> Unit
) {
    val quickShifts = remember {
        listOf(
            "Reverse gravity feel",
            "Suddenly everything is lava",
            "Swarm of laser drones",
            "Low-gravity moon bounce"
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Surface(
        color = Color(0xEE11162B),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .border(1.dp, Color(0x88E879F9), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("Inject mid-run mutation for Chunk N+1...", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFE879F9),
                        unfocusedBorderColor = Color(0xFF334155),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reality_shift_input")
                )
                Button(
                    onClick = { onSubmit(text) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA855F7)),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("reality_shift_submit_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Queue Reality Shift",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                quickShifts.forEachIndexed { idx, shift ->
                    Surface(
                        color = Color(0xFF1F2746),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .clickable { onSubmit(shift) }
                            .testTag("quick_shift_$idx")
                    ) {
                        Text(
                            text = shift,
                            color = Color(0xFFE9D5FF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldActionPadButton(
    icon: ImageVector,
    contentDescription: String,
    testTag: String,
    tint: Color = Color.White,
    onPressChange: (Boolean) -> Unit
) {
    Surface(
        color = Color(0x99161D35),
        shape = CircleShape,
        modifier = Modifier
            .size(62.dp)
            .testTag(testTag)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onPressChange(true)
                    waitForUpOrCancellation()
                    onPressChange(false)
                }
            }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}
