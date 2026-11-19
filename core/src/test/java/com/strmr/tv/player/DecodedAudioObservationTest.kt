package com.strmr.tv.player

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DecodedAudioObservationTest {
    private fun meter(channels: Int = 2, encoding: ObservedPcmEncoding = ObservedPcmEncoding.PCM16) =
        DecodedAudioObservation().apply { configure(8_000, channels, encoding) }

    private fun DecodedAudioObservation.read(bytes: ByteArray, at: Long = 0) {
        consumed(ByteBuffer.wrap(bytes), 0, bytes.size, token(), at)
    }

    @Test fun countsWholeAllChannelFramesAcrossPartialSampleAndChannelWrites() {
        val meter = meter()
        meter.read(byteArrayOf(0))
        assertEquals(0L, meter.snapshot().frames)
        meter.read(byteArrayOf(0, 0))
        assertEquals(0L, meter.snapshot().frames)
        meter.read(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0, 0))
        assertEquals(3L, meter.snapshot().frames)
        assertEquals(1L, meter.snapshot().zeroFrames)
        assertEquals(1L, meter.snapshot().breaks)
    }

    @Test fun oneActiveSurroundChannelBreaksSilenceAndEveryOtherChannelStaysInsufficient() {
        val meter = meter(8)
        meter.read(ByteArray(32))
        assertEquals(2L, meter.snapshot().zeroFrames)
        val frame = ByteArray(16).also { it[15] = 1 }
        meter.read(frame)
        assertEquals(0L, meter.snapshot().zeroFrames)
        meter.read(ByteArray(16))
        assertEquals(1L, meter.snapshot().zeroFrames)
    }

    @Test fun floatPositiveAndNegativeZeroCountButQuietNonzeroDoesNot() {
        val meter = meter(1, ObservedPcmEncoding.FLOAT32)
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(0f).putFloat(-0f).putFloat(Float.MIN_VALUE).array()
        meter.read(bytes)
        assertEquals(3L, meter.snapshot().frames)
        assertEquals(0L, meter.snapshot().zeroFrames)
        assertEquals(1L, meter.snapshot().breaks)
    }

    @Test fun nanInfinityMalformedRangeAndBudgetBecomeUnknownUntilReconfigured() {
        for (bytes in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).map {
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(it).array()
        }) {
            val meter = meter(1, ObservedPcmEncoding.FLOAT32)
            meter.read(bytes)
            assertEquals(0, meter.snapshot().sampleRate)
        }
        val meter = meter()
        meter.consumed(ByteBuffer.allocate(4), -1, 4, meter.token(), 0)
        assertEquals(0, meter.snapshot().sampleRate)
        meter.configure(8_000, 2, ObservedPcmEncoding.PCM16)
        meter.read(ByteArray(DecodedAudioObservation.MAX_CALLBACK_BYTES + 1))
        assertEquals(0, meter.snapshot().sampleRate)
    }

    @Test fun unknownGeometryCloseAndOldGenerationCannotPublishFrames() {
        val meter = meter()
        val old = meter.token()
        meter.restart()
        meter.consumed(ByteBuffer.allocate(4), 0, 4, old, 0)
        assertEquals(0L, meter.snapshot().frames)
        for ((rate, count) in listOf(0 to 2, 7_999 to 2, 192_001 to 2, 8_000 to 0, 8_000 to 9)) {
            meter.configure(rate, count, ObservedPcmEncoding.PCM16)
            meter.read(ByteArray(4))
            assertEquals(0, meter.snapshot().sampleRate)
        }
        meter.close()
        meter.configure(8_000, 2, ObservedPcmEncoding.PCM16)
        meter.read(ByteArray(4))
        assertEquals(0, meter.snapshot().sampleRate)
    }

    @Test fun consumedRangeDoesNotTouchCallerBytesPositionLimitOrByteOrder() {
        val meter = meter(1)
        val buffer = ByteBuffer.wrap(byteArrayOf(7, 0, 0, 8)).order(ByteOrder.BIG_ENDIAN)
        buffer.position(3); buffer.limit(3)
        val before = buffer.array().clone()
        meter.consumed(buffer, 1, 3, meter.token(), 0)
        assertEquals(1L, meter.snapshot().zeroFrames)
        assertEquals(3, buffer.position()); assertEquals(3, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order())
        assertArrayEquals(before, buffer.array())
    }
}
