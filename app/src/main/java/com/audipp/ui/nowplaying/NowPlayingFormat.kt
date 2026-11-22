package com.audipp.ui.nowplaying

import android.media.AudioDeviceInfo
import androidx.annotation.StringRes
import com.audipp.R
import com.audipp.domain.model.PlaceholderReason
import java.math.BigDecimal
import java.math.RoundingMode

internal fun formatClock(ms: Long): String {
    val seconds = ms / 1_000
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val rest = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

/** 44100 -> "44.1", 96000 -> "96"; the value is only reformatted, never rounded to a nominal rate. */
internal fun formatKilohertz(hz: Int): String =
    BigDecimal(hz).divide(BigDecimal(1_000), 3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

@StringRes
internal fun PlaceholderReason.label(): Int = when (this) {
    PlaceholderReason.NO_ARTWORK -> R.string.artwork_no_artwork
    PlaceholderReason.EXPIRED -> R.string.artwork_expired
    PlaceholderReason.UNAVAILABLE -> R.string.artwork_unavailable
    PlaceholderReason.DISPLAY_NOT_ALLOWED -> R.string.artwork_display_not_allowed
    PlaceholderReason.REJECTED -> R.string.artwork_rejected
}

/** Labels the Android route type only; it does not say which device it is or what format it carries. */
@StringRes
internal fun outputTypeLabel(androidType: Int): Int = when (androidType) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> R.string.now_playing_output_speaker
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.now_playing_output_earpiece
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> R.string.now_playing_output_wired
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> R.string.now_playing_output_bluetooth
    AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET -> R.string.now_playing_output_usb
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC ->
        R.string.now_playing_output_hdmi
    else -> R.string.now_playing_output_other
}
