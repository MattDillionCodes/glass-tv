package io.github.mattdillioncodes.glass

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** Background thread for the one-off pixel work (wallpaper, blur, refraction). */
object Glass {
    val worker = Executors.newSingleThreadExecutor()
}

/**
 * A blurred, screen-aligned copy of the wallpaper for the glass to refract. Android 12 has no
 * AGSL shaders, so instead of refracting on the GPU every frame, each glass panel asks for its
 * patch once ([render]) and then just draws that bitmap.
 *
 * Only the 1/[SCALE] copy is kept (320x180 = ~230 KB), sampled bilinearly: the blur hides the
 * difference, and it avoids a full-screen upscale and an 8 MB pixel array per photo.
 */
class Backdrop(small: Bitmap, val width: Int, val height: Int, dim: Boolean) {
    private val sw = small.width
    private val sh = small.height
    private val blurred = IntArray(sw * sh)
    private val fx = sw / width.toFloat()
    private val fy = sh / height.toFloat()

    /** The blurred (and dimmed) copy as an image; stretched full-screen it's the All Apps background. */
    val blurredImage: Bitmap

    init {
        small.getPixels(blurred, 0, sw, 0, 0, sw, sh)
        small.recycle()
        repeat(3) { boxBlur(blurred, sw, sh, 2) }
        if (dim) for (i in blurred.indices) blurred[i] = scaleRgb(blurred[i], DIM)
        blurredImage = Bitmap.createBitmap(blurred, sw, sh, Bitmap.Config.ARGB_8888) // immutable: uploaded once
    }

