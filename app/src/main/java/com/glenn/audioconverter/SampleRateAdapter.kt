package com.glenn.audioconverter

/**
 * Adapts sample rates to AAC-legal values.
 *
 * AAC legal rates: 8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000
 */
object SampleRateAdapter {

    val LEGAL_SAMPLE_RATES = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    data class AdaptResult(
        val targetRate: Int,
        val logNote: String? = null
    )

    /**
     * Determine target sample rate.
     *
     * @param originalRate  detected sample rate from source
     * @param userChoice    "原始" or a fixed rate string like "44100"
     */
    fun adapt(originalRate: Int, userChoice: String): AdaptResult {
        // Fixed rate selected by user
        if (userChoice != "原始") {
            val fixed = userChoice.toIntOrNull()
            if (fixed != null && fixed in LEGAL_SAMPLE_RATES) {
                return AdaptResult(fixed)
            }
            // Invalid choice → fall back to original adaptation
        }

        // "原始" mode — adapt to nearest legal rate
        if (originalRate in LEGAL_SAMPLE_RATES) {
            return AdaptResult(originalRate)
        }

        // Find the closest legal rate ≤ originalRate
        val lower = LEGAL_SAMPLE_RATES.lastOrNull { it <= originalRate }
        if (lower != null) {
            return AdaptResult(
                targetRate = lower,
                logNote = "原始采样率 ${originalRate}Hz → 适配为 ${lower}Hz"
            )
        }

        // No lower rate exists — use the smallest legal rate
        val fallback = LEGAL_SAMPLE_RATES.first()
        return AdaptResult(
            targetRate = fallback,
            logNote = "原始采样率 ${originalRate}Hz → 适配为 ${fallback}Hz (超出合法范围)"
        )
    }
}
