package io.github.akhil838.prism.media

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.KeyEvent
import io.github.akhil838.prism.R
import io.github.akhil838.prism.data.DockStore

/**
 * tvOS-style interface sounds (Settings → Sound Effects). Short synthesized clips in res/raw,
 * played through one SoundPool (low latency, decoded once, ~50 KB). The focus tick only plays when
 * a remote key actually moved focus, so programmatic focus changes stay silent.
 */
object Sounds {
    enum class Fx(val res: Int, val volume: Float) {
        FOCUS(R.raw.sfx_focus, 0.5f), SELECT(R.raw.sfx_select, 0.7f), BACK(R.raw.sfx_back, 0.6f),
        PICK_UP(R.raw.sfx_pickup, 0.7f), DROP(R.raw.sfx_drop, 0.7f), ERROR(R.raw.sfx_error, 0.7f),
    }

    private var pool: SoundPool? = null
    private val ids = IntArray(Fx.entries.size)
    private var enabled = true

    fun init(ctx: Context) {
        enabled = DockStore(ctx).sounds()
        if (pool != null) return
        val app = ctx.applicationContext
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
        ).build().also { p -> Fx.entries.forEach { ids[it.ordinal] = p.load(app, it.res, 1) } }
    }

    fun setEnabled(on: Boolean) { enabled = on }

    fun play(fx: Fx) {
        if (!enabled) return
        pool?.play(ids[fx.ordinal], fx.volume, fx.volume, 1, 0, 1f)
    }

    /**
     * Wrap an activity's dispatchKeyEvent: tick if a D-pad press moved focus, select on OK,
     * back on Back. Returns what [dispatch] returned.
     */
    fun around(activity: Activity, event: KeyEvent, dispatch: () -> Boolean): Boolean {
        val before = activity.currentFocus
        val handled = dispatch()
        if (event.action == KeyEvent.ACTION_DOWN) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (activity.currentFocus !== before) play(Fx.FOCUS)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) play(Fx.SELECT)
            KeyEvent.KEYCODE_BACK -> if (event.repeatCount == 0) play(Fx.BACK)
        }
        return handled
    }
}
