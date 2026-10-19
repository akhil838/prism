package io.github.akhil838.prism

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * "Liquid Prism" dock without shaders (AGSL needs API 33; the TV is API 30).
 *
 * Refraction: the Top Shelf bitmaps ([sources]) are redrawn inside the glass through a warped
 * vertex mesh (Canvas.drawVertices + BitmapShader, GPU-accelerated from API 29). Away from the
 * rim the mapping is identity, so the glass is invisible; inside a bevel band near the rounded
 * edge, each point samples from further outward, so the outside world appears compressed and
 * bent at the rim like a thick lens edge. The mesh is rebuilt only when size or strength changes.
 *
 * Then: [overlay] (shade/scrim drawn above the backdrop in the real scene), optional frost from a
 * pre-blurred bitmap, dim, top gloss, and a light-from-above specular rim.
 */
class GlassView(context: Context, dims: Dims) : FrameLayout(context) {

    /** Top Shelf layers drawn behind the glass (bottom to top). */
    var sources: List<ImageView> = emptyList()
    /** Draws full-screen overlays that sit between the backdrop and the glass in the real scene. */
    var overlay: ((Canvas) -> Unit)? = null

    private var source: Bitmap? = null
    private val pt = dims.pt
    private val radius = dims.glassRadius
    private val rimWidth = 1.5f * pt

