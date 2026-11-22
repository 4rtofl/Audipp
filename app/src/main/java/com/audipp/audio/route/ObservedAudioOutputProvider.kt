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
            override fun play() {
                super.play()
                routes.onPlaying(this, track)
            }

            override fun pause() {
                super.pause()
                routes.onInactive(this)
            }

            override fun stop() {
                super.stop()
                routes.onInactive(this)
            }

            override fun release() {
                routes.onInactive(this)
                super.release()
            }
        }
    }
}
