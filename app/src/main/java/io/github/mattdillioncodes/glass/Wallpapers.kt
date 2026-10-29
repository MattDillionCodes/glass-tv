package io.github.mattdillioncodes.glass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.ExifInterface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * A loaded wallpaper: [display] is what's drawn full-screen (a GPU-resident HARDWARE bitmap for
 * photos: no CPU copy, no texture upload), [small] is a 1/[Backdrop.SCALE] software copy the glass
 * blurs and samples, and [dim] says to darken it at draw time (photos, for legible text in dark mode).
 */
class Wallpaper(val display: Bitmap, val small: Bitmap, val dim: Boolean)

/**
 * Where photos come from. A photo is a path string: "asset:wallpapers/<set>/<name>.webp" for the
 * sets built into the app, or a file path for the user's own photos. Those arrive through
 * [importPhoto] (uploaded from a phone, or picked on the TV), or with
 *   adb push *.jpg /sdcard/Android/data/io.github.mattdillioncodes.glass/files/wallpapers/
 * Exactly the screen size (1920x1080) is fastest. With nothing chosen, a colorful wallpaper is
 * generated (glass needs color behind it to read as glass).
 */
object Wallpapers {
    private val photoTypes = setOf("jpg", "jpeg", "png", "webp")
    private const val ASSET = "asset:"

    /** Built-in sets, as folder name -> what Settings calls it. */
    val SETS = linkedMapOf("abstract" to "Abstract", "digital" to "Digital", "landscapes" to "Landscapes", "wildlife" to "Wildlife")
    const val OWN = "own" // the user's photos, when there are any

    private fun ownDir(context: Context) = File(context.getExternalFilesDir(null), "wallpapers")

    fun ownPhotos(context: Context): List<String> =
        ownDir(context)
            .listFiles { f -> f.extension.lowercase() in photoTypes }
            ?.sortedBy { it.name }?.map { it.path } ?: emptyList()

    /**
     * Every photo in the sets the user has turned on, in a stable order (for "Photo 3 of 24").
     * Called on every photo change, so it's cached: the built-in sets never change, and the user's
     * folder is only re-listed when its modified time does (adding or deleting a file bumps it).
     */
    fun photos(context: Context): List<String> {
        val chosen = Prefs(context).wallpaperSets
        val own = if (OWN in chosen) cachedOwn(context) else emptyList()
        return own + SETS.keys.filter { it in chosen }.flatMap { set ->
            builtIn.getOrPut(set) {
                context.assets.list("wallpapers/$set").orEmpty().sorted().map { "${ASSET}wallpapers/$set/$it" }
            }
        }
    }

    private val builtIn = HashMap<String, List<String>>()
    private var ownList: List<String> = emptyList()
    private var ownStamp = -1L

    @Synchronized private fun forgetOwnList() { ownStamp = -1L }

    @Synchronized private fun cachedOwn(context: Context): List<String> {
        val stamp = ownDir(context).lastModified()
        if (stamp != ownStamp) { ownList = ownPhotos(context); ownStamp = stamp }
        return ownList
    }

    /**
     * Adds one of the user's photos: turned upright (camera EXIF), cropped to fill the screen and
     * saved as a screen-size sRGB JPEG, the format Home loads fastest. Returns null if [source]
     * isn't an image Android can read.
     */
    fun importPhoto(context: Context, source: File): File? {
        val m = context.resources.displayMetrics
        val w = max(m.widthPixels, m.heightPixels)
        val h = min(m.widthPixels, m.heightPixels)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) return null
        val decoded = decodeSampled(context, source.path, bounds, w, h) ?: return null
        val degrees = runCatching {
            when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        val upright = if (degrees == 0f) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(degrees) }, true)
                .also { decoded.recycle() }
        val fitted = centerCrop(upright, w, h)
        val dir = ownDir(context).apply { mkdirs() }
        val out = File(dir, "photo-${System.currentTimeMillis()}-${imported.incrementAndGet()}.jpg")
        out.outputStream().buffered().use { fitted.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        fitted.recycle()
        forgetOwnList() // folder times can be coarse (1 s); don't rely on them alone
        return out
    }

    private val imported = AtomicInteger()

    /** Deletes every photo the user added (the built-in sets stay). */
    fun removeOwnPhotos(context: Context) {
        ownDir(context).listFiles()?.forEach { it.delete() }
        forgetOwnList()
    }

    fun load(context: Context, w: Int, h: Int, photo: String? = null): Wallpaper {
        if (photo != null) loadPhoto(context, photo, w, h)?.let { return it }
        val generated = generate(w, h)
        return Wallpaper(generated, Bitmap.createScaledBitmap(generated, w / Backdrop.SCALE, h / Backdrop.SCALE, true), dim = false)
    }

    private fun decode(context: Context, photo: String, options: BitmapFactory.Options): Bitmap? = runCatching {
        val stream = if (photo.startsWith(ASSET)) context.assets.open(photo.removePrefix(ASSET)) else File(photo).inputStream()
        stream.buffered().use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun loadPhoto(context: Context, photo: String, w: Int, h: Int): Wallpaper? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(context, photo, bounds)
        if (bounds.outWidth <= 0) return null
        val exact = bounds.outWidth == w && bounds.outHeight == h
        val display = if (exact) {
            decode(context, photo, srgb())
        } else {
            // Any other size (4K screens, the user's own photos): decode near the target, then crop to fill.
            centerCrop(decodeSampled(context, photo, bounds, w, h) ?: return null, w, h)
        } ?: return null
        // The glass only needs a tiny copy: JPEG decodes at 1/4 or 1/8 scale almost for free.
        val tiny = decodeSampled(context, photo, bounds, w / Backdrop.SCALE, h / Backdrop.SCALE) ?: return null
        return Wallpaper(display, centerCrop(tiny, w / Backdrop.SCALE, h / Backdrop.SCALE), dim = true)
    }

    private fun decodeSampled(context: Context, photo: String, bounds: BitmapFactory.Options, w: Int, h: Int): Bitmap? {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        return decode(context, photo, srgb().apply { inSampleSize = sample })
    }

    /**
     * Always decode into sRGB. Camera/Mac JPEGs often carry Adobe RGB or Display P3 profiles; left
     * as-is, every frame converts every pixel of the full-screen photo (measured: ~24 ms -> ~80 ms).
     */
    private fun srgb() = BitmapFactory.Options().apply { inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val scale = max(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        Canvas(out).drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
            Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    private fun generate(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
                Color.rgb(4, 5, 12), Color.rgb(12, 9, 24), Shader.TileMode.CLAMP)
        })
        // Dark mode: the same colour glows, but deeper and dimmer so they read as ambient light.
        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            val x = cx * w
            val y = cy * h
            val radius = r * w
            c.drawCircle(x, y, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, radius, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            })
        }
        blob(0.18f, 0.30f, 0.45f, Color.argb(120, 200, 70, 40))
        blob(0.78f, 0.22f, 0.42f, Color.argb(130, 90, 60, 220))
        blob(0.62f, 0.88f, 0.50f, Color.argb(120, 0, 120, 200))
        blob(0.05f, 0.95f, 0.35f, Color.argb(100, 190, 30, 110))
        return out
    }
}
