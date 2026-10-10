package kotllm.util

import kotlin.jvm.Volatile
import kotlin.math.ceil
import kotlin.math.max

/**
 * Estimates prompt tokens before a request is sent, and calibrates itself.
 *
 * Rate limiting has to charge input tokens *before* the call, but the true
 * `prompt_tokens` only comes back with the response. Rather than hard-coding
 * the chars-divided-by-four folklore -- wrong by a wide margin for code, JSON
 * or CJK -- the ratio is learned from the endpoint's own accounting.
 *
 * That makes the first request of a session the least accurate, which is
 * exactly the one least likely to be near a limit: windows fill over a long
 * conversation, by which point the ratio has converged.
 *
 * Deliberately not a real tokenizer: that would mean bundling vocabulary files
 * per model family, and a few percent is ample for deciding whether to pause.
 */
class TokenEstimator(initialCharsPerToken: Double = 3.6) {

    /**
     * Fixed cost per message for role markers and chat-template scaffolding.
     * Approximate and provider-specific; folded into the learned ratio as
     * samples accumulate.
     */
    private val PerMessageOverhead = 4

    /**
     * Weight of each new observation. High enough to converge within a few
     * turns, low enough that one unusual message does not swing it.
     */
    private val SmoothingFactor = 0.3

    @Volatile
    private var ratio = initialCharsPerToken

    @Volatile
    private var samples = 0

    val charsPerToken: Double
        get() = ratio

    val sampleCount: Int
        get() = samples

    val isCalibrated: Boolean
        get() = samples > 0

    fun estimate(chars: Int, messages: Int): Int =
        max(1, ceil(chars / ratio).toInt() + messages * PerMessageOverhead)

    /**
     * Folds one observed (characters, messages) -> prompt_tokens pair into the
     * ratio. Implausible observations are ignored rather than allowed to poison
     * the estimate.
     */
    fun calibrate(chars: Int, messages: Int, actualPromptTokens: Int) {
        val contentTokens = actualPromptTokens - messages * PerMessageOverhead
        if (chars <= 0 || contentTokens <= 0) return

        val observed = chars.toDouble() / contentTokens
        // A ratio outside this range means the input was not natural text at all
        // (or the endpoint counts something we are not modelling); ignore it.
        if (observed < 0.5 || observed > 20.0) return

        ratio =
            if (samples == 0) observed
            else (1 - SmoothingFactor) * ratio + SmoothingFactor * observed
        samples += 1
    }
}