    private val clip = Path()
    private val bounds = RectF()
    private val srcRect = Rect()
    private val location = IntArray(2)
    private val tmpLoc = IntArray(2)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = rimWidth; color = -1 }
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = rimWidth; color = -1 }
    private val elevationMax = 10 * pt

    // Refraction mesh (local coordinates) and per-layer texture coordinates.
    private var meshKey = ""
    private var verts = FloatArray(0)
    private var texBase = FloatArray(0)
    private var texs = FloatArray(0)
    private var indices = ShortArray(0)
    private val inv = Matrix()
    private val layerMatrix = Matrix()
    /**
     * Shader paint for the current source bitmaps only. (A WeakHashMap never released entries: each
     * value's BitmapShader holds its key, so every old backdrop/tint/frost bitmap stayed alive.)
     */
    private val layerPaints = HashMap<Bitmap, Paint>()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
                outline.alpha = 0.8f
            }
        }
        applyStyle()
    }

    /** Pre-blurred full-screen bitmap used for the frost layer; [color] kept for API stability. */
    @Suppress("UNUSED_PARAMETER")
    fun setArtwork(glass: Bitmap?, color: Int) {
        source = glass
        invalidate()
    }

    /** Re-reads [glassStyle] and redraws. */
    fun applyStyle() {
        elevation = elevationMax * glassStyle.blur / 100f // clear glass casts no visible shadow
        updateShaders()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glassDirty = true
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        updateShaders()
    }

    private fun white(a: Float) = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or 0x00FFFFFF

    private fun updateShaders() {
        val h = height.toFloat()
        if (h <= 0f) return
        val g = glassStyle.gloss / 100f
        // Light from above: bright top edge and upper curves, faint sides, a softer bottom reflection.
        rimPaint.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(white(0.12f + 0.58f * g), white(0.04f + 0.08f * g), white(0.08f + 0.32f * g)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        innerPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.3f, white(0.28f * g), white(0f), Shader.TileMode.CLAMP)
        highlightPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.35f, white(0.12f * g), white(0f), Shader.TileMode.CLAMP)
    }

    // ---- refraction mesh ----------------------------------------------------------------

    private fun buildMesh() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = min(radius, min(h, w) / 2f)
        val band = min(h * 0.38f, w / 2f)
        val amp = band * 0.8f * glassStyle.refraction / 100f
        val key = "$w,$h,$amp"
        if (key == meshKey) return
        meshKey = key

        val xs = axis(w, band, 6 * pt, 64 * pt)
        val ys = axis(h, band, 5 * pt, 24 * pt)
        val nx = xs.size
        val ny = ys.size
        verts = FloatArray(nx * ny * 2)
        texBase = FloatArray(verts.size)
        texs = FloatArray(verts.size)
        val cx = w / 2f
        val cy = h / 2f
        var k = 0
        for (y in ys) for (x in xs) {
            verts[k] = x
            verts[k + 1] = y
            // Signed distance to the rounded rectangle (negative inside) and its outward normal.
            val px = x - cx
            val py = y - cy
            val qx = abs(px) - (cx - r)
            val qy = abs(py) - (cy - r)
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            val outside = hypot(ox, oy)
            val depth = -(outside + min(max(qx, qy), 0f) - r)
            var dx = 0f
            var dy = 0f
            if (amp > 0f && depth < band) {
                val t = 1f - depth.coerceAtLeast(0f) / band
                val s = amp * t * t // strongest at the rim, fading smoothly to zero
                when {
                    qx > 0f && qy > 0f && outside > 0f -> { dx = ox / outside * sign(px) * s; dy = oy / outside * sign(py) * s }
                    qx > qy -> dx = sign(px) * s
                    else -> dy = sign(py) * s
                }
            }
            texBase[k] = x + dx
            texBase[k + 1] = y + dy
            k += 2
        }
        indices = ShortArray((nx - 1) * (ny - 1) * 6)
        var n = 0
        for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            val a = (j * nx + i).toShort()
            val b = (j * nx + i + 1).toShort()
            val c = ((j + 1) * nx + i).toShort()
            val d = ((j + 1) * nx + i + 1).toShort()
            indices[n++] = a; indices[n++] = b; indices[n++] = c
            indices[n++] = b; indices[n++] = d; indices[n++] = c
        }
    }

    /** Sample positions along one axis: fine steps inside the bevel bands, coarse in between. */
    private fun axis(len: Float, band: Float, fine: Float, coarse: Float): FloatArray {
        val out = ArrayList<Float>()
        var x = 0f
        while (x < len - 0.5f) {
            out += x
            val inBand = x < band || x >= len - band - 0.5f
            x += if (inBand) fine else min(coarse, (len - band) - x).coerceAtLeast(fine)
        }
        out += len
        return out.toFloatArray()
    }

    private fun drawRefracted(canvas: Canvas) {
        if (verts.isEmpty() || !Diag.glass) return
        // Forget shaders for bitmaps no source shows any more.
        if (layerPaints.size > sources.size) {
            val live = sources.mapNotNullTo(HashSet()) { (it.drawable as? BitmapDrawable)?.bitmap }
            layerPaints.keys.retainAll(live)
        }
        for (iv in sources) {
            if (iv.visibility != VISIBLE || iv.alpha <= 0.01f) continue
            val d = iv.drawable as? BitmapDrawable ?: continue
            val bm = d.bitmap ?: continue
            if (d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0 || !iv.imageMatrix.invert(inv)) continue
            iv.getLocationInWindow(tmpLoc)
            // glass-local -> ImageView coords -> drawable coords -> bitmap pixels
            layerMatrix.setTranslate((location[0] - tmpLoc[0]).toFloat(), (location[1] - tmpLoc[1]).toFloat())
            layerMatrix.postConcat(inv)
            layerMatrix.postScale(bm.width / d.intrinsicWidth.toFloat(), bm.height / d.intrinsicHeight.toFloat())
            System.arraycopy(texBase, 0, texs, 0, texBase.size)
            layerMatrix.mapPoints(texs)
            val p = layerPaints.getOrPut(bm) {
                Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            }
            p.alpha = (iv.alpha * 255).toInt()
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, texs, 0, null, 0, indices, 0, indices.size, p)
        }
    }

    // ---- draw ---------------------------------------------------------------------------

    /**
     * The glass itself (refraction mesh, frost, tint, rim) is cached in a GPU layer: tiles animating
     * on top (focus, move-mode wiggle and reflow) no longer re-run the mesh every frame. It is redrawn
     * only when [invalidate] is called — backdrop crossfades, scrolling and style changes do that.
     */
    private val glassNode = android.graphics.RenderNode("glass").apply { setUseCompositingLayer(true, null) }
    private var glassDirty = true

    /**
     * The glass layer is frozen while things move under it: a change on its own redraws at once, but
     * during a stream of changes (scrolling into the grid, backdrop / frost fades: one per frame) it
     * keeps its last image and redraws once, [SETTLE_MS] after the last one, refraction included.
     * Redrawing it every frame was an extra off-screen pass per frame (and the mesh, thousands of
     * triangles per source) on top of the busiest frames; on the TCL every frame of those
     * transitions already took the GPU 30-120 ms.
     */
    private var moving = false
    private var lastInvalidate = 0L
    private val settle = Runnable { moving = false; redraw() }

    override fun invalidate() {
        val now = android.os.SystemClock.uptimeMillis()
        moving = now - lastInvalidate < SETTLE_MS
        lastInvalidate = now
        removeCallbacks(settle)
        if (moving) postDelayed(settle, SETTLE_MS) else redraw()
    }

    private fun redraw() {
        glassDirty = true
        super.invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (canvas.isHardwareAccelerated && width > 0 && height > 0) {
            if (glassDirty || !glassNode.hasDisplayList()) {
                glassNode.setPosition(0, 0, width, height)
                drawGlass(glassNode.beginRecording(width, height))
                glassNode.endRecording()
                glassDirty = false
            }
            canvas.drawRenderNode(glassNode)
        } else {
            drawGlass(canvas)
        }
        super.dispatchDraw(canvas)
    }

    private fun drawGlass(canvas: Canvas) {
        buildMesh()
        getLocationInWindow(location)
        canvas.save()
        canvas.clipPath(clip)

        val r = perf.refraction
        if (Diag.glass && r != Perf.Refraction.OFF && !(r == Perf.Refraction.AT_REST && moving)) drawRefracted(canvas)
        overlay?.let {
            canvas.save()
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            it(canvas)
            canvas.restore()
        }

        val s = glassStyle
        val src = source
        if (src != null && s.blur > 0) {
            val root = rootView
            val sx = src.width / root.width.toFloat()
            val sy = src.height / root.height.toFloat()
            srcRect.set(
                (location[0] * sx).toInt(), (location[1] * sy).toInt(),
                ((location[0] + width) * sx).toInt(), ((location[1] + height) * sy).toInt(),
            )
            bitmapPaint.alpha = s.blur * 255 / 100
            canvas.drawBitmap(src, srcRect, bounds, bitmapPaint)
        }
        fillPaint.shader = null
        fillPaint.color = ((s.blur * 0x1A / 100) shl 24) or 0x00FFFFFF
        canvas.drawRect(bounds, fillPaint)
        fillPaint.color = ((s.dim * 0xD9 / 100) shl 24) or 0x001C1C1F
        canvas.drawRect(bounds, fillPaint)
        canvas.drawRect(bounds, highlightPaint)
        canvas.restore()

        val i1 = rimWidth / 2
        canvas.drawRoundRect(i1, i1, width - i1, height - i1, radius - i1, radius - i1, rimPaint)
        val i2 = rimWidth * 2
        canvas.drawRoundRect(i2, i2, width - i2, height - i2, radius - i2, radius - i2, innerPaint)
    }
}

/** Quiet time after the glass's last change before its refraction mesh is drawn again. */
private const val SETTLE_MS = 150L

/** User-tunable glass look, 0..100 each. Default matches a clear tvOS 26 dock over artwork. */
data class GlassStyle(val blur: Int = 0, val dim: Int = 20, val gloss: Int = 50, val refraction: Int = 50)

/** Process-wide current style; activities load it from preferences. */
var glassStyle = GlassStyle()
