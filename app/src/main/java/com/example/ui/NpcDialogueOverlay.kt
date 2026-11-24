package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.DialogueChoice
import com.example.ai.DialogueSessionState
import com.example.render.SpriteCompiler
import com.example.schemas.NpcRole

@Composable
fun NpcDialogueOverlay(
    session: DialogueSessionState,
    playerScore: Int,
    onSelectChoice: (DialogueChoice) -> Unit,
    onSendCustomMessage: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val npc = session.activeNpc
    AnimatedVisibility(
        visible = npc != null,
        enter = fadeIn() + scaleIn(initialScale = 0.96f),
        exit = fadeOut() + scaleOut(targetScale = 0.96f),
        modifier = modifier
    ) {
        if (npc == null) return@AnimatedVisibility
        var customInput by remember { mutableStateOf("") }
        val scrollState = rememberScrollState()

        LaunchedEffect(session.messages.size) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }

        val roleColor = when (npc.role) {
            NpcRole.GUIDE_MERCHANT -> Color(0xFF00F5D4)
            NpcRole.LOREKEEPER -> Color(0xFFC084FC)
            NpcRole.COMPANION -> Color(0xFF4ADE80)
            NpcRole.RIVAL -> Color(0xFFFB923C)
        }
        val roleLabel = when (npc.role) {
            NpcRole.GUIDE_MERCHANT -> "GUIDE & MERCHANT"
            NpcRole.LOREKEEPER -> "LOREKEEPER"
            NpcRole.COMPANION -> "COMPANION"
            NpcRole.RIVAL -> "RIVAL WANDERER"
        }
        val portrait = remember(npc.id) {
            SpriteCompiler.getOrCompile(npc.id, npc.spriteMatrix, scaleFactor = 4).frames.firstOrNull()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xAA060814))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                color = Color(0xF20D1124),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .border(1.dp, roleColor.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
                    .testTag("npc_dialogue_overlay")
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Header: Portrait, Name, Role, Disposition & Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color(0xFF151C36),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .size(62.dp)
                                .border(1.dp, roleColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        ) {
                            if (portrait != null) {
                                Image(
                                    bitmap = portrait,
                                    contentDescription = "${npc.name} portrait",
                                    contentScale = ContentScale.Fit,
                                    filterQuality = FilterQuality.None,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = npc.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = roleLabel,
                                    color = roleColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                                val dispLabel = when {
                                    npc.disposition >= 20 -> "Allied (${npc.disposition})"
                                    npc.disposition <= -15 -> "Hostile (${npc.disposition})"
                                    else -> "Wary (${npc.disposition})"
                                }
                                Text(
                                    text = "• $dispLabel • Score: $playerScore",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = npc.personality,
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                            session.activeRouteLabel?.let { route ->
                                Text(
                                    text = "Route: $route",
                                    color = Color(0xFF7DD3FC),
                                    fontSize = 10.sp
                                )
                            }
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier.testTag("close_dialogue_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Leave conversation",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Transcript area
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp, max = 210.dp)
                            .background(Color(0xFF080C1A), RoundedCornerShape(14.dp))
                            .padding(12.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        session.messages.forEach { msg ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = if (msg.isPlayer) Alignment.End else Alignment.Start
                            ) {
                                Surface(
                                    color = if (msg.isPlayer) Color(0xFF1E293B) else Color(0xFF151C36),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.widthIn(max = 460.dp)
                                ) {
                                    Text(
                                        text = msg.text,
                                        color = if (msg.isPlayer) Color(0xFFE2E8F0) else Color.White,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                                msg.consequenceBadge?.let { badge ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Surface(
                                        color = roleColor.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.border(
                                            1.dp,
                                            roleColor.copy(alpha = 0.6f),
                                            RoundedCornerShape(8.dp)
                                        )
                                    ) {
                                        Text(
                                            text = badge,
                                            color = roleColor,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (session.isThinking) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = roleColor,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "${npc.name} is thinking...",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }

                    session.errorNotice?.let { notice ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = notice,
                            color = Color(0xFFFBBF24),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Tactical Branching Choices
                    if (session.suggestedChoices.isNotEmpty() && !session.isThinking) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            session.suggestedChoices.take(3).forEachIndexed { idx, choice ->
                                Surface(
                                    color = Color(0xFF16203C),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .border(
                                            1.dp,
                                            roleColor.copy(alpha = 0.35f),
                                            RoundedCornerShape(12.dp)
                                        )
                                        .clickable { onSelectChoice(choice) }
                                        .testTag("dialogue_choice_$idx")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = choice.label,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            color = roleColor.copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(
                                                text = choice.intentHint,
                                                color = roleColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    // Free-form player chat + Leave button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customInput,
                            onValueChange = { customInput = it },
                            placeholder = { Text("Say anything or negotiate...", fontSize = 12.sp) },
                            singleLine = true,
                            enabled = !session.isThinking,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = roleColor,
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dialogue_custom_input")
                        )
                        Button(
                            onClick = {
                                val text = customInput.trim()
                                if (text.isNotEmpty()) {
                                    customInput = ""
                                    onSendCustomMessage(text)
                                }
                            },
                            enabled = !session.isThinking && customInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = roleColor,
                                contentColor = Color(0xFF090B1A)
                            ),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("dialogue_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send reply",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
