package io.github.mattdillioncodes.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * A minimal QR code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1-10,
 * enough for a URL of up to ~210 characters. Glass has no library dependencies, and a URL is all
 * it ever encodes, so this covers exactly that and nothing more.
 *
 * [encode] returns the module grid, `[row][column]`, true = dark.
 */
object Qr {
    // Error correction level M, per version (index 0 unused): codewords per block, number of blocks.
    private val EC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
    private val NUM_BLOCKS = intArrayOf(0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
    private const val FORMAT_M = 0 // ECC level bits: L=1, M=0, Q=3, H=2

    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = (1..10).firstOrNull { v ->
            4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8
        } ?: throw IllegalArgumentException("Too long for a QR code: ${data.size} bytes")
        val grid = Grid(version)
        grid.drawFunctionPatterns()
        grid.drawCodewords(addEcc(version, dataBits(version, data)))
        // Try all eight masks and keep the one that's easiest to scan.
        var best = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            grid.applyMask(mask)
            grid.drawFormat(mask)
            val p = grid.penalty()
            if (p < bestPenalty) { best = mask; bestPenalty = p }
            grid.applyMask(mask) // XOR again: undo
        }
        grid.applyMask(best)
        grid.drawFormat(best)
        return grid.modules
    }

    private fun rawCodewords(v: Int): Int {
        var modules = (16 * v + 128) * v + 64
        if (v >= 2) {
            val align = v / 7 + 2
            modules -= (25 * align - 10) * align - 55
            if (v >= 7) modules -= 36
        }
        return modules / 8
    }

    private fun dataCodewords(v: Int) = rawCodewords(v) - EC_PER_BLOCK[v] * NUM_BLOCKS[v]

    /** Mode + length + bytes, then terminator and padding up to the version's data capacity. */
    private fun dataBits(v: Int, data: ByteArray): ByteArray {
        val bits = ArrayList<Boolean>()
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
        put(0b0100, 4)
        put(data.size, if (v < 10) 8 else 16)
        data.forEach { put(it.toInt() and 0xFF, 8) }
        val capacity = dataCodewords(v) * 8
        put(0, minOf(4, capacity - bits.size))
        put(0, (8 - bits.size % 8) % 8)
        var pad = 0xEC
        while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
        return ByteArray(bits.size / 8) { i -> (0 until 8).fold(0) { acc, b -> (acc shl 1) or (if (bits[i * 8 + b]) 1 else 0) }.toByte() }
    }

    /** Splits data into blocks, appends Reed-Solomon codewords to each, and interleaves them. */
    private fun addEcc(v: Int, data: ByteArray): ByteArray {
        val numBlocks = NUM_BLOCKS[v]
        val ecLen = EC_PER_BLOCK[v]
        val raw = rawCodewords(v)
        val numShort = numBlocks - raw % numBlocks
        val shortLen = raw / numBlocks
        val divisor = rsDivisor(ecLen)
        val blocks = ArrayList<ByteArray>()
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortLen - ecLen + if (i < numShort) 0 else 1
            val dat = data.copyOfRange(k, k + datLen)
            k += datLen
            val ecc = rsRemainder(dat, divisor)
            // Short blocks get a placeholder byte so all blocks line up for interleaving.
            blocks += (if (i < numShort) dat + byteArrayOf(0) else dat) + ecc
        }
        val out = ArrayList<Byte>()
        for (i in blocks[0].indices) for (j in blocks.indices) {
            if (i != shortLen - ecLen || j >= numShort) out += blocks[j][i]
        }
        return out.toByteArray()
    }

    private fun gfMul(x: Int, y: Int): Int { // GF(2^8) with the QR polynomial 0x11D
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val result = IntArray(degree).also { it[degree - 1] = 1 }
        var root = 1
        repeat(degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 0x02)
        }
        return result
    }

    private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private class Grid(val version: Int) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        private val isFunction = Array(size) { BooleanArray(size) }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) } // timing
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val align = alignmentPositions()
            for (i in align.indices) for (j in align.indices) {
                val corner = (i == 0 && j == 0) || (i == 0 && j == align.lastIndex) || (i == align.lastIndex && j == 0)
                if (!corner) alignment(align[i], align[j])
            }
            drawFormat(0) // reserve the area; real bits come after masking
            if (version >= 7) drawVersion()
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = max(abs(dx), abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) set(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun alignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) set(x + dx, y + dy, max(abs(dx), abs(dy)) != 1)
        }

        private fun alignmentPositions(): List<Int> {
            if (version == 1) return emptyList()
            val count = version / 7 + 2
            val step = (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2
            val result = mutableListOf(6)
            var pos = size - 7
            while (result.size < count) { result.add(1, pos); pos -= step }
            return result
        }

        fun drawFormat(mask: Int) {
            val data = FORMAT_M shl 3 or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = (data shl 10 or rem) xor 0x5412
            fun bit(i: Int) = (bits ushr i) and 1 != 0
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
            set(8, size - 8, true) // the dark module
        }

        private fun drawVersion() {
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = version shl 12 or rem
            for (i in 0 until 18) {
                val dark = (bits ushr i) and 1 != 0
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, dark); set(b, a, dark)
            }
        }

        /** Places data bits in the zigzag order, two columns at a time, skipping function patterns. */
        fun drawCodewords(data: ByteArray) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
                right -= 2
            }
        }

        fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFunction[y][x]) continue
                val flip = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (flip) modules[y][x] = !modules[y][x]
            }
        }

        /** The standard's penalty score: long runs, 2x2 blocks, finder look-alikes, dark/light balance. */
        fun penalty(): Int {
            var score = 0
            for (horizontal in listOf(true, false)) for (a in 0 until size) {
                var run = 0
                var last = false
                val line = BooleanArray(size) { b -> if (horizontal) modules[a][b] else modules[b][a] }
                for (b in 0 until size) {
                    if (b > 0 && line[b] == last) run++ else { if (run >= 5) score += run - 2; run = 1; last = line[b] }
                }
                if (run >= 5) score += run - 2
                // Rule 3: 1:1:3:1:1 finder-like pattern with 4 light modules on one side.
                for (b in 0..size - 11) {
                    val p = listOf(true, false, true, true, true, false, true)
                    val core = p.indices.all { line[b + it + 4] == p[it] } && (0..3).none { line[b + it] }
                    val core2 = p.indices.all { line[b + it] == p[it] } && (7..10).none { line[b + it] }
                    if (core || core2) score += 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) score += 3
            }
            val dark = modules.sumOf { row -> row.count { it } }
            val total = size * size
            score += (abs(dark * 20 - total * 10) + total - 1) / total * 10 - 10
            return score
        }
    }
}

/** Draws a QR code: dark modules on white with the standard 4-module quiet zone, in both themes. */
class QrView(context: Context) : View(context) {
    var text: String? = null
        set(v) { field = v; modules = v?.let { runCatching { Qr.encode(it) }.getOrNull() }; invalidate() }

    private var modules: Array<BooleanArray>? = null
    private val dark = Paint().apply { color = Color.BLACK }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val m = modules ?: return
        val count = m.size + 8 // quiet zone of 4 modules each side
        val cell = minOf(width, height) / count.toFloat()
        val side = cell * count
        val left = (width - side) / 2
        val top = (height - side) / 2
        canvas.drawRoundRect(left, top, left + side, top + side, cell * 2, cell * 2, light)
        for (y in m.indices) for (x in m.indices) if (m[y][x]) {
            val px = left + (x + 4) * cell
            val py = top + (y + 4) * cell
            canvas.drawRect(px, py, px + cell + 0.5f, py + cell + 0.5f, dark) // +0.5: no hairline gaps
        }
    }
}
