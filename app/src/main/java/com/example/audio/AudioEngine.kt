package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.engine.EngineEvent
import com.example.schemas.AudioParams
import com.example.schemas.WaveformTimbre
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Non-blocking real-time procedural AudioTrack synthesizer & step sequencer.
 * Zero static audio assets: generates adaptive biome basslines, lead arpeggios,
 * smooth biome crossfades, and scale-tuned SFX in a dedicated PCM synthesis loop.
 */
class AudioEngine {

    private val sampleRate = 22050
    private val bufferSamples = 512

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    @Volatile
    var isMuted: Boolean = false

    @Volatile
    private var dialogueDucked: Boolean = false

    @Volatile
    private var currentParams: AudioParams = AudioParams(
        bpm = 120,
        rootNoteHz = 98f,
        scaleIntervals = listOf(0, 2, 3, 5, 7, 9, 10),
        bassPattern = listOf(0, -1, 0, 2, 3, -1, 4, 2),
        leadArpPattern = listOf(0, 2, 4, 5, 6, 4, 3, 2),
        waveformTimbre = WaveformTimbre.SQUARE
    )

    @Volatile
    private var targetParams: AudioParams = currentParams

    private val activeSfx = ConcurrentLinkedQueue<ActiveSfxVoice>()
    private var synthThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    fun setDialogueDucking(ducked: Boolean) {
        dialogueDucked = ducked
    }

