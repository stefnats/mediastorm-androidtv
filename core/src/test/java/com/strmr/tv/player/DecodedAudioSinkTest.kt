package com.strmr.tv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class DecodedAudioSinkTest {
    private class Delegate {
        var consume = Int.MAX_VALUE
        var error: RuntimeException? = null
        var errorAfterConsumption = false
        var failingMethod: String? = null
        var during: (() -> Unit)? = null
        val calls = mutableListOf<String>()
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, args ->
            calls += method.name
            if (method.name == failingMethod) throw requireNotNull(error)
            if (method.name == "handleBuffer") {
                error?.takeIf { !errorAfterConsumption }?.let { throw it }
                val buffer = args!![0] as ByteBuffer
                buffer.position(buffer.position() + minOf(consume, buffer.remaining()))
                during?.invoke()
                error?.let { throw it }
                !buffer.hasRemaining()
            } else when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                else -> null
            }
        } as AudioSink
    }

    private fun pcm(encoding: Int = C.ENCODING_PCM_16BIT) = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setSampleRate(8_000).setChannelCount(2).setPcmEncoding(encoding).build()

    @Test fun actualForwardingAdapterCountsOnlyNewlyConsumedPartialAndRetriedRanges() {
        val delegate = Delegate()
        val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 100 }
        sink.configure(pcm(), 0, null)
        val bytes = byteArrayOf(9, 0, 0, 0, 0, 1, 0, 0, 0, 9)
        val before = bytes.clone()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).apply { position(1); limit(9) }
        delegate.consume = 3
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(0L, meter.snapshot().frames)
        delegate.consume = 1
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1L, meter.snapshot().zeroFrames)
        delegate.consume = 0
        assertFalse(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1L, meter.snapshot().frames)
        delegate.consume = 4
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertEquals(2L, meter.snapshot().frames)
        assertEquals(0L, meter.snapshot().zeroFrames)
        assertEquals(9, buffer.position()); assertEquals(9, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order()); assertArrayEquals(before, bytes)
        assertEquals(4, delegate.calls.count { it == "handleBuffer" })
    }

    @Test fun configuredFloatInputIsObservedWithoutAddingAProcessorOrConvertingCallerData() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(C.ENCODING_PCM_FLOAT), 1024, intArrayOf(0, 1))
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(-0f).putFloat(0f).apply { flip() }
        assertTrue(sink.handleBuffer(buffer, 0, 1))
        assertEquals(1L, meter.snapshot().zeroFrames)
        assertEquals(listOf("configure", "handleBuffer"), delegate.calls)
    }

    @Test fun encodedUnsupportedAndInvalidFormatsStayUnknownAndStillReachDelegate() {
        for (format in listOf(pcm(C.ENCODING_PCM_24BIT), pcm().buildUpon().setChannelCount(9).build(),
                pcm().buildUpon().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())) {
            val delegate = Delegate(); val meter = DecodedAudioObservation()
            val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
            sink.configure(format, 0, null)
            assertTrue(sink.handleBuffer(ByteBuffer.allocate(16), 0, 1))
            assertEquals(0, meter.snapshot().sampleRate)
            assertEquals(listOf("configure", "handleBuffer"), delegate.calls)
        }
    }

    @Test fun delegateErrorsPropagateWithoutPublishingAcceptedSamples() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(), 0, null)
        val expected = IllegalStateException("synthetic output failure")
        delegate.error = expected
        try { sink.handleBuffer(ByteBuffer.allocate(4), 0, 1); fail("delegate failure disappeared") }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(0L, meter.snapshot().frames)
        assertNull(meter.snapshot().lastBufferMs)
    }

    @Test fun partialDelegateFailureRetiresOldEvidenceAndPreservesOriginalExceptionAndBufferState() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(), 0, null)
        sink.handleBuffer(ByteBuffer.allocate(8), 0, 1)
        assertEquals(2L, meter.snapshot().zeroFrames)
        val original = byteArrayOf(0, 0, 0, 0, 9, 9, 9, 9)
        val buffer = ByteBuffer.wrap(original).order(ByteOrder.BIG_ENDIAN)
        val before = original.clone()
        val expected = IllegalStateException("synthetic partial output failure")
        delegate.error = expected; delegate.errorAfterConsumption = true; delegate.consume = 2
        try { sink.handleBuffer(buffer, 0, 1); fail("partial failure disappeared") }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        assertEquals(2, buffer.position()); assertEquals(8, buffer.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, buffer.order()); assertArrayEquals(before, original)
        assertEquals(0, meter.snapshot().sampleRate); assertEquals(0L, meter.snapshot().zeroFrames)
        assertNull(meter.snapshot().lastBufferMs)
    }

    @Test fun unsuccessfulConfigureOrModeTransitionCannotRestoreAnOldFormat() {
        for (method in listOf("configure", "enableTunnelingV21", "disableTunneling", "setOffloadMode", "flush", "handleDiscontinuity", "playToEndOfStream")) {
            val delegate = Delegate(); val meter = DecodedAudioObservation()
            val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
            sink.configure(pcm(), 0, null)
            val expected = IllegalStateException("synthetic transition failure")
            delegate.error = expected; delegate.failingMethod = method
            try {
                when (method) {
                    "configure" -> sink.configure(pcm(), 0, null)
                    "enableTunnelingV21" -> sink.enableTunnelingV21()
                    "disableTunneling" -> sink.disableTunneling()
                    "setOffloadMode" -> sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
                    "flush" -> sink.flush()
                    "handleDiscontinuity" -> sink.handleDiscontinuity()
                    else -> sink.playToEndOfStream()
                }
                fail("transition failure disappeared")
            } catch (actual: IllegalStateException) { assertSame(expected, actual) }
            delegate.error = null; delegate.failingMethod = null
            sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
            sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
            assertEquals(0, meter.snapshot().sampleRate)
        }
    }

    @Test fun lateConsumedRangeCannotCrossAnObservationResetDuringTheDelegateCall() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(), 0, null)
        delegate.during = { meter.restart() }
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(0L, meter.snapshot().frames)
        delegate.during = null
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(1L, meter.snapshot().frames)
    }

    @Test fun flushDiscontinuityAndEndRetireEvidenceWhileResetAndReleaseRetireTheFormat() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(), 0, null)
        for (reset in listOf<() -> Unit>({ sink.flush() }, { sink.handleDiscontinuity() }, { sink.playToEndOfStream() })) {
            sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
            val generation = meter.snapshot().generation
            reset()
            assertTrue(meter.snapshot().generation > generation)
            assertEquals(0L, meter.snapshot().frames)
        }
        sink.reset(); assertEquals(0, meter.snapshot().sampleRate)
        sink.configure(pcm(), 0, null)
        sink.release(); sink.configure(pcm(), 0, null)
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(0, meter.snapshot().sampleRate)
    }

    @Test fun tunnelingAndOffloadAreUnknownAndNeverForceADifferentOutputPath() {
        val delegate = Delegate(); val meter = DecodedAudioObservation()
        val sink = DecodedAudioSink(delegate.sink, meter) { 10 }
        sink.configure(pcm(), 0, null)
        sink.enableTunnelingV21()
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(0, meter.snapshot().sampleRate)
        sink.disableTunneling()
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(1L, meter.snapshot().frames)
        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_ENABLED_GAPLESS_NOT_REQUIRED)
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(0, meter.snapshot().sampleRate)
        sink.setOffloadMode(AudioSink.OFFLOAD_MODE_DISABLED)
        sink.handleBuffer(ByteBuffer.allocate(4), 0, 1)
        assertEquals(1L, meter.snapshot().frames)
        assertTrue(delegate.calls.containsAll(listOf("enableTunnelingV21", "disableTunneling", "setOffloadMode")))
    }
}
