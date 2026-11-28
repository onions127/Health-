package com.jcwear.coach.ui.sky

import android.content.Context
import com.jcwear.coach.domain.sky.SceneParams
import com.jcwear.coach.domain.sky.SkyState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Baked skies on this phone (lock plan step 3, round RP3; the prototype's 3.12-3.14 bakes). A set is one day's whole sky (its
 * SkyState and the day's formation seed) at one frame size, from one version of the scene shaders: the four depth slices of
 * each keyframe hour ([SceneParams.KEYFRAMES]) as baked by [SkyRenderer.bake], colour as RGBM in sqrt space and an aux image
 * (coverage, wind material), the prototype's format. A keyframe is complete when its marker file is written.
 *
 * Files are raw RGBA rows (bottom-up, as read back), PNG's "up" filter, deflated: exact bytes (an Android Bitmap would
 * premultiply the RGBM alpha), and no image codec to trust.
 *
 * One day kept (lock plan, bakes): starting a set deletes sets of earlier days and other sets of the same day.
 */
object SkyBakes {
    private const val FMT = 1

    data class Key(val sky: SkyState, val day: Int, val w: Int, val h: Int, val shader: String) {
        val id: String get() = "$day-" + sha1("$sky|$w|$h|$shader|$FMT").take(12)
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    @Volatile private var shaderV: String? = null
    /** The scene shaders' version: a set from older shaders is never played (the prototype bumps SKY_CACHE for the same reason). */
    fun shader(ctx: Context): String = shaderV ?: sha1(listOf("SKY", "LAND", "ENCODE").joinToString("") {
        ctx.assets.open("sky/$it.glsl").bufferedReader().readText() }).take(12).also { shaderV = it }

    fun key(ctx: Context, sky: SkyState, day: Int, w: Int, h: Int) = Key(sky, day, w, h, shader(ctx))
    private fun root(ctx: Context) = File(ctx.filesDir, "sky-bakes")
    fun dir(ctx: Context, k: Key) = File(root(ctx), k.id)
    private fun hourName(h: Double) = "%05.2f".format(java.util.Locale.ROOT, h)
    fun file(dir: File, hour: Double, slice: Int, aux: Boolean) = File(dir, "${hourName(hour)}-s$slice-${if (aux) 'a' else 'c'}.skb")
    private fun marker(dir: File, hour: Double) = File(dir, "${hourName(hour)}.ok")
    fun has(dir: File, hour: Double) = marker(dir, hour).exists()
    fun done(dir: File, hour: Double, note: String) = marker(dir, hour).writeText(note)
    /** The keyframe hours baked in this set. */
    fun hours(ctx: Context, k: Key): List<Double> = SceneParams.KEYFRAMES.filter { has(dir(ctx, k), it) }
    fun bytes(ctx: Context, k: Key): Long = dir(ctx, k).listFiles()?.sumOf { it.length() } ?: 0L

    /** Before baking [k]: drop earlier days' sets and the day's other sets (another sky, frame or shader). */
    fun prune(ctx: Context, k: Key, today: Int) {
        root(ctx).listFiles()?.forEach { d ->
            val day = d.name.substringBefore('-').toIntOrNull()
            if (day == null || day < today || (day == k.day && d.name != k.id)) d.deleteRecursively()
        }
        dir(ctx, k).mkdirs()
        File(dir(ctx, k), "set.txt").writeText("${k.sky}\nday ${k.day}, frame ${k.w}x${k.h}, shader ${k.shader}, format $FMT\n")
    }

    // ---------------------------------------------------------------- the last sky shown, for the nightly bake
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sky_bakes", Context.MODE_PRIVATE)
    /** What the nightly bake needs from the screen: today's readiness (the likely sky tomorrow) and the frame it is drawn at. */
    fun remember(ctx: Context, readiness: Int?, w: Int, h: Int) =
        prefs(ctx).edit().putInt("readiness", readiness ?: -1).putInt("w", w).putInt("h", h).apply()
    fun lastReadiness(ctx: Context): Int? = prefs(ctx).getInt("readiness", -1).takeIf { it >= 0 }
    fun lastFrame(ctx: Context): Pair<Int, Int>? = prefs(ctx).let { p -> if (p.contains("w")) p.getInt("w", 824) to p.getInt("h", 1830) else null }

    // ---------------------------------------------------------------- the image files
    fun write(f: File, w: Int, h: Int, px: ByteBuffer) {
        val row = w * 4; val a = ByteArray(row * h); px.position(0); px.get(a)
        for (i in a.size - 1 downTo row) a[i] = (a[i] - a[i - row]).toByte()      // "up" filter, from the bottom so each row still sees its original neighbour
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { o ->
            o.writeInt(0x534B4231); o.writeInt(w); o.writeInt(h)                   // "SKB1"
            DeflaterOutputStream(o, Deflater(6), 1 shl 16).use { it.write(a) }
        }
        check(tmp.renameTo(f)) { "rename ${f.name}" }
    }
    class Image(val w: Int, val h: Int, val px: ByteBuffer)
    fun read(f: File): Image {
        DataInputStream(f.inputStream().buffered(1 shl 16)).use { i ->
            check(i.readInt() == 0x534B4231) { "${f.name}: not a sky bake" }
            val w = i.readInt(); val h = i.readInt(); val row = w * 4; val a = ByteArray(row * h)
            DataInputStream(InflaterInputStream(i, java.util.zip.Inflater(), 1 shl 16)).readFully(a)
            for (j in row until a.size) a[j] = (a[j] + a[j - row]).toByte()
            val bb = ByteBuffer.allocateDirect(a.size).order(ByteOrder.nativeOrder()); bb.put(a); bb.position(0)
            return Image(w, h, bb)
        }
    }
}