    /** Average brightness (0..1) of the screen area a panel covers, from the small blurred copy. */
    fun luminance(left: Int, top: Int, w: Int, h: Int): Float {
        val x0 = (left * fx).toInt().coerceIn(0, sw - 1)
        val y0 = (top * fy).toInt().coerceIn(0, sh - 1)
        val x1 = ((left + w) * fx).toInt().coerceIn(x0 + 1, sw)
        val y1 = ((top + h) * fy).toInt().coerceIn(y0 + 1, sh)
        var sum = 0f
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = blurred[y * sw + x]
            sum += 0.2126f * (c shr 16 and 0xFF) + 0.7152f * (c shr 8 and 0xFF) + 0.0722f * (c and 0xFF)
        }
        return sum / ((x1 - x0) * (y1 - y0) * 255f)
    }

    /** Bilinear sample of the small blurred copy at screen coordinates. */
    private fun sample(x: Float, y: Float): Int {
        val u = (x * fx - 0.5f).coerceIn(0f, sw - 1f)
        val v = (y * fy - 0.5f).coerceIn(0f, sh - 1f)
        val x0 = u.toInt()
        val y0 = v.toInt()
        val x1 = min(x0 + 1, sw - 1)
        val y1 = min(y0 + 1, sh - 1)
        val ax = u - x0
        val ay = v - y0
        val a = blurred[y0 * sw + x0]
        val b = blurred[y0 * sw + x1]
        val c = blurred[y1 * sw + x0]
        val d = blurred[y1 * sw + x1]
        fun ch(s: Int): Int {
            val top = ((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * ax
            val bot = ((c shr s) and 0xFF) + (((d shr s) and 0xFF) - ((c shr s) and 0xFF)) * ax
            return (top + (bot - top) * ay).toInt()
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** The glass works from a 1/6-size copy of the screen. */
        const val SCALE = 6

        /** Photo darkening for dark mode (matches WallpaperView's draw-time dim). */
        const val DIM = 0.686f

        fun scaleRgb(c: Int, k: Float) = (c and 0xFF000000.toInt()) or
            (((c shr 16 and 0xFF) * k).toInt() shl 16) or (((c shr 8 and 0xFF) * k).toInt() shl 8) or ((c and 0xFF) * k).toInt()

        /** Three of these approximate a Gaussian. Opaque output (alpha forced to 255). */
        fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            blurPass(px, tmp, w, h, r, horizontal = true)
            blurPass(tmp, px, w, h, r, horizontal = false)
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val div = 2 * r + 1
            for (l in 0 until lines) {
                fun at(i: Int): Int {
                    val k = i.coerceIn(0, len - 1)
                    return if (horizontal) src[l * w + k] else src[k * w + l]
                }
                var sr = 0
                var sg = 0
                var sb = 0
                for (i in -r..r) {
                    val c = at(i)
                    sr += c shr 16 and 0xFF; sg += c shr 8 and 0xFF; sb += c and 0xFF
                }
                for (i in 0 until len) {
                    dst[if (horizontal) l * w + i else i * w + l] =
                        (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val add = at(i + r + 1)
                    val rem = at(i - r)
                    sr += (add shr 16 and 0xFF) - (rem shr 16 and 0xFF)
                    sg += (add shr 8 and 0xFF) - (rem shr 8 and 0xFF)
                    sb += (add and 0xFF) - (rem and 0xFF)
                }
            }
        }
    }

    /**
     * Frosted + refracted image for a rounded-rect panel whose top-left sits at (left, top)
     * on screen. Inside the [bezel] band the sample point is pulled inward along the edge
     * normal by `lensProfile(t) * maxShift`, which is what makes the edge look like thick glass.
     */
    fun render(left: Int, top: Int, w: Int, h: Int, radius: Float, bezel: Float, maxShift: Float): Bitmap {
        val out = IntArray(w * h)
        val hx = w / 2f
        val hy = h / 2f
        val r = min(radius, min(hx, hy))
        for (j in 0 until h) {
            for (i in 0 until w) {
                // Signed distance to the rounded rect (negative inside).
                val px = i + 0.5f - hx
                val py = j + 0.5f - hy
                val qx = abs(px) - (hx - r)
                val qy = abs(py) - (hy - r)
                val ox = max(qx, 0f)
                val oy = max(qy, 0f)
                val outside = sqrt(ox * ox + oy * oy)
                val depth = -(outside + min(max(qx, qy), 0f) - r) // distance in from the edge

                var sx = left + i + 0.5f
                var sy = top + j + 0.5f
                if (depth in 0f..bezel) {
                    val shift = lensProfile(depth / bezel).coerceIn(0f, 1f) * maxShift
                    // Outward edge normal: radial in the corners, axis-aligned along the sides.
                    var nx: Float
                    var ny: Float
                    if (outside > 0f) { nx = ox / outside; ny = oy / outside }
                    else if (qx > qy) { nx = 1f; ny = 0f }
                    else { nx = 0f; ny = 1f }
                    nx *= sign(px)
                    ny *= sign(py)
                    sx -= nx * shift
                    sy -= ny * shift
                }
                out[j * w + i] = sample(sx, sy)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(out, 0, w, 0, 0, w, h) } // mutable
    }
}

/**
 * A container that draws itself as a Liquid Glass panel over the [Backdrop]. Like Apple's, it
 * reads the photo behind it: in Auto it turns frosted over bright content and smoked over dark
 * ([light], announced through [onTone] so the owner can recolor its text), and it casts a soft
 * shadow to sit above busy content. [thick] panels (big, full of text) are more opaque and cast
 * a deeper shadow.
 */
class GlassView(context: Context, private val radius: Float, private val thick: Boolean = false) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val bezel = min(radius, (if (thick) 22f else 18f) * density)
    private val maxShift = (if (thick) 16f else 12f) * density

    /** A new backdrop re-reads [Look], so switching appearance re-bakes every panel. */
    var backdrop: Backdrop? = null
        set(v) { field = v; cacheKey = NO_KEY; pendingKey = NO_KEY; restyle(); invalidate() }

    /** This panel's current tone: frosted (true) or smoked. Set by the last bake. */
    var light = Look.light
        private set

    /** Called on the UI thread when [light] flips, after the glass has been re-baked. */
    var onTone: ((Boolean) -> Unit)? = null

    /** Brighter tint while focused (used by buttons). */
    var highlighted = false
        set(v) { field = v; restyle(); invalidate() }

    // What the baked glass was made for, packed into one Long: checked every frame, so no allocation.
    private var cacheKey = NO_KEY
    private var pendingKey = NO_KEY
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Shadow: blurred once per size at 1/4 scale, drawn outside the panel's own outline only.
    private val shadowSpread = (if (thick) 30f else 14f) * density
    private val shadowDrop = (if (thick) 10f else 4f) * density
    private val shadowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowDst = RectF()
    private var shadow: Bitmap? = null

    init {
        setWillNotDraw(false)
        defaultFocusHighlightEnabled = false
        restyle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shadowDst.set(-shadowSpread, -shadowSpread + shadowDrop, w + shadowSpread, h + shadowSpread + shadowDrop)
        shadow = makeShadow(w, h)
        restyle()
    }

    private fun makeShadow(w: Int, h: Int): Bitmap? {
        if (w == 0 || h == 0) return null
        val s = 4f
        val m = shadowSpread
        val bmp = Bitmap.createBitmap(((w + 2 * m) / s).toInt().coerceAtLeast(1), ((h + 2 * m) / s).toInt().coerceAtLeast(1),
            Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        canvas.drawRoundRect(m / s, m / s, (m + w) / s, (m + h) / s, radius / s, radius / s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(m / s * 0.6f, BlurMaskFilter.Blur.NORMAL) })
        // Cut out where the panel itself sits (the shadow is drawn [shadowDrop] lower), once, here:
        // clipping a rounded path on every frame is one of the costlier things a TV GPU can do.
        val d = shadowDrop / s
        canvas.drawRoundRect(m / s, m / s - d, (m + w) / s, (m + h) / s - d, radius / s, radius / s,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        return bmp
    }

    /** Pick up a changed [Look] (e.g. transparency) using the backdrop already loaded. */
    fun retint() { restyle(); invalidate() }

    /** Tint for the unbaked fallback, plus a sheen from the top and a rim lit mostly top-left. */
    private fun restyle() {
        tint.color = if (highlighted) Look.glassFocused(light) else Look.glassTint(light, thick)
        shadowPaint.color = Color.argb(Look.shadowAlpha(light, thick), 0, 0, 0)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val rimBoost = if (light) 1.6f else 1f // frosted glass has a brighter edge
        sheen.shader = sheenShader(h, light)
        rim.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(Color.argb((120 * rimBoost).toInt(), 255, 255, 255), Color.argb((18 * rimBoost).toInt(), 255, 255, 255),
                Color.argb((55 * rimBoost).toInt(), 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    private fun sheenShader(h: Float, light: Boolean) = LinearGradient(0f, 0f, 0f, h * 0.6f,
        Color.argb(Look.sheenAlpha(light), 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)

    override fun dispatchDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        requestGlass()
        // Only around the panel (the middle is cut out): under translucent glass it would muddy the tint.
        shadow?.let { canvas.drawBitmap(it, null, shadowDst, shadowPaint) }
        if (glass.shader != null) {
            // Tint and sheen are baked into the glass bitmap: one textured pass per frame, not three.
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, glass)
            if (highlighted) canvas.drawRoundRect(0f, 0f, w, h, radius, radius, tint)
        } else {
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, tint)
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, sheen)
        }
        val i = rim.strokeWidth / 2
        canvas.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, rim)
        super.dispatchDraw(canvas)
    }

    /** Position, size and the [Look] settings that shape the bake: 13 bits each for geometry. */
    private fun bakeKey(x: Int, y: Int, w: Int, h: Int): Long {
        fun f(v: Int) = (v and 0x1FFF).toLong()
        return (f(x) shl 45) or (f(y) shl 32) or (f(w) shl 19) or (f(h) shl 6) or
            (Look.mode.ordinal.toLong() shl 4) or Look.transparency.toLong()
    }

    /** Re-render our patch of the backdrop when our layout position or the [Look] changes. */
    private fun requestGlass() {
        val b = backdrop ?: return
        if (width == 0 || height == 0) return
        // Layout position in the window, ignoring scale/translation animations on purpose.
        var x = left
        var y = top
        var p = parent
        while (p is View) { x += p.left - p.scrollX; y += p.top - p.scrollY; p = p.parent }
        val key = bakeKey(x, y, width, height)
        if (key == cacheKey || key == pendingKey) return
        pendingKey = key
        val w = width
        val h = height
        val current = light
        Glass.worker.execute {
            // Decide the tone from what's actually behind us, then bake glass in that tone.
            val luminance = b.luminance(x, y, w, h)
            val tone = Look.toneFor(luminance, current)
            val guard = Look.clarityGuard(tone, luminance)
            val baking = b.render(x, y, w, h, radius, bezel, maxShift)
            Canvas(baking).apply {
                if (guard != 0) drawColor(guard)
                drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { color = Look.glassTint(tone, thick) })
                drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sheenShader(h.toFloat(), tone) })
            }
            // Must be immutable: the renderer re-copies and re-uploads a *mutable* bitmap used in a
            // shader on every frame (measured: ~80 ms frames). An immutable one is uploaded once.
            val bmp = baking.copy(Bitmap.Config.ARGB_8888, false)
            baking.recycle()
            post {
                // Same key but a newer backdrop arrived meanwhile? Then this bake is stale: drop it.
                if (pendingKey == key && backdrop === b) {
                    glass.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    cacheKey = key
                    pendingKey = NO_KEY
                    val flipped = tone != light
                    light = tone
                    restyle()
                    invalidate()
                    if (flipped) onTone?.invoke(tone)
                }
            }
        }
    }

    private companion object {
        const val NO_KEY = -1L
    }
}

