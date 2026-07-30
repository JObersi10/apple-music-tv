package com.applemusicktv.util

/**
 * Minimal QR encoder — byte mode, error-correction level M, versions 1-6.
 *
 * Enough for a `http://<ip>:8080` URL (25-30 bytes) and nothing more, which is the
 * only thing the app needs to encode. Hand-rolled rather than pulled from a library
 * for one screen.
 *
 * Verified end-to-end: the generated matrices decode correctly through OpenCV's
 * QRCodeDetector for a range of LAN addresses. Note that it does NOT match segno
 * byte-for-byte — segno emits an extra 0x00 codeword after the terminator. Both are
 * readable; don't "fix" this to chase a diff against another encoder.
 */
object QrCode {

    /** `matrix[row][col]` — true = dark module. Null if the text doesn't fit v1-6. */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val version = chooseVersion(bytes.size) ?: return null
        val codewords = interleave(encodeData(bytes, version), version)

        var best: Array<BooleanArray>? = null
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0..7) {
            val m = build(version, codewords, mask)
            placeFormat(m, mask)
            val p = penalty(m)
            if (p < bestPenalty) { bestPenalty = p; best = m }
        }
        return best
    }

    // ── GF(256) ──────────────────────────────────────────────────────────────
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** Generator polynomial for [n] EC codewords, highest-degree coefficient first. */
    private fun genPoly(n: Int): IntArray {
        var p = intArrayOf(1)
        for (i in 0 until n) {
            val np = IntArray(p.size + 1)
            for (j in p.indices) {
                np[j] = np[j] xor mul(p[j], EXP[i])
                np[j + 1] = np[j + 1] xor p[j]
            }
            p = np
        }
        return p.reversedArray()
    }

    private fun rsEc(data: IntArray, n: Int): IntArray {
        val g = genPoly(n)
        val res = IntArray(n)
        for (d in data) {
            val factor = d xor res[0]
            for (i in 0 until n - 1) res[i] = res[i + 1]
            res[n - 1] = 0
            for (i in 0 until n) res[i] = res[i] xor mul(g[i + 1], factor)
        }
        return res
    }

    // ── Version tables (level M) ─────────────────────────────────────────────
    // totalCodewords, ecPerBlock, blockCount
    private val SPEC = mapOf(
        1 to Triple(26, 10, 1),
        2 to Triple(44, 16, 1),
        3 to Triple(70, 26, 1),
        4 to Triple(100, 18, 2),
        5 to Triple(134, 24, 2),
        6 to Triple(172, 16, 4),
    )
    private val ALIGN = mapOf(
        1 to intArrayOf(), 2 to intArrayOf(6, 18), 3 to intArrayOf(6, 22),
        4 to intArrayOf(6, 26), 5 to intArrayOf(6, 30), 6 to intArrayOf(6, 34),
    )

    private fun dataCapacity(v: Int): Int {
        val (total, ec, blocks) = SPEC.getValue(v)
        return total - ec * blocks
    }

    private fun chooseVersion(nbytes: Int): Int? =
        (1..6).firstOrNull { nbytes + 2 <= dataCapacity(it) }

    private fun encodeData(bytes: ByteArray, v: Int): IntArray {
        val bits = ArrayList<Int>(dataCapacity(v) * 8)
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value shr i) and 1) }

        put(0b0100, 4)          // byte mode
        put(bytes.size, 8)      // count indicator is 8 bits for versions 1-9
        for (b in bytes) put(b.toInt() and 0xFF, 8)

        val cap = dataCapacity(v) * 8
        put(0, minOf(4, cap - bits.size))       // terminator
        while (bits.size % 8 != 0) bits.add(0)  // pad to byte boundary

        val cw = ArrayList<Int>(dataCapacity(v))
        for (i in bits.indices step 8) {
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or bits[i + j]
            cw.add(b)
        }
        val pad = intArrayOf(0xEC, 0x11)
        var i = 0
        while (cw.size < dataCapacity(v)) cw.add(pad[i++ % 2])
        return cw.toIntArray()
    }

    private fun interleave(data: IntArray, v: Int): IntArray {
        val (total, ecPer, blockCount) = SPEC.getValue(v)
        val dataTotal = total - ecPer * blockCount
        val base = dataTotal / blockCount
        val extra = dataTotal % blockCount

        val blocks = ArrayList<IntArray>(blockCount)
        var pos = 0
        for (i in 0 until blockCount) {
            // Longer blocks go last, per the spec's block layout.
            val n = base + if (i >= blockCount - extra) 1 else 0
            blocks.add(data.copyOfRange(pos, pos + n))
            pos += n
        }
        val ecs = blocks.map { rsEc(it, ecPer) }

        val out = ArrayList<Int>(total)
        val longest = blocks.maxOf { it.size }
        for (i in 0 until longest) for (b in blocks) if (i < b.size) out.add(b[i])
        for (i in 0 until ecPer) for (e in ecs) out.add(e[i])
        return out.toIntArray()
    }

    // ── Matrix ───────────────────────────────────────────────────────────────
    /** Function-pattern marker: cells left UNSET are where data goes. */
    private const val UNSET = -1

    private fun build(v: Int, codewords: IntArray, mask: Int): Array<BooleanArray> {
        val size = 17 + 4 * v
        val g = Array(size) { IntArray(size) { UNSET } }

        fun finder(row: Int, col: Int) {
            for (dr in -1..7) for (dc in -1..7) {
                val r = row + dr; val c = col + dc
                if (r !in 0 until size || c !in 0 until size) continue
                val inBox = dr in 0..6 && dc in 0..6
                val on = inBox && (dr == 0 || dr == 6 || dc == 0 || dc == 6 || (dr in 2..4 && dc in 2..4))
                g[r][c] = if (on) 1 else 0
            }
        }
        finder(0, 0); finder(0, size - 7); finder(size - 7, 0)

        for (i in 0 until size) {
            if (g[6][i] == UNSET) g[6][i] = if (i % 2 == 0) 1 else 0
            if (g[i][6] == UNSET) g[i][6] = if (i % 2 == 0) 1 else 0
        }

        for (r in ALIGN.getValue(v)) for (c in ALIGN.getValue(v)) {
            // Skip the three positions that collide with the finder patterns.
            if ((r < 9 && c < 9) || (r < 9 && c > size - 10) || (r > size - 10 && c < 9)) continue
            for (dr in -2..2) for (dc in -2..2) {
                val on = dr == -2 || dr == 2 || dc == -2 || dc == 2 || (dr == 0 && dc == 0)
                g[r + dr][c + dc] = if (on) 1 else 0
            }
        }

        // Reserve the format-information strips so data placement skips them.
        for (i in 0..8) {
            if (g[8][i] == UNSET) g[8][i] = 0
            if (g[i][8] == UNSET) g[i][8] = 0
        }
        for (i in 0 until 8) {
            if (g[8][size - 1 - i] == UNSET) g[8][size - 1 - i] = 0
            if (g[size - 1 - i][8] == UNSET) g[size - 1 - i][8] = 0
        }
        g[size - 8][8] = 1 // dark module

        // Zigzag data placement: column pairs right to left, skipping the timing column.
        val bits = ArrayList<Int>(codewords.size * 8)
        for (cw in codewords) for (i in 7 downTo 0) bits.add((cw shr i) and 1)

        var idx = 0
        var col = size - 1
        var upward = true
        while (col > 0) {
            if (col == 6) col--
            for (i in 0 until size) {
                val r = if (upward) size - 1 - i else i
                for (c in intArrayOf(col, col - 1)) {
                    if (g[r][c] != UNSET) continue
                    // Past the data stream, remaining modules are the version's
                    // "remainder bits" and stay 0 before masking.
                    var b = if (idx < bits.size) bits[idx] else 0
                    idx++
                    if (maskAt(mask, r, c)) b = b xor 1
                    g[r][c] = b
                }
            }
            upward = !upward
            col -= 2
        }
        return Array(size) { r -> BooleanArray(size) { c -> g[r][c] == 1 } }
    }

    private fun maskAt(k: Int, r: Int, c: Int): Boolean = when (k) {
        0 -> (r + c) % 2 == 0
        1 -> r % 2 == 0
        2 -> c % 3 == 0
        3 -> (r + c) % 3 == 0
        4 -> ((r / 2) + (c / 3)) % 2 == 0
        5 -> (r * c) % 2 + (r * c) % 3 == 0
        6 -> ((r * c) % 2 + (r * c) % 3) % 2 == 0
        else -> ((r + c) % 2 + (r * c) % 3) % 2 == 0
    }

    /** BCH(15,5) format information, computed rather than tabulated. */
    private fun formatBits(mask: Int): Int {
        val data = (0b00 shl 3) or mask   // 00 = error-correction level M
        var v = data shl 10
        for (i in 4 downTo 0) if (v and (1 shl (i + 10)) != 0) v = v xor (0b10100110111 shl i)
        return ((data shl 10) or v) xor 0b101010000010010
    }

    private fun placeFormat(m: Array<BooleanArray>, mask: Int) {
        val size = m.size
        val fmt = formatBits(mask)
        val bits = IntArray(15) { (fmt shr (14 - it)) and 1 }

        val around = arrayOf(
            8 to 0, 8 to 1, 8 to 2, 8 to 3, 8 to 4, 8 to 5, 8 to 7, 8 to 8,
            7 to 8, 5 to 8, 4 to 8, 3 to 8, 2 to 8, 1 to 8, 0 to 8,
        )
        for (i in 0 until 15) m[around[i].first][around[i].second] = bits[i] == 1

        val split = ArrayList<Pair<Int, Int>>(15)
        for (i in 1..7) split.add((size - i) to 8)
        for (i in 8 downTo 1) split.add(8 to (size - i))
        for (i in 0 until 15) m[split[i].first][split[i].second] = bits[i] == 1
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        var p = 0
        val lines = ArrayList<BooleanArray>(size * 2)
        for (r in 0 until size) lines.add(m[r])
        for (c in 0 until size) lines.add(BooleanArray(size) { m[it][c] })

        // Rule 1: runs of five or more same-coloured modules.
        for (line in lines) {
            var run = 1
            for (i in 1 until size) {
                if (line[i] == line[i - 1]) run++ else { if (run >= 5) p += 3 + (run - 5); run = 1 }
            }
            if (run >= 5) p += 3 + (run - 5)
        }
        // Rule 2: 2x2 blocks of one colour.
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            if (m[r][c] == m[r][c + 1] && m[r][c] == m[r + 1][c] && m[r][c] == m[r + 1][c + 1]) p += 3
        }
        // Rule 3: finder-like 1:1:3:1:1 patterns.
        val pat = booleanArrayOf(true, false, true, true, true, false, true, false, false, false, false)
        val rev = pat.reversedArray()
        for (line in lines) for (i in 0..size - 11) {
            var fwd = true; var bwd = true
            for (j in 0 until 11) {
                if (line[i + j] != pat[j]) fwd = false
                if (line[i + j] != rev[j]) bwd = false
            }
            if (fwd || bwd) p += 40
        }
        // Rule 4: deviation from a 50% dark ratio.
        var dark = 0
        for (r in 0 until size) for (c in 0 until size) if (m[r][c]) dark++
        p += 10 * kotlin.math.abs(dark * 20 / (size * size) - 10)
        return p
    }
}
