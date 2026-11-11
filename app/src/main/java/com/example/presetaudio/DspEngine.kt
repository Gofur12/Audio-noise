package com.example.presetaudio

import android.annotation.SuppressLint
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class Capabilities(
    val equalizer: Boolean = false,
    val bassBoost: Boolean = false,
    val stereoWidth: Boolean = false,
    val dynamics: Boolean = false // compressor + limiter
)

sealed interface ApplyState {
    object Idle : ApplyState
    object Applying : ApplyState
    data class Applied(val skipped: List<String>) : ApplyState
}

/** Nilai akhir yang dikirim ke efek perangkat. Dipakai untuk interpolasi (ramp). */
private class Frame(
    val bands: FloatArray, // dB per band perangkat
    val bass: Float,       // 0..1000
    val virt: Float,       // 0..1000
    val ratio: Float,
    val thr: Float,        // dB
    val makeup: Float,     // dB
    val limThr: Float      // dB
)

/**
 * @param sessionId 0 = efek global (hanya jalan di sebagian perangkat/ROM);
 *                  atau session ID pemutar yang dikontrol.
 */
@SuppressLint("NewApi")
class DspEngine(private val sessionId: Int = 0) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private var eq: Equalizer?
    private var bb: BassBoost?
    private var vz: Virtualizer?
    private var dp: DynamicsProcessing?
    private var bandHz = FloatArray(0)
    private var minDb = -15f
    private var maxDb = 15f

    @Volatile private var current: Frame? = null

    val caps: Capabilities
    private val _status = MutableStateFlow<ApplyState>(ApplyState.Idle)
    val status: StateFlow<ApplyState> = _status.asStateFlow()

    init {
        eq = tryOrNull { Equalizer(0, sessionId) }.keepIf { true }
        eq?.let { e ->
            val r = e.bandLevelRange
            minDb = r[0] / 100f
            maxDb = r[1] / 100f
            bandHz = FloatArray(e.numberOfBands.toInt()) { e.getCenterFreq(it.toShort()) / 1000f }
        }
        bb = tryOrNull { BassBoost(0, sessionId) }.keepIf { it.strengthSupported }
        vz = tryOrNull { Virtualizer(0, sessionId) }.keepIf { it.strengthSupported }
        dp = if (Build.VERSION.SDK_INT >= 28) tryOrNull { buildDynamics() } else null
        caps = Capabilities(eq != null, bb != null, vz != null, dp != null)

        val start = neutral()
        push(start)
        current = start
    }

    /** Terapkan konfigurasi. smooth=true → ramp ±150 ms agar tidak ada pop saat ganti preset. */
    fun apply(config: DspConfig, smooth: Boolean = true) {
        val target = resolve(config)
        val from = current ?: neutral()
        job?.cancel()
        job = scope.launch {
            _status.value = ApplyState.Applying
            val steps = if (smooth) 10 else 1
            for (i in 1..steps) {
                val frame = lerp(from, target, i / steps.toFloat())
                push(frame)
                current = frame
                if (smooth) delay(15)
            }
            _status.value = ApplyState.Applied(skipped(config))
        }
    }

    fun release() {
        job?.cancel()
        scope.cancel()
        listOf(eq, bb, vz, dp).forEach { runCatching { it?.release() } }
    }

    // ---------- internal ----------

    private fun resolve(c: DspConfig): Frame {
        val bands = FloatArray(bandHz.size) { Curve.gainDb(c, bandHz[it]).coerceIn(minDb, maxDb) }
        val ratio = 1f + 3f * c.compressor
        val thr = -6f - 18f * c.compressor
        val makeup = (-thr * (1f - 1f / ratio) * 0.5f).coerceAtMost(6f)
        return Frame(
            bands = bands,
            bass = (c.bass.coerceAtLeast(0f) / 6f * 700f),
            virt = c.stereoWidth * 1000f,
            ratio = ratio, thr = thr, makeup = makeup,
            // limiter "off" = threshold 0 dBFS (praktis transparan, tetap jadi pengaman clipping)
            limThr = if (c.limiterOn) c.limiterDb else 0f
        )
    }

    private fun neutral() = Frame(FloatArray(bandHz.size), 0f, 0f, 1f, 0f, 0f, 0f)

    private fun lerp(a: Frame, b: Frame, t: Float): Frame {
        fun m(x: Float, y: Float) = x + (y - x) * t
        return Frame(
            FloatArray(b.bands.size) { m(a.bands.getOrElse(it) { 0f }, b.bands[it]) },
            m(a.bass, b.bass), m(a.virt, b.virt),
            m(a.ratio, b.ratio), m(a.thr, b.thr), m(a.makeup, b.makeup), m(a.limThr, b.limThr)
        )
    }

    private fun push(f: Frame) {
        eq?.let { e ->
            f.bands.forEachIndexed { i, db ->
                runCatching { e.setBandLevel(i.toShort(), (db * 100).toInt().toShort()) }
            }
        }
        bb?.runCatching { setStrength(f.bass.toInt().coerceIn(0, 1000).toShort()) }
        vz?.runCatching { setStrength(f.virt.toInt().coerceIn(0, 1000).toShort()) }
        dp?.let { pushDynamics(it, f) }
    }

    private fun buildDynamics(): DynamicsProcessing {
        val cfg = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
            false, 0, // pre-EQ (tidak dipakai; EQ memakai Equalizer)
            true, 1,  // MBC 1 band = kompresor wideband
            false, 0, // post-EQ
            true      // limiter
        ).build()
        return DynamicsProcessing(0, sessionId, cfg).apply { enabled = true }
    }

    private fun pushDynamics(d: DynamicsProcessing, f: Frame) {
        val mbc = DynamicsProcessing.MbcBand(
            true, 20000f, 10f, 120f, f.ratio, f.thr, 6f, -90f, 1f, 0f, f.makeup
        )
        val lim = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, f.limThr, 0f)
        for (ch in 0 until 2) {
            runCatching {
                d.setMbcBandByChannelIndex(ch, 0, mbc)
                d.setLimiterByChannelIndex(ch, lim)
            }
        }
    }

    private fun skipped(c: DspConfig) = buildList {
        if (!caps.equalizer) add("Equalizer")
        if (!caps.bassBoost && c.bass > 0f) add("Bass boost")
        if (!caps.stereoWidth && c.stereoWidth > 0f) add("Stereo width")
        if (!caps.dynamics && (c.compressor > 0f || c.limiterOn)) add("Compressor/Limiter")
    }

    private fun <T> tryOrNull(block: () -> T): T? = runCatching { block() }.getOrNull()

    /** Aktifkan efek jika didukung; jika tidak, langsung dilepas agar tidak bocor. */
    private fun <T : AudioEffect> T?.keepIf(ok: (T) -> Boolean): T? {
        val e = this ?: return null
        return if (runCatching { ok(e) }.getOrDefault(false)) {
            e.enabled = true
            e
        } else {
            runCatching { e.release() }
            null
        }
    }
}
