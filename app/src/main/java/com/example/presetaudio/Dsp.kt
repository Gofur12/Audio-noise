package com.example.presetaudio

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/** Seluruh konfigurasi DSP dalam satuan yang ramah pengguna. */
data class DspConfig(
    val eq: List<Float> = List(5) { 0f }, // dB pada 60, 230, 910, 3600, 14000 Hz
    val bass: Float = 0f,                 // -6..+6 dB
    val treble: Float = 0f,               // -6..+6 dB
    val vocal: Float = 0f,                // -6..+6 dB
    val clarity: Float = 0f,              // -6..+6 dB
    val stereoWidth: Float = 0f,          // 0..1
    val compressor: Float = 0f,           // 0..1
    val limiterOn: Boolean = true,
    val limiterDb: Float = -1f            // -6..0 dB
) {
    fun toJson() = JSONObject()
        .put("eq", JSONArray(eq.map { it.toDouble() }))
        .put("bass", bass.toDouble()).put("treble", treble.toDouble())
        .put("vocal", vocal.toDouble()).put("clarity", clarity.toDouble())
        .put("width", stereoWidth.toDouble()).put("comp", compressor.toDouble())
        .put("limOn", limiterOn).put("limDb", limiterDb.toDouble())

    companion object {
        fun fromJson(o: JSONObject): DspConfig {
            val a = o.getJSONArray("eq")
            return DspConfig(
                eq = List(5) { a.optDouble(it, 0.0).toFloat() },
                bass = o.optDouble("bass", 0.0).toFloat(),
                treble = o.optDouble("treble", 0.0).toFloat(),
                vocal = o.optDouble("vocal", 0.0).toFloat(),
                clarity = o.optDouble("clarity", 0.0).toFloat(),
                stereoWidth = o.optDouble("width", 0.0).toFloat(),
                compressor = o.optDouble("comp", 0.0).toFloat(),
                limiterOn = o.optBoolean("limOn", true),
                limiterDb = o.optDouble("limDb", -1.0).toFloat()
            )
        }
    }
}

data class Preset(
    val id: String,
    val name: String,
    val desc: String,
    val config: DspConfig,
    val builtIn: Boolean = true
) {
    fun toJson() = JSONObject().put("id", id).put("name", name).put("desc", desc).put("config", config.toJson())

    companion object {
        fun fromJson(o: JSONObject) = Preset(
            o.getString("id"), o.getString("name"), o.optString("desc"),
            DspConfig.fromJson(o.getJSONObject("config")), builtIn = false
        )
    }
}

/** Mengubah konfigurasi (EQ + makro Bass/Treble/Vocal/Clarity) menjadi gain dB per frekuensi. */
object Curve {
    val REF_HZ = floatArrayOf(60f, 230f, 910f, 3600f, 14000f)

    private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()
    private fun bell(f: Float, center: Float, octaves: Float): Float {
        val d = log2(f / center) / octaves
        return exp(-d * d)
    }
    private fun shelfHigh(f: Float, fc: Float) = 1f / (1f + exp(-3f * log2(f / fc)))

    fun gainDb(c: DspConfig, hz: Float): Float {
        val base = when {
            hz <= REF_HZ.first() -> c.eq.first()
            hz >= REF_HZ.last() -> c.eq.last()
            else -> {
                val i = REF_HZ.indexOfLast { it <= hz }
                val t = log2(hz / REF_HZ[i]) / log2(REF_HZ[i + 1] / REF_HZ[i])
                c.eq[i] + (c.eq[i + 1] - c.eq[i]) * t
            }
        }
        return base +
            c.bass * (1f - shelfHigh(hz, 150f)) +
            c.treble * shelfHigh(hz, 6000f) +
            c.vocal * bell(hz, 2000f, 1.2f) +
            c.clarity * bell(hz, 5000f, 1.0f)
    }
}

object Presets {
    private fun c(
        eq: List<Float>, bass: Float = 0f, treble: Float = 0f, vocal: Float = 0f,
        clarity: Float = 0f, width: Float = 0f, comp: Float = 0f, lim: Float = -1f
    ) = DspConfig(eq, bass, treble, vocal, clarity, width, comp, true, lim)

    val all = listOf(
        Preset("ultra_clarity", "Ultra Clarity", "Detail & kejernihan naik",
            c(listOf(-1f, -1f, 0f, 2f, 3f), treble = 1f, vocal = 1f, clarity = 3f, comp = 0.2f)),
        Preset("vocal_boost", "Vocal Boost", "Suara & dialog lebih jelas",
            c(listOf(-2f, -1f, 2f, 3f, 1f), vocal = 3f, comp = 0.3f)),
        Preset("studio", "Studio Quality", "Seimbang & natural",
            c(listOf(0.5f, 0f, 0f, 0.5f, 1f), comp = 0.1f)),
        Preset("deep_bass", "Deep Bass", "Bass terasa, vokal tetap jelas",
            c(listOf(4f, 2f, -1f, 1f, 0f), bass = 2f, vocal = 1f, lim = -2f)),
        Preset("crystal_treble", "Crystal Treble", "Detail frekuensi tinggi",
            c(listOf(-1f, 0f, 0f, 2f, 4f), treble = 3f, clarity = 1f)),
        Preset("spatial", "Spatial Sound", "Ruang stereo lebih luas",
            c(listOf(0f, 0f, 0f, 0f, 1f), width = 0.7f)),
        Preset("gaming", "Gaming", "Detail audio game lebih jelas",
            c(listOf(1f, 0f, -1f, 3f, 2f), clarity = 2f, width = 0.4f, comp = 0.3f)),
        Preset("movie", "Movie", "Dialog jelas, suasana luas",
            c(listOf(2f, 1f, 0f, 2f, 1f), vocal = 3f, width = 0.5f, comp = 0.4f)),
        Preset("tws", "TWS Optimized", "Profil untuk earbuds TWS",
            c(listOf(3f, 1f, 0f, 0f, -1f), bass = 1f, width = 0.2f, lim = -2f)),
        Preset("balanced", "Balanced", "Natural untuk sehari-hari",
            c(List(5) { 0f }))
    )

    val balanced: Preset = all.last()
}
