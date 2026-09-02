package com.example.camera

import kotlin.math.*

/**
 * Real-time Digital Signal Processing (DSP) Voice Isolation & Traffic Noise Filter.
 * 
 * Specifically filters out:
 * 1. Low-frequency vehicle engine rumble, motorcycle exhaust thumping, wind and road drone (< 300 Hz) via dual Cascaded High-Pass Biquad Filters.
 * 2. High-frequency tire hiss and ambient screech (> 3400 Hz) via Low-Pass Biquad Filter.
 * 3. Human speech formant boost (1800 Hz) for clear voice intelligibility.
 * 4. Adaptive Spectral Noise Gate / Voice Activity Detector (VAD) with smooth attack/release envelope.
 * 5. Soft-knee speech compressor & limiter to prevent clipping while amplifying quiet speech.
 */
class VoiceIsolationDsp(private val sampleRate: Int = 16000) {

    // --- Biquad Filter Class ---
    private class Biquad {
        var b0 = 1.0
        var b1 = 0.0
        var b2 = 0.0
        var a1 = 0.0
        var a2 = 0.0

        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0

        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0.0
            x2 = 0.0
            y1 = 0.0
            y2 = 0.0
        }

        fun setHighPass(sampleRate: Int, cutoffFreq: Double, q: Double = 0.7071) {
            val omega = 2.0 * Math.PI * cutoffFreq / sampleRate
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosOmega) / 2.0) / a0
            b1 = (-(1.0 + cosOmega)) / a0
            b2 = ((1.0 + cosOmega) / 2.0) / a0
            a1 = (-2.0 * cosOmega) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setLowPass(sampleRate: Int, cutoffFreq: Double, q: Double = 0.7071) {
            val omega = 2.0 * Math.PI * cutoffFreq / sampleRate
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0 * q)

            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosOmega) / 2.0) / a0
            b1 = (1.0 - cosOmega) / a0
            b2 = ((1.0 - cosOmega) / 2.0) / a0
            a1 = (-2.0 * cosOmega) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun setPeakingEq(sampleRate: Int, centerFreq: Double, q: Double = 1.2, gainDb: Double = 5.0) {
            val a = 10.0.pow(gainDb / 40.0)
            val omega = 2.0 * Math.PI * centerFreq / sampleRate
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / (2.0 * q)

            val a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosOmega) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosOmega) / a0
            a2 = (1.0 - alpha / a) / a0
        }
    }

    // Cascaded High-pass filters at 300Hz (cuts engine rumble and motorcycle exhaust heavily: 24dB/octave)
    private val hpf1 = Biquad().apply { setHighPass(sampleRate, 300.0, 0.7071) }
    private val hpf2 = Biquad().apply { setHighPass(sampleRate, 300.0, 0.7071) }

    // Low-pass filter at 3400Hz (cuts high screech and road tire hiss)
    private val lpf = Biquad().apply { setLowPass(sampleRate, 3400.0, 0.7071) }

    // Speech formant enhancer at 1800Hz (boosts human vocal clarity)
    private val formantEq = Biquad().apply { setPeakingEq(sampleRate, 1800.0, 1.2, 5.0) }

    // Adaptive noise gate state
    private var noiseFloor = 180.0
    private var currentGateGain = 0.1
    private var holdCounter = 0
    private val HOLD_SAMPLES = (sampleRate * 0.25).toInt() // 250ms hold time for natural speech

    /**
     * Process a raw 16-bit PCM byte array in place.
     */
    @Synchronized
    fun processPcm16(pcmBytes: ByteArray, offset: Int = 0, length: Int = pcmBytes.size) {
        val sampleCount = length / 2
        if (sampleCount == 0) return

        // 1. Convert to doubles and compute RMS energy for adaptive gate
        var sumSquares = 0.0
        val samples = DoubleArray(sampleCount)

        for (i in 0 until sampleCount) {
            val byteIdx = offset + (i * 2)
            val low = pcmBytes[byteIdx].toInt() and 0xFF
            val high = pcmBytes[byteIdx + 1].toInt()
            val sampleVal = ((high shl 8) or low).toShort().toDouble()
            samples[i] = sampleVal
            sumSquares += sampleVal * sampleVal
        }

        val rms = sqrt(sumSquares / sampleCount.coerceAtLeast(1))

        // Update noise floor tracking with slow adaptation
        if (rms < noiseFloor * 1.5) {
            noiseFloor = noiseFloor * 0.99 + rms * 0.01
        } else {
            noiseFloor = noiseFloor * 0.999 + rms * 0.001
        }
        noiseFloor = noiseFloor.coerceIn(80.0, 1200.0)

        // Determine if voice is active in this block
        val speechThreshold = (noiseFloor * 2.2).coerceAtLeast(220.0)
        val isVoiceActive = rms > speechThreshold

        val targetGateGain = if (isVoiceActive) {
            holdCounter = HOLD_SAMPLES
            1.25 // Clear boost for human voice
        } else if (holdCounter > 0) {
            holdCounter -= sampleCount
            1.0 // Hold open during brief pauses between words
        } else {
            0.06 // Cut traffic / motorcycle idling by ~24dB
        }

        // 2. Apply filtering and smoothed dynamic gain to each sample
        for (i in 0 until sampleCount) {
            var s = samples[i]

            // Apply steep low-cut / HPF (strips motorcycle/vehicle engine rumble)
            s = hpf1.process(s)
            s = hpf2.process(s)

            // Apply high-cut / LPF (strips road tire friction hiss)
            s = lpf.process(s)

            // Enhance speech clarity formant
            s = formantEq.process(s)

            // Smooth gain envelope (fast attack 5ms, smooth release 40ms)
            val attackSmoothing = if (targetGateGain > currentGateGain) 0.15 else 0.02
            currentGateGain += (targetGateGain - currentGateGain) * attackSmoothing

            s *= currentGateGain

            // Soft-knee limiter / speech compressor
            val limited = softLimit(s)
            val shortVal = limited.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            // Write back to PCM byte array (Little Endian)
            val byteIdx = offset + (i * 2)
            pcmBytes[byteIdx] = (shortVal.toInt() and 0xFF).toByte()
            pcmBytes[byteIdx + 1] = ((shortVal.toInt() shr 8) and 0xFF).toByte()
        }
    }

    private fun softLimit(x: Double): Double {
        val threshold = 26000.0
        val maxVal = 32000.0
        val absX = abs(x)
        if (absX <= threshold) return x
        val excess = absX - threshold
        val compressed = threshold + (maxVal - threshold) * tanh(excess / (maxVal - threshold))
        return if (x > 0) compressed else -compressed
    }

    fun reset() {
        hpf1.reset()
        hpf2.reset()
        lpf.reset()
        formantEq.reset()
        noiseFloor = 180.0
        currentGateGain = 0.1
        holdCounter = 0
    }
}
