package com.jcwear.coach.domain.parity

import java.io.File

/** Tab-separated fixtures written by tools/visual-loop/parity.js from the design prototype. First line = column names. */
object Fixtures {
    class Row(private val cols: Map<String, String>) {
        operator fun get(k: String): String = cols[k] ?: error("no column $k in $cols")
        fun d(k: String): Double = get(k).toDouble()
        fun i(k: String): Int = get(k).toInt()
        fun dOrNull(k: String): Double? = get(k).takeIf { it.isNotEmpty() && it != "null" }?.toDouble()
        fun iOrNull(k: String): Int? = get(k).takeIf { it.isNotEmpty() && it != "null" }?.toInt()
        override fun toString() = cols.toString()
    }

    /**
     * A PNG as packed 0xRRGGBB, top row first. Only what the prototype's renders use: 8-bit RGB or RGBA, not interlaced
     * (javax.imageio is not on the Android unit-test classpath).
     */
    fun png(file: File): Triple<IntArray, Int, Int> {
        val inp = java.io.DataInputStream(file.inputStream().buffered())
        inp.skipBytes(8)
        var w = 0; var h = 0; var ct = 0
        val idat = java.io.ByteArrayOutputStream()
        while (true) {
            val len = inp.readInt(); val type = String(ByteArray(4).also { inp.readFully(it) }, Charsets.US_ASCII)
            val data = ByteArray(len).also { inp.readFully(it) }; inp.readInt()
            when (type) {
                "IHDR" -> { val b = java.nio.ByteBuffer.wrap(data); w = b.int; h = b.int; require(data[8].toInt() == 8 && data[12].toInt() == 0) { "8-bit, not interlaced" }; ct = data[9].toInt() }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val bpp = when (ct) { 2 -> 3; 6 -> 4; else -> error("PNG colour type $ct") }
        val raw = java.util.zip.InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        val stride = w * bpp
        val out = IntArray(w * h); val prev = IntArray(stride); val cur = IntArray(stride)
        var o = 0
        for (y in 0 until h) {
            val f = raw[o++].toInt()
            for (x in 0 until stride) {
                val v = raw[o++].toInt() and 0xFF
                val a = if (x >= bpp) cur[x - bpp] else 0; val b = prev[x]; val c = if (x >= bpp) prev[x - bpp] else 0
                cur[x] = (v + when (f) {
                    0 -> 0; 1 -> a; 2 -> b; 3 -> (a + b) / 2
                    4 -> { val p = a + b - c; val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c }
                    else -> error("filter $f")
                }) and 0xFF
            }
            for (x in 0 until w) out[y * w + x] = (cur[x * bpp] shl 16) or (cur[x * bpp + 1] shl 8) or cur[x * bpp + 2]
            cur.copyInto(prev)
        }
        return Triple(out, w, h)
    }

    fun tsv(name: String): List<Row> {
        val lines = File("src/test/resources/parity/$name").readLines().filter { it.isNotBlank() }
        val head = lines.first().split('\t')
        return lines.drop(1).map { line -> Row(head.zip(line.split('\t')).toMap()) }
    }
}