    fun start() {
        if (running) return
        running = true
        paused = false

        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(bufferSamples * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        synthThread = thread(start = true, isDaemon = true, name = "WorldShiftAudioSynth") {
            runSynthLoop(track)
        }
    }

    fun setBiomeAudio(params: AudioParams) {
        targetParams = params
    }

    fun triggerEvent(event: EngineEvent) {
        if (!running || paused || isMuted) return
        val params = currentParams
        val root = params.rootNoteHz
        val scale = params.scaleIntervals.ifEmpty { listOf(0, 3, 5, 7, 10) }

        when (event) {
            EngineEvent.JUMP -> {
                val startHz = noteHz(root, scale, 0, octaveShift = 1)
                val endHz = noteHz(root, scale, 2, octaveShift = 2)
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.SWEEP,
                        startHz = startHz,
                        endHz = endHz,
                        durationSec = 0.14f,
                        gain = 0.24f,
                        timbre = WaveformTimbre.TRIANGLE
                    )
                )
            }
            EngineEvent.LAND -> {
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.NOISE_THUD,
                        startHz = 140f,
                        endHz = 55f,
                        durationSec = 0.08f,
                        gain = 0.22f,
                        timbre = WaveformTimbre.SINE
                    )
                )
            }
            EngineEvent.COLLECT -> {
                val f1 = noteHz(root, scale, 2, octaveShift = 2)
                val f2 = noteHz(root, scale, 4, octaveShift = 3)
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.CHIME,
                        startHz = f1,
                        endHz = f2,
                        durationSec = 0.20f,
                        gain = 0.26f,
                        timbre = WaveformTimbre.SINE
                    )
                )
            }
            EngineEvent.COMPANION_BOLT -> {
                val f1 = noteHz(root, scale, 3, octaveShift = 2)
                val f2 = noteHz(root, scale, 5, octaveShift = 2)
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.SWEEP,
                        startHz = f1,
                        endHz = f2,
                        durationSec = 0.09f,
                        gain = 0.14f,
                        timbre = WaveformTimbre.SINE
                    )
                )
            }
            EngineEvent.QUEST_COMPLETE -> {
                val f1 = noteHz(root, scale, 0, octaveShift = 2)
                val f2 = noteHz(root, scale, 4, octaveShift = 3)
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.CHIME,
                        startHz = f1,
                        endHz = f2,
                        durationSec = 0.35f,
                        gain = 0.30f,
                        timbre = WaveformTimbre.TRIANGLE
                    )
                )
            }
            EngineEvent.STOMP_ENEMY -> {
                val f1 = noteHz(root, scale, 4, octaveShift = 2)
                val f2 = noteHz(root, scale, 0, octaveShift = 1)
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.SWEEP,
                        startHz = f1,
                        endHz = f2,
                        durationSec = 0.16f,
                        gain = 0.30f,
                        timbre = WaveformTimbre.SQUARE
                    )
                )
            }
            EngineEvent.DASH -> {
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.NOISE_THUD,
                        startHz = 360f,
                        endHz = 120f,
                        durationSec = 0.13f,
                        gain = 0.20f,
                        timbre = WaveformTimbre.SAWTOOTH
                    )
                )
            }
            EngineEvent.PLAYER_DAMAGE -> {
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.BITCRUSH_FM,
                        startHz = 280f,
                        endHz = 72f,
                        durationSec = 0.25f,
                        gain = 0.32f,
                        timbre = WaveformTimbre.SAWTOOTH
                    )
                )
            }
            EngineEvent.PLAYER_DEATH -> {
                activeSfx.add(
                    ActiveSfxVoice(
                        kind = SfxKind.BITCRUSH_FM,
                        startHz = 240f,
                        endHz = 42f,
                        durationSec = 0.55f,
                        gain = 0.36f,
                        timbre = WaveformTimbre.SQUARE
                    )
                )
            }
            EngineEvent.BIOME_ENTERED -> Unit
        }
    }

    fun pause() {
        paused = true
        try {
            audioTrack?.pause()
        } catch (_: Exception) {
        }
    }

    fun resume() {
        if (!running) {
            start()
            return
        }
        paused = false
        try {
            audioTrack?.play()
        } catch (_: Exception) {
        }
    }

    fun release() {
        running = false
        paused = true
        try {
            synthThread?.join(250)
        } catch (_: Exception) {
        }
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        synthThread = null
        activeSfx.clear()
    }

    private fun runSynthLoop(track: AudioTrack) {
        val pcm = ShortArray(bufferSamples)
        var bassPhase = 0f
        var leadPhase = 0f
        var stepProgress = 0f
        var stepIndex = 0
        var crossfadeAlpha = 1f
        var smoothedRootHz = currentParams.rootNoteHz
        var smoothedBpm = currentParams.bpm.toFloat()

        while (running) {
            if (paused) {
                Thread.sleep(25)
                continue
            }

            if (targetParams !== currentParams) {
                crossfadeAlpha = (crossfadeAlpha - 0.04f).coerceAtLeast(0f)
                if (crossfadeAlpha <= 0.01f) {
                    currentParams = targetParams
                }
            } else if (crossfadeAlpha < 1f) {
                crossfadeAlpha = (crossfadeAlpha + 0.04f).coerceAtMost(1f)
            }

            val params = currentParams
            smoothedRootHz += (params.rootNoteHz - smoothedRootHz) * 0.08f
            smoothedBpm += (params.bpm.toFloat() - smoothedBpm) * 0.08f

            val stepsPerSec = (smoothedBpm.coerceIn(50f, 200f) / 60f) * 2f // eighth-note pulse
            val stepIncrement = stepsPerSec / sampleRate
            val scale = params.scaleIntervals.ifEmpty { listOf(0, 2, 4, 7, 9) }

            for (i in 0 until bufferSamples) {
                stepProgress += stepIncrement
                if (stepProgress >= 1f) {
                    stepProgress -= 1f
                    stepIndex++
                }

                val bassIdx = params.bassPattern[stepIndex % params.bassPattern.size]
                val leadIdx = params.leadArpPattern[stepIndex % params.leadArpPattern.size]

                val env = (1f - stepProgress).pow(1.4f)
                var mix = 0f

                if (bassIdx >= 0) {
                    val bassHz = noteHz(smoothedRootHz, scale, bassIdx, octaveShift = 0)
                    bassPhase = (bassPhase + bassHz / sampleRate) % 1f
                    mix += sampleWave(params.waveformTimbre, bassPhase) * 0.14f * env * crossfadeAlpha
                }

                if (leadIdx >= 0) {
                    val leadHz = noteHz(smoothedRootHz, scale, leadIdx, octaveShift = 2)
                    leadPhase = (leadPhase + leadHz / sampleRate) % 1f
                    val leadEnv = (1f - stepProgress).pow(2.2f)
                    mix += sampleWave(WaveformTimbre.TRIANGLE, leadPhase) * 0.11f * leadEnv * crossfadeAlpha
                }

                // Mix active procedural SFX voices
                val iter = activeSfx.iterator()
                while (iter.hasNext()) {
                    val voice = iter.next()
                    val t = (voice.elapsedSec / voice.durationSec).coerceIn(0f, 1f)
                    val freq = voice.startHz + (voice.endHz - voice.startHz) * t
                    val vEnv = (1f - t)
                    voice.phase = (voice.phase + freq / sampleRate) % 1f

                    val sample = when (voice.kind) {
                        SfxKind.SWEEP -> sampleWave(voice.timbre, voice.phase)
                        SfxKind.CHIME -> {
                            val harmonic = sampleWave(WaveformTimbre.SINE, (voice.phase * 2f) % 1f)
                            (sampleWave(WaveformTimbre.SINE, voice.phase) * 0.7f + harmonic * 0.3f)
                        }
                        SfxKind.NOISE_THUD -> {
                            val noise = Random.nextFloat() * 2f - 1f
                            (noise * 0.65f + sampleWave(WaveformTimbre.SINE, voice.phase) * 0.35f)
                        }
                        SfxKind.BITCRUSH_FM -> {
                            val mod = sin(2.0 * PI * voice.phase * 3.5).toFloat() * 0.4f
                            val raw = sampleWave(voice.timbre, (voice.phase + mod).mod(1f))
                            floor(raw * 4f) / 4f // 2-bit quantize crunch
                        }
                    }

                    mix += sample * voice.gain * vEnv
                    voice.elapsedSec += 1f / sampleRate
                    if (voice.elapsedSec >= voice.durationSec) {
                        iter.remove()
                    }
                }

                val duckMult = if (dialogueDucked) 0.28f else 1f
                val clamped = if (isMuted) 0f else (mix * duckMult).coerceIn(-0.92f, 0.92f)
                pcm[i] = (clamped * Short.MAX_VALUE).toInt().toShort()
            }

            try {
                track.write(pcm, 0, pcm.size)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun noteHz(rootHz: Float, scale: List<Int>, degreeIndex: Int, octaveShift: Int): Float {
        val len = scale.size
        val oct = octaveShift + (degreeIndex / len)
        val semitones = scale[degreeIndex % len] + oct * 12
        return rootHz * 2f.pow(semitones / 12f)
    }

    private fun sampleWave(timbre: WaveformTimbre, phase: Float): Float {
        return when (timbre) {
            WaveformTimbre.SINE -> sin(2.0 * PI * phase).toFloat()
            WaveformTimbre.SQUARE -> if (phase < 0.5f) 0.85f else -0.85f
            WaveformTimbre.SAWTOOTH -> (phase * 2f - 1f) * 0.85f
            WaveformTimbre.TRIANGLE -> 1f - 4f * abs(phase - 0.5f)
        }
    }

    private enum class SfxKind {
        SWEEP,
        CHIME,
        NOISE_THUD,
        BITCRUSH_FM
    }

    private data class ActiveSfxVoice(
        val kind: SfxKind,
        val startHz: Float,
        val endHz: Float,
        val durationSec: Float,
        val gain: Float,
        val timbre: WaveformTimbre,
        var elapsedSec: Float = 0f,
        var phase: Float = 0f
    )
}
