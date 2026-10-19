package io.github.akhil838.prism

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Top-right status (tvOS 26): a glass pill with the time, a notification count and the profile
 * picture. Moving Up into it splits the pill into separate glass circles — Notifications, Screen
 * Saver, Settings, Profile — with the time beside them. The bell opens a notifications panel.
 * Down / Back folds the circles back into the pill and returns focus via [Actions.leave].
 */
@SuppressLint("ViewConstructor")
class ControlStrip(context: Context, private val dims: Dims, private val actions: Actions) : FrameLayout(context) {
    interface Actions {
        fun leave()
        fun openSettings(page: String?)
        fun startScreenSaver()
    }

    private val pt = dims.pt
    private val c = (68 * pt).toInt()          // circle diameter
    private val gap = (16 * pt).toInt()

    // ---- collapsed pill ----
    private val pill = LinearLayout(context)
    private val clock = label(28f)
    private val pillBell = ImageView(context).apply { setImageResource(R.drawable.ic_bell); visibility = GONE }
    private val pillCount = label(24f).apply { visibility = GONE }
    private val pillAvatar = ImageView(context)

    // ---- expanded circles ----
    private val row = LinearLayout(context)
    private val time = label(30f)
    private val circles = ArrayList<View>()
    private val discs = ArrayList<View>()
    private val icons = ArrayList<ImageView>()
    private val badge = label(20f)
    private lateinit var avatarIcon: ImageView
    private var avatar: Bitmap? = null

    // ---- notifications panel ----
    private val panel = LinearLayout(context)
    private val panelRows = ArrayList<View>()

    var expanded = false
        private set
    private val onNotifs: () -> Unit = { updateCount(); if (panel.visibility == VISIBLE) fillPanel() }

    init {
        clipChildren = false
        buildPill()
        buildRow()
        buildPanel()
        updateCount()
    }

    // ---------------------------------------------------------------------------------------------

