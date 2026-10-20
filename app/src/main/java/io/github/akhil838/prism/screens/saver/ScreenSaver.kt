package io.github.akhil838.prism.screens.saver

import android.app.Activity
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager

/** System screen saver ("Daydream"): Prism Aerials while any app is idle, once chosen in TV settings. */
class AerialDream : DreamService() {
    private var view: AerialView? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        view = AerialView(this).also { setContentView(it) }
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); view?.start() }
    override fun onDreamingStopped() { view?.stop(); super.onDreamingStopped() }
}

/** The same aerials started by Prism itself: Home idle timer and Settings → Preview. Any key exits. */
class ScreenSaverActivity : Activity() {
    private lateinit var view: AerialView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        view = AerialView(this)
        setContentView(view)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    override fun onStart() { super.onStart(); view.start() }
    override fun onStop() { view.stop(); super.onStop(); finish() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}
