package com.gymrest

import kotlin.math.*

/**
 * Lightweight exercise classifier using sensor fusion.
 * No ML framework required — rule-based with confidence scores.
 *
 * On a real Watch 4 you could replace this with a TFLite model
 * (.tflite asset) loaded via org.tensorflow:tensorflow-lite.
 */
object ExerciseClassifier {

    data class Classification(
        val exercise: String,
        val confidence: Int,          // 0–100
        val category: Category
    )

    enum class Category { PUSH, PULL, LEGS, CORE, CARDIO, UNKNOWN }

    private data class Rule(
        val name: String,
        val category: Category,
        val accelMin: Float,
        val accelMax: Float,
        val gyroMin: Float,
        val gyroMax: Float,
        val cadenceMin: Float,        // reps/min estimate
        val cadenceMax: Float,
        val baseConfidence: Int
    )

    private val RULES = listOf(
        Rule("Supino Reto",      Category.PUSH,  1.6f, 3.5f, 0.5f, 2.5f, 15f, 40f, 88),
        Rule("Supino Inclinado", Category.PUSH,  1.4f, 3.0f, 1.0f, 3.0f, 15f, 35f, 82),
        Rule("Desenvolvimento",  Category.PUSH,  1.2f, 2.8f, 1.5f, 4.0f, 12f, 35f, 84),
        Rule("Rosca Bíceps",     Category.PULL,  1.5f, 4.0f, 2.5f, 6.0f, 15f, 45f, 90),
        Rule("Remada",           Category.PULL,  1.8f, 4.5f, 1.0f, 3.5f, 12f, 35f, 85),
        Rule("Puxada",           Category.PULL,  1.5f, 3.5f, 0.8f, 2.5f, 10f, 30f, 83),
        Rule("Agachamento",      Category.LEGS,  2.0f, 5.0f, 0.3f, 1.8f, 10f, 30f, 89),
        Rule("Leg Press",        Category.LEGS,  1.8f, 4.0f, 0.2f, 1.2f, 10f, 28f, 86),
        Rule("Cadeira Extensora",Category.LEGS,  1.0f, 2.5f, 3.0f, 7.0f, 15f, 40f, 80),
        Rule("Panturrilha",      Category.LEGS,  0.8f, 2.0f, 0.5f, 2.0f, 20f, 60f, 78),
        Rule("Tríceps Polia",    Category.PUSH,  1.2f, 3.0f, 2.0f, 5.0f, 18f, 50f, 83),
        Rule("Elevação Lateral", Category.PUSH,  0.8f, 2.2f, 2.5f, 6.5f, 15f, 40f, 81),
        Rule("Prancha",          Category.CORE,  0.1f, 0.6f, 0.1f, 0.8f, 0f,  0f,  75),
        Rule("Corrida",          Category.CARDIO,2.5f, 6.0f, 0.5f, 2.5f, 60f,180f, 91),
    )

    /**
     * Classify from rolling sensor buffers.
     * @param accelBuf  LinearAcceleration magnitudes (m/s²)
     * @param gyroBuf   Gyroscope magnitudes (rad/s)
     * @param cadence   Estimated reps/min from zero-crossing analysis
     */
    fun classify(
        accelBuf: FloatArray,
        gyroBuf:  FloatArray,
        cadence:  Float = 0f
    ): Classification {
        val accelMean = accelBuf.average().toFloat()
        val accelStd  = accelBuf.std()
        val gyroMean  = gyroBuf.average().toFloat()

        var bestRule: Rule? = null
        var bestScore = 0f

        for (rule in RULES) {
            val accelScore  = gaussScore(accelMean, rule.accelMin, rule.accelMax)
            val gyroScore   = gaussScore(gyroMean,  rule.gyroMin,  rule.gyroMax)
            val cadenceScore = if (rule.cadenceMax == 0f) 1f
                               else gaussScore(cadence, rule.cadenceMin, rule.cadenceMax)

            val score = accelScore * 0.45f + gyroScore * 0.35f + cadenceScore * 0.20f
            if (score > bestScore) {
                bestScore = score
                bestRule  = rule
            }
        }

        if (bestRule == null || bestScore < 0.15f) {
            return Classification("Exercício", 50, Category.UNKNOWN)
        }

        val confidence = (bestRule.baseConfidence * bestScore).toInt().coerceIn(40, 99)
        return Classification(bestRule.name, confidence, bestRule.category)
    }

    /** Estimate repetition cadence (reps/min) from acceleration buffer. */
    fun estimateCadence(buf: FloatArray, samplesPerSec: Float): Float {
        if (buf.size < 4) return 0f
        val mean = buf.average().toFloat()
        var crossings = 0
        for (i in 1 until buf.size) {
            if ((buf[i-1] - mean) * (buf[i] - mean) < 0) crossings++
        }
        val seconds = buf.size / samplesPerSec
        return (crossings / 2f) / seconds * 60f
    }

    // ── Math helpers ──────────────────────────────────────────────────────────

    private fun gaussScore(v: Float, lo: Float, hi: Float): Float {
        val mid   = (lo + hi) / 2f
        val sigma = (hi - lo) / 4f
        if (sigma == 0f) return if (v == mid) 1f else 0f
        return exp(-0.5f * ((v - mid) / sigma).pow(2))
    }

    private fun FloatArray.std(): Float {
        val mean = average().toFloat()
        return sqrt(map { (it - mean).pow(2) }.average().toFloat())
    }
}
