package io.github.akhil838.prism

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run setup, one step per full-screen slide (Projectivy-style). Each slide that needs
 * something asks for exactly that: TV listings permission, then the Home role.
 */
class IntroActivity : Activity() {

    private class Slide(
        val title: Int,
        val body: Int,
        val done: () -> Boolean = { false },
        val primary: Int,
        val onPrimary: () -> Unit,
        val skippable: Boolean = true,
    )

    private lateinit var dims: Dims
    private val pt get() = dims.pt
    private lateinit var titleView: TextView
    private lateinit var bodyView: TextView
    private lateinit var stateView: TextView
    private lateinit var hintView: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var content: View
    private var index = 0
    private val slides by lazy { buildSlides() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Sounds.init(this)
        val m = resources.displayMetrics
        dims = Dims(maxOf(m.widthPixels, m.heightPixels), minOf(m.widthPixels, m.heightPixels))
        setContentView(buildLayout())
        index = savedInstanceState?.getInt(KEY_INDEX) ?: 0
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(KEY_INDEX, index)
    }

    override fun onResume() {
        super.onResume()
        render(animate = false) // re-check state after returning from a system dialog
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = Sounds.around(this, event) { dispatchKey(event) }

    private fun dispatchKey(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) { if (index > 0) go(index - 1) else finish() }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- slides -------------------------------------------------------------------------

    private fun buildSlides() = listOf(
        Slide(R.string.intro_welcome_title, R.string.intro_welcome_body, primary = R.string.intro_start, onPrimary = { next() }, skippable = false),
        Slide(
            R.string.intro_reco_title, R.string.intro_reco_body,
            done = { Channels.allowed(this) },
            primary = R.string.intro_allow,
            onPrimary = { requestPermissions(arrayOf(Channels.PERMISSION), REQ_LISTINGS) },
        ),
        Slide(
            R.string.intro_home_title, R.string.intro_home_body,
            done = { isDefaultHome(this) },
            primary = R.string.set_as_home,
            onPrimary = { requestHome() },
        ),
        Slide(R.string.intro_style_title, R.string.intro_style_body, primary = R.string.intro_finish, onPrimary = { finishIntro(openSettings = false) }, skippable = false),
    )

    private fun next() = go(index + 1)

    private fun go(i: Int) {
        index = i.coerceIn(0, slides.lastIndex)
        render(animate = true)
    }

    private fun finishIntro(openSettings: Boolean) {
        getSharedPreferences("prism", MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
        if (openSettings) startActivity(Intent(this, SettingsActivity::class.java))
        else if (isDefaultHome(this)) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName))
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == REQ_LISTINGS) {
            if (Channels.allowed(this)) next() else render(animate = false)
        }
    }

    private fun requestHome() {
        // A launcher with a higher intent priority beats the Home role: granting it would only bounce
        // the user to that launcher. Explain the one-time ADB step instead.
        if (overridingLauncher(this) != null) { showHomeHint(); return }
        // Coming back through Home after the role change lands on Prism (if it wins), which resumes setup.
        getSharedPreferences("prism", MODE_PRIVATE).edit().putBoolean(KEY_RESUME, true).apply()
        val rm = if (Build.VERSION.SDK_INT >= 29) getSystemService(RoleManager::class.java) else null
        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
            @Suppress("DEPRECATION")
            startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME)
            return
        }
        // Role held but Home still opens the OEM launcher, or no role UI: try system settings,
        // and show the ADB fix on the slide.
        showHomeHint()
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    @Deprecated("No AndroidX activity-result API in this app; a single request is fine.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME) {
            if (isDefaultHome(this)) next() else showHomeHint()
        }
    }

    private fun showHomeHint() {
        val other = overridingLauncher(this) ?: STOCK_LAUNCHER
        hintView.text = getString(R.string.setup_adb_body, other, packageName)
        hintView.visibility = View.VISIBLE
    }

    // ---- layout -------------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply {
            clipChildren = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF0E1420.toInt(), 0xFF1B2333.toInt(), 0xFF2A2140.toInt()))
        }
        // Soft glow behind the icon.
        root.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = 480 * pt
                colors = intArrayOf(0x4D6E8BFF, 0x006E8BFF)
            }
        }, FrameLayout.LayoutParams((960 * pt).toInt(), (960 * pt).toInt()).apply {
            leftMargin = (40 * pt).toInt(); topMargin = (60 * pt).toInt()
        })
        // No elevation shadow: shadows are what ran the TCL's GPU out of memory (see TileView).
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, 80 * pt)
            }
            clipToOutline = true
        }
        root.addView(icon, FrameLayout.LayoutParams((420 * pt).toInt(), (420 * pt).toInt()).apply {
            leftMargin = (310 * pt).toInt(); topMargin = (330 * pt).toInt()
        })

        // The slide fade animates this column's alpha. With overlapping rendering Android fades it
        // through an offscreen layer the size of the column, cropping the zoomed button and its shadow
        // mid-transition; without it each child fades on its own, unclipped.
        val col = object : LinearLayout(this) { override fun hasOverlappingRendering() = false }.apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
        }
        content = col
        titleView = text(60f, Color.WHITE, bold = true)
        bodyView = text(30f, 0xD9FFFFFF.toInt()).apply { setLineSpacing(8 * pt, 1f) }
        stateView = text(28f, 0xFF7EE0A1.toInt(), bold = true)
        hintView = text(24f, 0xB3FFFFFF.toInt()).apply { visibility = View.GONE; setLineSpacing(4 * pt, 1f) }
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false; clipToPadding = false
            // Room for the 1.08x focus zoom + shadow on every side.
            val p = (14 * pt).toInt(); setPadding(p, p, p, p)
        }
        col.addView(titleView)
        col.addView(bodyView, lp(top = 24))
        col.addView(stateView, lp(top = 24))
        col.addView(buttons, lp(top = 40 - 14).apply { leftMargin = -(14 * pt).toInt() })
        col.addView(hintView, lp(top = 30))
        root.addView(col, FrameLayout.LayoutParams((820 * pt).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
            leftMargin = (960 * pt).toInt()
        })

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = (70 * pt).toInt()
        })
        return root
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = (top * pt).toInt()
    }

    private fun text(sizePt: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePt * pt)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun button(label: Int, primary: Boolean, onClick: () -> Unit): View {
        val h = (76 * pt).toInt()
        val bg = GradientDrawable().apply { cornerRadius = h / 2f; setColor(if (primary) 0x40FFFFFF else 0x1AFFFFFF) }
        return TextView(this).apply {
            text = getString(label)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30 * pt)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = bg
            setPadding((44 * pt).toInt(), 0, (44 * pt).toInt(), 0)
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) Color.WHITE else if (primary) 0x40FFFFFF else 0x1AFFFFFF)
                (v as TextView).setTextColor(if (has) 0xFF1C1C1E.toInt() else Color.WHITE)
                v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f).translationZ(if (has) 14 * pt else 0f).setDuration(150).start()
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, h).apply { rightMargin = (24 * pt).toInt() }
        }
    }

    private fun render(animate: Boolean) {
        val s = slides[index]
        val apply = {
            titleView.setText(s.title)
            bodyView.setText(s.body)
            val done = s.done()
            stateView.visibility = if (done) View.VISIBLE else View.GONE
            stateView.setText(R.string.intro_done_state)
            hintView.visibility = View.GONE
            buttons.removeAllViews()
            if (done) {
                buttons.addView(button(R.string.intro_continue, primary = true) { next() })
            } else {
                buttons.addView(button(s.primary, primary = true, s.onPrimary))
                if (s.skippable) buttons.addView(button(R.string.intro_skip, primary = false) { next() })
            }
            if (index == slides.lastIndex) {
                buttons.addView(button(R.string.intro_open_settings, primary = false) { finishIntro(openSettings = true) })
            }
            dots.removeAllViews()
            slides.indices.forEach { i ->
                val d = (if (i == index) 34 else 12) * pt
                dots.addView(View(this).apply {
                    background = GradientDrawable().apply { cornerRadius = 99f; setColor(if (i == index) Color.WHITE else 0x59FFFFFF) }
                }, LinearLayout.LayoutParams(d.toInt(), (12 * pt).toInt()).apply { setMargins((6 * pt).toInt(), 0, (6 * pt).toInt(), 0) })
            }
            buttons.post { buttons.getChildAt(0)?.requestFocus() }
        }
        if (!animate) { apply(); return }
        content.animate().alpha(0f).translationX(-40 * pt).setDuration(140).withEndAction {
            apply()
            content.translationX = 40 * pt
            content.animate().alpha(1f).translationX(0f).setDuration(220).start()
        }.start()
    }

    companion object {
        private const val REQ_LISTINGS = 21
        private const val REQ_HOME = 22
        private const val KEY_INDEX = "index"
        private const val KEY_DONE = "intro_done"
        const val KEY_RESUME = "intro_resume"
        private const val STOCK_LAUNCHER = "com.google.android.tvlauncher"

        fun isDefaultHome(ctx: Context): Boolean {
            val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            return r?.activityInfo?.packageName == ctx.packageName
        }

        /**
         * A built-in launcher that out-ranks third-party Home apps (Google TV / TCL ship theirs with a
         * higher intent priority, which Android lets beat the Home role), or null.
         */
        fun overridingLauncher(ctx: Context): String? =
            ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .firstOrNull { it.priority > 0 && it.activityInfo.packageName != ctx.packageName }
                ?.activityInfo?.packageName

        /** Show the intro on first run, or when a required step is still missing. */
        fun needed(ctx: Context): Boolean =
            !ctx.getSharedPreferences("prism", MODE_PRIVATE).getBoolean(KEY_DONE, false) ||
                !isDefaultHome(ctx) || !Channels.allowed(ctx)
    }
}
