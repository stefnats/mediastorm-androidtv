package com.strmr.tv.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/** Observe only accepted decoded input; every audio operation still belongs to the original sink. */
@OptIn(UnstableApi::class)
internal class DecodedAudioSink(
    delegate: AudioSink,
    private val observation: DecodedAudioObservation,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : ForwardingAudioSink(delegate) {
    private var tunneling = false
    private var offload = false
    private var configured: Format? = null

    override fun configure(format: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        configured = null
        observation.configure(0, 0, null)
        super.configure(format, specifiedBufferSize, outputChannels)
        configured = format
        observeFormat()
    }

    private fun observeFormat() {
        val format = configured ?: return
        val encoding = if (format.sampleMimeType != MimeTypes.AUDIO_RAW || tunneling || offload) null else when (format.pcmEncoding) {
            C.ENCODING_PCM_16BIT -> ObservedPcmEncoding.PCM16
            C.ENCODING_PCM_FLOAT -> ObservedPcmEncoding.FLOAT32
            else -> null
        }
        observation.configure(format.sampleRate, format.channelCount, encoding)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val start = buffer.position()
        val token = observation.token()
        val accepted = try { super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount) }
        catch (error: Exception) { failed(error) }
        observation.consumed(buffer, start, buffer.position(), token, nowMs())
        return accepted
    }

    private fun failed(error: Exception): Nothing {
        configured = null
        observation.configure(0, 0, null)
        throw error
    }

    override fun flush() { observation.restart(); try { super.flush() } catch (error: Exception) { failed(error) } }
    override fun handleDiscontinuity() { observation.restart(); try { super.handleDiscontinuity() } catch (error: Exception) { failed(error) } }
    override fun playToEndOfStream() { observation.restart(); try { super.playToEndOfStream() } catch (error: Exception) { failed(error) } }
    override fun reset() { configured = null; observation.configure(0, 0, null); super.reset() }
    override fun release() { configured = null; observation.close(); super.release() }
    override fun enableTunnelingV21() {
        tunneling = true; observation.configure(0, 0, null)
        try { super.enableTunnelingV21() } catch (error: Exception) { failed(error) }
    }
    override fun disableTunneling() {
        tunneling = false; observation.configure(0, 0, null)
        try { super.disableTunneling() } catch (error: Exception) { failed(error) }
        observeFormat()
    }
    override fun setOffloadMode(offloadMode: Int) {
        offload = offloadMode != AudioSink.OFFLOAD_MODE_DISABLED
        observation.configure(0, 0, null)
        try { super.setOffloadMode(offloadMode) } catch (error: Exception) { failed(error) }
        observeFormat()
    }
}
