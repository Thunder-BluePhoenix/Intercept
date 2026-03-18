package com.example.intercept

object AudioMixer {
    /**
     * Mixes two 16-bit PCM samples into one 16-bit PCM sample to avoid clipping/overflow.
     */
    fun mixPCM(micSample: Short, speakerSample: Short): Short {
        val mixed = micSample.toFloat() + speakerSample.toFloat()
        return when {
            mixed > Short.MAX_VALUE -> Short.MAX_VALUE
            mixed < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> mixed.toInt().toShort()
        }
    }
}
