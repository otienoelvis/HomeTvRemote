package com.example.hometv.domain.model

import android.view.KeyEvent

/**
 * Standard Android TV Remote keycode constants.
 */
object KeyCodes {
    const val KEYCODE_DPAD_UP = KeyEvent.KEYCODE_DPAD_UP // 19
    const val KEYCODE_DPAD_DOWN = KeyEvent.KEYCODE_DPAD_DOWN // 20
    const val KEYCODE_DPAD_LEFT = KeyEvent.KEYCODE_DPAD_LEFT // 21
    const val KEYCODE_DPAD_RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT // 22
    const val KEYCODE_DPAD_CENTER = KeyEvent.KEYCODE_DPAD_CENTER // 23
    const val KEYCODE_HOME = KeyEvent.KEYCODE_HOME // 3
    const val KEYCODE_BACK = KeyEvent.KEYCODE_BACK // 4
    const val KEYCODE_VOLUME_UP = KeyEvent.KEYCODE_VOLUME_UP // 24
    const val KEYCODE_VOLUME_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN // 25
    const val KEYCODE_VOLUME_MUTE = KeyEvent.KEYCODE_VOLUME_MUTE // 164
    const val KEYCODE_POWER = KeyEvent.KEYCODE_POWER // 26
    const val KEYCODE_MEDIA_PLAY_PAUSE = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE // 85
}

/**
 * Key commands or app deep links sent to the TV.
 */
sealed class RemoteCommand {
    data class KeyEventCommand(val keycode: Int, val label: String) : RemoteCommand()
    data class LaunchAppCommand(val appLink: String, val label: String) : RemoteCommand()

    companion object {
        val DpadUp = KeyEventCommand(KeyCodes.KEYCODE_DPAD_UP, "Up")
        val DpadDown = KeyEventCommand(KeyCodes.KEYCODE_DPAD_DOWN, "Down")
        val DpadLeft = KeyEventCommand(KeyCodes.KEYCODE_DPAD_LEFT, "Left")
        val DpadRight = KeyEventCommand(KeyCodes.KEYCODE_DPAD_RIGHT, "Right")
        val DpadCenter = KeyEventCommand(KeyCodes.KEYCODE_DPAD_CENTER, "OK")
        val Home = KeyEventCommand(KeyCodes.KEYCODE_HOME, "Home")
        val Back = KeyEventCommand(KeyCodes.KEYCODE_BACK, "Back")
        val VolumeUp = KeyEventCommand(KeyCodes.KEYCODE_VOLUME_UP, "Vol +")
        val VolumeDown = KeyEventCommand(KeyCodes.KEYCODE_VOLUME_DOWN, "Vol -")
        val Mute = KeyEventCommand(KeyCodes.KEYCODE_VOLUME_MUTE, "Mute")
        val Power = KeyEventCommand(KeyCodes.KEYCODE_POWER, "Power")
        val PlayPause = KeyEventCommand(KeyCodes.KEYCODE_MEDIA_PLAY_PAUSE, "Play/Pause")

        val YouTube = LaunchAppCommand("https://www.youtube.com", "YouTube")
        val Netflix = LaunchAppCommand("netflix://", "Netflix")
        val PrimeVideo = LaunchAppCommand("https://app.primevideo.com", "Prime Video")
    }
}
