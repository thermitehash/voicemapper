package com.thrivestorm2.voicemapper.core.dsp

/** Growable FIFO of samples with cheap front removal (Swift's `[Float]` + `removeFirst`). */
class SampleBuffer(initialCapacity: Int = 16_384) {
    var data = FloatArray(initialCapacity)
        private set
    /** Index of the first live sample in [data]. */
    var start = 0
        private set
    var count = 0
        private set

    fun append(x: FloatArray, offset: Int = 0, length: Int = x.size - offset) {
        if (start + count + length > data.size) {
            // Compact, then grow if still short.
            System.arraycopy(data, start, data, 0, count)
            start = 0
            if (count + length > data.size) data = data.copyOf(maxOf(data.size * 2, count + length))
        }
        System.arraycopy(x, offset, data, start + count, length)
        count += length
    }

    fun removeFirst(n: Int) {
        val k = minOf(n, count)
        start += k
        count -= k
        if (count == 0) start = 0
    }
}