    private fun label(sizePt: Float) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePt * pt)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun glass(oval: Boolean) = GradientDrawable().apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadius = 999f
        setColor(0x4D1C1C1F)
        setStroke((1.5f * pt).toInt().coerceAtLeast(1), 0x40FFFFFF)
    }

    private fun buildPill() {
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        val pv = (6 * pt).toInt()
        pill.setPadding((18 * pt).toInt(), pv, pv, pv)
        pill.background = glass(oval = false)
        pill.addView(clock)
        val bs = (26 * pt).toInt()
        pill.addView(pillBell, LinearLayout.LayoutParams(bs, bs).apply { leftMargin = (14 * pt).toInt() })
        pill.addView(pillCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (4 * pt).toInt() })
        val av = (44 * pt).toInt()
        pillAvatar.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = OVAL; clipToOutline = true
            setBackgroundColor(0x33FFFFFF)
        }
        pill.addView(pillAvatar, LinearLayout.LayoutParams(av, av).apply { leftMargin = (14 * pt).toInt() })
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dims.safeTop; rightMargin = dims.safeSide
        })
    }

    private fun buildRow() {
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.clipChildren = false; row.clipToPadding = false
        row.visibility = INVISIBLE
        row.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = (10 * pt).toInt() })
        addCircle(R.drawable.ic_bell, withBadge = true) { togglePanel() }
        addCircle(R.drawable.ic_saver) { collapse(); actions.startScreenSaver() }
        addCircle(R.drawable.ic_gear) { collapse(); actions.openSettings(null) }
        avatarIcon = addCircle(R.drawable.ic_person) { collapse(); actions.openSettings(SettingsActivity.PAGE_PROFILE) }
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, c + (20 * pt).toInt(), Gravity.TOP or Gravity.END).apply {
            // Circles centred on the pill's centre line.
            topMargin = dims.safeTop + (28 * pt).toInt() - (c + (20 * pt).toInt()) / 2
            rightMargin = dims.safeSide
        })
    }

    private fun addCircle(icon: Int, withBadge: Boolean = false, onClick: () -> Unit): ImageView {
        val i = circles.size
        val disc = FrameLayout(context).apply { background = glass(oval = true); outlineProvider = OVAL; clipToOutline = true; elevation = 4 * pt }
        val img = ImageView(context).apply { setImageResource(icon); scaleType = ImageView.ScaleType.CENTER_INSIDE }
        val pad = (18 * pt).toInt()
        disc.addView(img, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply { setMargins(pad, pad, pad, pad) })
        val outer = FrameLayout(context).apply {
            clipChildren = false
            isFocusable = true; isClickable = true
            addView(disc, LayoutParams(c, c, Gravity.CENTER))
            if (withBadge) {
                val bs = (28 * pt).toInt()
                badge.apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, 18 * pt)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF453A.toInt()) }
                    elevation = 40 * pt // above the focused (lifted) disc
                    setPadding((4 * pt).toInt(), 0, (4 * pt).toInt(), 0)
                    minWidth = bs
                }
                addView(badge, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, bs, Gravity.TOP or Gravity.END).apply {
                    topMargin = (2 * pt).toInt(); rightMargin = (2 * pt).toInt()
                })
            }
            setOnFocusChangeListener { _, has ->
                (disc.background as GradientDrawable).setColor(if (has) Color.WHITE else 0x4D1C1C1F)
                if (img.drawable != null && !(i == circles.size - 1 && avatar != null)) img.setColorFilter(if (has) 0xFF1C1C1E.toInt() else Color.WHITE, PorterDuff.Mode.SRC_IN)
                disc.animate().scaleX(if (has) 1.18f else 1f).scaleY(if (has) 1.18f else 1f)
                    .translationZ(if (has) 16 * pt else 0f).setDuration(180).start()
                if (has && i != 0) hidePanel()
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { circles.getOrNull(i - 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { circles.getOrNull(i + 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (i == 0 && panel.visibility == VISIBLE && panelRows.isNotEmpty()) panelRows[0].requestFocus()
                        else leave()
                        true
                    }
                    else -> false
                }
            }
        }
        circles += outer; discs += disc; icons += img
        row.addView(outer, LinearLayout.LayoutParams(c + (20 * pt).toInt(), c + (20 * pt).toInt()).apply { leftMargin = if (i == 0) 0 else gap - (20 * pt).toInt() })
        return img
    }

    // ---- notifications panel ----------------------------------------------------------------

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.visibility = GONE
        val p = (22 * pt).toInt()
        panel.setPadding(p, p, p, p)
        panel.background = GradientDrawable().apply {
            cornerRadius = 34 * pt
            setColor(0xF01C1C1F.toInt())
            setStroke((1.5f * pt).toInt().coerceAtLeast(1), 0x40FFFFFF)
        }
        panel.elevation = 24 * pt
        panel.clipChildren = false; panel.clipToPadding = false
        addView(panel, LayoutParams((660 * pt).toInt(), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dims.safeTop + (28 * pt).toInt() + c / 2 + (26 * pt).toInt()
            rightMargin = dims.safeSide
        })
    }

    private fun togglePanel() = if (panel.visibility == VISIBLE) hidePanel() else showPanel()

    private fun showPanel() {
        fillPanel()
        panel.visibility = VISIBLE
        panel.alpha = 0f; panel.translationY = -16 * pt
        panel.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        panelRows.firstOrNull()?.requestFocus()
    }

    private fun hidePanel() {
        if (panel.visibility != VISIBLE) return
        val focusedInside = panel.hasFocus()
        panel.visibility = GONE
        if (focusedInside) circles[0].requestFocus()
    }

    private fun fillPanel() {
        val refocus = panelRows.indexOfFirst { it.isFocused }
        panel.removeAllViews(); panelRows.clear()
        panel.addView(label(34f).apply { text = context.getString(R.string.notifications); typeface = Typeface.create("sans-serif", Typeface.BOLD) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (14 * pt).toInt(); leftMargin = (8 * pt).toInt() })
        if (!Notifs.granted(context)) {
            panel.addView(note(context.getString(R.string.notif_access_body, Notifs.component(context).flattenToShortString())))
            addPanelRow(context.getString(R.string.notif_open_access), null) {
                runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } else if (Notifs.items.isEmpty()) {
            panel.addView(note(context.getString(R.string.no_notifications)))
        } else {
            Notifs.items.take(6).forEach { n -> addPanelRow(n.title, n) { open(n) } }
            if (Notifs.items.any { it.clearable }) addPanelRow(context.getString(R.string.clear_all), null) { Notifs.clearAll() }
        }
        if (panel.visibility == VISIBLE && refocus >= 0) (panelRows.getOrNull(refocus) ?: panelRows.lastOrNull() ?: circles[0]).requestFocus()
    }

    private fun note(text: String) = TextView(context).apply {
        this.text = text
        setTextColor(0xCCFFFFFF.toInt())
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * pt)
        setLineSpacing(4 * pt, 1f)
        setPadding((8 * pt).toInt(), (4 * pt).toInt(), (8 * pt).toInt(), (12 * pt).toInt())
    }

    private fun addPanelRow(title: String, n: Notifs.Item?, onClick: () -> Unit) {
        val bg = GradientDrawable().apply { cornerRadius = 24 * pt; setColor(0x1FFFFFFF) }
        val titleView = label(26f).apply { text = title; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END }
        val sub = n?.let {
            label(22f).apply {
                text = listOfNotNull(appName(it.pkg), it.text).joinToString(" · ")
                setTextColor(0xB3FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                typeface = Typeface.DEFAULT
            }
        }
        val whenView = n?.let { label(20f).apply {
            text = DateUtils.getRelativeTimeSpanString(it.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
            setTextColor(0x99FFFFFF.toInt()); typeface = Typeface.DEFAULT
        } }
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView); sub?.let { addView(it) }
        }
        val rowView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            isFocusable = true; isClickable = true
            val p = (14 * pt).toInt(); setPadding(p, p, (18 * pt).toInt(), p)
            if (n != null) addView(ImageView(context).apply {
                setImageDrawable(runCatching { context.packageManager.getApplicationIcon(n.pkg) }.getOrNull())
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 10 * pt)
                }
                clipToOutline = true
            }, LinearLayout.LayoutParams((48 * pt).toInt(), (48 * pt).toInt()).apply { rightMargin = (16 * pt).toInt() })
            addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            whenView?.let { addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = (12 * pt).toInt() }) }
            val i = panelRows.size
            setOnFocusChangeListener { v, has ->
                bg.setColor(if (has) Color.WHITE else 0x1FFFFFFF)
                titleView.setTextColor(if (has) 0xFF1C1C1E.toInt() else Color.WHITE)
                sub?.setTextColor(if (has) 0xFF48484A.toInt() else 0xB3FFFFFF.toInt())
                whenView?.setTextColor(if (has) 0xFF6E6A75.toInt() else 0x99FFFFFF.toInt())
                v.animate().scaleX(if (has) 1.03f else 1f).scaleY(if (has) 1.03f else 1f).translationZ(if (has) 10 * pt else 0f).setDuration(150).start()
            }
            setOnClickListener { onClick() }
            setOnKeyListener { _, k, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP -> { (panelRows.getOrNull(i - 1) ?: circles[0]).requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { panelRows.getOrNull(i + 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
                    else -> false
                }
            }
        }
        panelRows += rowView
        panel.addView(rowView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * pt).toInt() })
    }

    private fun appName(pkg: String) = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    private fun open(n: Notifs.Item) {
        collapse()
        runCatching { n.intent?.send() }.onFailure {
            context.packageManager.getLeanbackLaunchIntentForPackage(n.pkg)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        if (n.clearable) Notifs.dismiss(n)
    }

    // ---- state ------------------------------------------------------------------------------

    fun setTime(text: CharSequence) { clock.text = text; time.text = text }

    fun setAvatar(b: Bitmap?) {
        avatar = b
        // No picture chosen: a person glyph in the same circle (tvOS), so the pill keeps its shape.
        if (b != null) { pillAvatar.setImageBitmap(b); pillAvatar.scaleType = ImageView.ScaleType.CENTER_CROP; pillAvatar.setPadding(0, 0, 0, 0) }
        else {
            pillAvatar.setImageResource(R.drawable.ic_person); pillAvatar.scaleType = ImageView.ScaleType.FIT_CENTER
            val p = (9 * pt).toInt(); pillAvatar.setPadding(p, p, p, p)
        }
        pillAvatar.visibility = VISIBLE
        if (b != null) {
            avatarIcon.clearColorFilter(); avatarIcon.setImageBitmap(b)
            avatarIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            (avatarIcon.layoutParams as LayoutParams).setMargins(0, 0, 0, 0)
        } else {
            avatarIcon.setImageResource(R.drawable.ic_person); avatarIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (18 * pt).toInt(); (avatarIcon.layoutParams as LayoutParams).setMargins(pad, pad, pad, pad)
        }
        avatarIcon.requestLayout()
    }

    private fun updateCount() {
        val n = Notifs.items.size
        pillBell.visibility = if (n > 0) VISIBLE else GONE
        pillCount.visibility = pillBell.visibility
        pillCount.text = n.toString()
        badge.visibility = if (n > 0) VISIBLE else GONE
        badge.text = if (n > 9) "9+" else n.toString()
    }

    /** Split the pill into circles and focus the first one. */
    fun expand() {
        if (expanded) return
        expanded = true
        hidePanel()
        pill.animate().alpha(0f).scaleX(0.9f).setDuration(160).start()
        row.visibility = VISIBLE
        time.alpha = 0f
        time.animate().alpha(1f).setStartDelay(120).setDuration(220).start()
        val step = (c + gap).toFloat()
        circles.forEachIndexed { k, v ->
            // Start stacked where the pill's right end is, then spread out to their places.
            v.translationX = (circles.size - 1 - k) * step
            v.alpha = 0f; v.scaleX = 0.7f; v.scaleY = 0.7f
            v.animate().translationX(0f).alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay((circles.size - 1 - k) * 30L).setDuration(340)
                .setInterpolator(OvershootInterpolator(1.2f)).start()
        }
        circles[0].requestFocus()
    }

    /** Fold the circles back into the pill (focus is the caller's job). */
    fun collapse() {
        if (!expanded) return
        expanded = false
        panel.visibility = GONE
        val step = (c + gap).toFloat()
        circles.forEachIndexed { k, v ->
            v.animate().translationX((circles.size - 1 - k) * step).alpha(0f).scaleX(0.7f).scaleY(0.7f)
                .setStartDelay(0).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        }
        time.animate().alpha(0f).setStartDelay(0).setDuration(150).start()
        row.postDelayed({ if (!expanded) row.visibility = INVISIBLE }, 230)
        pill.animate().alpha(1f).scaleX(1f).setStartDelay(80).setDuration(200).start()
    }

    private fun leave() { collapse(); actions.leave() }

    /** Back: panel first, then the strip. */
    fun back() { if (panel.visibility == VISIBLE) hidePanel() else leave() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); Notifs.listen(onNotifs); updateCount() }
    override fun onDetachedFromWindow() { Notifs.unlisten(onNotifs); super.onDetachedFromWindow() }

    private companion object {
        val OVAL = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
    }
}
