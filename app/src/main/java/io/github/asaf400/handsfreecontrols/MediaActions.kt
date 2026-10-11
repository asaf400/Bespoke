package io.github.asaf400.handsfreecontrols

import android.view.KeyEvent
import androidx.annotation.StringRes

data class MediaAction(
    val keyCode: Int?,
    @param:StringRes val labelRes: Int,
)

object MediaActions {
    val all: List<MediaAction> = listOf(
        MediaAction(null, R.string.action_none),
        MediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, R.string.action_play_pause),
        MediaAction(KeyEvent.KEYCODE_MEDIA_PLAY, R.string.action_play),
        MediaAction(KeyEvent.KEYCODE_MEDIA_PAUSE, R.string.action_pause),
        MediaAction(KeyEvent.KEYCODE_MEDIA_STOP, R.string.action_stop),
        MediaAction(KeyEvent.KEYCODE_MEDIA_NEXT, R.string.action_next),
        MediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS, R.string.action_previous),
        MediaAction(KeyEvent.KEYCODE_MEDIA_REWIND, R.string.action_rewind),
        MediaAction(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, R.string.action_fast_forward),
        MediaAction(KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, R.string.action_skip_backward),
        MediaAction(KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, R.string.action_skip_forward),
        MediaAction(KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD, R.string.action_step_backward),
        MediaAction(KeyEvent.KEYCODE_MEDIA_STEP_FORWARD, R.string.action_step_forward),
        MediaAction(KeyEvent.KEYCODE_MEDIA_RECORD, R.string.action_record),
        MediaAction(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, R.string.action_audio_track),
    )

    fun find(keyCode: Int?): MediaAction =
        all.firstOrNull { it.keyCode == keyCode } ?: all.first()
}
