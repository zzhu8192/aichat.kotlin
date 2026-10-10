package kotllm.util

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus

class TokenEstimatorTest {

    @Test fun `estimates from characters plus per-message overhead`() {
        val e = TokenEstimator(initialCharsPerToken = 4.0)
        // 400 chars / 4.0 = 100, plus 2 messages * 4 overhead.
        e.estimate(400, 2) shouldBe 108
    }

    @Test fun `never estimates zero, so a reservation always charges something`() {
        TokenEstimator().estimate(0, 0) shouldBe 1
    }

    @Test fun `starts uncalibrated and reports the fact`() {
        val e = TokenEstimator()
        e.isCalibrated shouldBe false
        e.sampleCount shouldBe 0
    }

    @Test fun `the first observation replaces the prior outright`() {
        val e = TokenEstimator(initialCharsPerToken = 4.0)
        // 1000 chars, 1 message: content tokens = 500 - 4 = 496 -> ~2.016
        e.calibrate(1000, 1, 500)

        e.isCalibrated shouldBe true
        e.charsPerToken shouldBe (2.016 plusOrMinus 0.01)
    }

    @Test fun `converges toward the observed ratio over several turns`() {
        val e = TokenEstimator(initialCharsPerToken = 4.0)
        (1..10).forEach { _ -> e.calibrate(1000, 0, 500) } // a true ratio of 2.0

        e.charsPerToken shouldBe (2.0 plusOrMinus 0.05)
        e.sampleCount shouldBe 10
    }

    @Test fun `smooths rather than jumping to a single outlier`() {
        val e = TokenEstimator(initialCharsPerToken = 4.0)
        (1..10).forEach { _ -> e.calibrate(1000, 0, 250) } // settles at 4.0
        val before = e.charsPerToken

        e.calibrate(1000, 0, 1000) // one odd turn implying 1.0
        // Moves toward the outlier but is not captured by it.
        e.charsPerToken shouldBeLessThan before
        e.charsPerToken shouldBeGreaterThan 2.0
    }

    @Test fun `ignores implausible observations instead of poisoning the ratio`() {
        val e = TokenEstimator(initialCharsPerToken = 4.0)

        e.calibrate(10, 1, 4)      // content tokens <= 0
        e.calibrate(0, 0, 100)     // no characters
        e.calibrate(100_000, 0, 1) // absurd ratio, far outside the sane band

        e.isCalibrated shouldBe false
        e.charsPerToken shouldBe 4.0
    }

    @Test fun `a calibrated estimator tracks real usage more closely than the default`() {
        val e = TokenEstimator(initialCharsPerToken = 10.0) // deliberately wrong
        val trueRatio = 3.5

        fun actual(chars: Int) = Math.round(chars / trueRatio).toInt()

        (1..8).forEach { _ -> e.calibrate(3500, 0, actual(3500)) }

        val estimated = e.estimate(7000, 0)
        val truth = actual(7000)
        (Math.abs(estimated - truth).toDouble() / truth) shouldBeLessThan 0.1
    }
}
