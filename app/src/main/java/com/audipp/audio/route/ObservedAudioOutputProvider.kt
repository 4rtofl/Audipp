package com.audipp.audio.route

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider

@UnstableApi
internal class ObservedAudioOutputProvider(
    context: Context,
    private val routes: OutputRouteMonitor,
) : ForwardingAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        val track = (output as? AudioTrackAudioOutput)?.audioTrack
        return object : ForwardingAudioOutput(output) {
            private val volumeLock = Any()
            private var attempt: Any? = null
            private var requestedVolume = 1f
            private var safetyGain = 0f

            override fun play() {
                val probe = Any()
                synchronized(volumeLock) {
                    attempt = probe
                    safetyGain = 0f
                    output.setVolume(0f)
                }
                super.play()
                routes.onPlaying(probe, track, { synchronized(volumeLock) { attempt === probe } }) { gain ->
                    synchronized(volumeLock) {
                        if (attempt === probe) {
                            safetyGain = gain
                            output.setVolume(requestedVolume * safetyGain)
                        }
                    }
                }
            }

            override fun setVolume(volume: Float) {
                synchronized(volumeLock) {
                    requestedVolume = volume
                    output.setVolume(requestedVolume * safetyGain)
                }
            }

            override fun pause() {
                endObservation()
                super.pause()
            }

            override fun stop() {
                // Media3 also calls stop() to drain the end of a stream. Keep that audio revocable
                // until release rather than muting the final queued frames of every item.
                super.stop()
                synchronized(volumeLock) { attempt?.let(routes::onDraining) }
            }

            override fun release() {
                endObservation()
                super.release()
            }

            private fun endObservation() {
                synchronized(volumeLock) {
                    attempt?.let(routes::onInactive)
                    attempt = null
                    safetyGain = 0f
                    output.setVolume(0f)
                }
            }
        }
    }
}