/**
 * App artwork that turns into glass when focused (tvOS: "image views adopt Liquid Glass when
 * they gain focus"): a glint of light that sweeps with [sway], a top sheen and a lit rim.
 * Drawn inside the view so it follows the scale / 3D tilt applied to it.
 */
class TileImage(context: Context, private val radius: Float) : ImageView(context) {
    var lit = false
        set(v) { field = v; invalidate() }

    /** -1f..1f, driven by the activity's sway animator while focused. */
    var sway = 0f
        set(v) { field = v; if (lit) invalidate() }

    private val glintMatrix = Matrix()
    private val glint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    init {
        outlineProvider = RoundedOutline(radius)
        clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        glint.shader = LinearGradient(-w * 0.35f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, Color.argb(80, 255, 255, 255), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        sheen.shader = LinearGradient(0f, 0f, 0f, h * 0.5f,
            Color.argb(50, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.argb(230, 255, 255, 255), Color.argb(50, 255, 255, 255), Color.argb(140, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!lit) return
        val w = width.toFloat()
        val h = height.toFloat()
        glintMatrix.setTranslate(w * (0.5f + 0.4f * sway), 0f)
        glint.shader.setLocalMatrix(glintMatrix)
        canvas.drawRect(0f, 0f, w, h, glint)
        canvas.drawRect(0f, 0f, w, h, sheen)
        val i = rim.strokeWidth / 2
        canvas.drawRoundRect(i, i, w - i, h - i, radius - i, radius - i, rim)
    }
}

/**
 * Top Shelf: the focused app's artwork, scaled to fill, heavily blurred and faded out toward the
 * dock so the dock's glass still sits on plain wallpaper. The blur is done once per focus change
 * on a tiny copy (1/[SHRINK] size) and stretched back up, so it works the same on Android 9 as
 * on 12+ (where RenderEffect exists) and costs nothing per frame.
 */
class ShelfView(context: Context) : View(context) {
    var art: Drawable? = null
        set(v) { field = v; baked = null; invalidate() }

    private var baked: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { baked = null }

    /** One plain bitmap, so a fading shelf needs no offscreen layer of its own. */
    override fun hasOverlappingRendering() = false

    /**
     * Art centre-cropped into a small bitmap, box-blurred three times (~Gaussian), darkened and faded
     * toward the dock: all baked in once, so drawing is a single stretched bitmap with no layers.
     */
    private fun bake(d: Drawable): Bitmap {
        val sw = max(1, width / SHRINK)
        val sh = max(1, height / SHRINK)
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: sw
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: sh
        val scale = max(sw / iw.toFloat(), sh / ih.toFloat())
        val dw = iw * scale
        val dh = ih * scale
        val bounds = d.copyBounds()
        d.setBounds(((sw - dw) / 2).toInt(), ((sh - dh) / 2).toInt(), ((sw + dw) / 2).toInt(), ((sh + dh) / 2).toInt())
        val canvas = Canvas(small)
        d.draw(canvas)
        d.bounds = bounds
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { Backdrop.boxBlur(px, sw, sh, 3) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        canvas.drawColor(Color.argb(170, 0, 0, 0)) // keeps bright art (e.g. YouTube) dark
        canvas.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            shader = LinearGradient(0f, 0f, 0f, sh.toFloat(), intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        })
        return small.copy(Bitmap.Config.ARGB_8888, false).also { small.recycle() } // immutable: uploaded once
    }

    override fun onDraw(canvas: Canvas) {
        val d = art ?: return
        if (width == 0 || height == 0) return
        val b = baked ?: bake(d).also { baked = it }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, null, dst, paint)
    }

    private companion object {
        const val SHRINK = 12 // 1920x370 -> 160x30; three r=3 passes then ~40px of blur on screen
    }
}

/** Full-screen wallpaper that dissolves into each new [bitmap]. */
class WallpaperView(context: Context) : View(context) {
    private var previous: Bitmap? = null
    private var fade = 1f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val crossfade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500
        addUpdateListener { fade = it.animatedValue as Float; invalidate() }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { previous = null }
        })
    }

    /** Crossfade length for the next [bitmap] change: slow for auto-rotation, quick when scrolling. */
    var fadeMs = 1500L

    /** Darken the next [bitmap] at draw time (photos are GPU bitmaps, so they can't be edited). */
    var dim = false
    private var currentDim = false
    private var previousDim = false

    var bitmap: Bitmap? = null
        set(v) {
            previous = field
            previousDim = currentDim
            field = v
            currentDim = dim
            crossfade.duration = fadeMs
            if (previous == null) { fade = 1f; invalidate() } else crossfade.start()
        }

    override fun onDraw(canvas: Canvas) {
        previous?.let { draw(canvas, it, 255, previousDim) }
        bitmap?.let { draw(canvas, it, (fade * 255).toInt(), currentDim) }
    }

    private val dst = RectF()

    /** Stretched to fill: a no-op for screen-sized photos, an upscale for the small blurred copy. */
    private fun draw(canvas: Canvas, b: Bitmap, alpha: Int, dimmed: Boolean) {
        paint.alpha = alpha
        paint.colorFilter = if (dimmed) DIM_FILTER else null
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(b, null, dst, paint)
    }

    private companion object {
        val DIM_FILTER = ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(Backdrop.DIM, Backdrop.DIM, Backdrop.DIM, 1f)
        })
    }
}

class RoundedOutline(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) =
        outline.setRoundRect(0, 0, view.width, view.height, radius)
}
