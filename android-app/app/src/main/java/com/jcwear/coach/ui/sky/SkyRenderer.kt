package com.jcwear.coach.ui.sky

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.jcwear.coach.domain.sky.SceneParams
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * The live sky (lock plan step 3, renderer A): the prototype's shaders unchanged on GLES 3.0, driven by [SceneParams].
 *
 * Set up as the S21 spike proved it (SP1, 2026-11-27): the scene goes straight to a SurfaceView's surface; the glass backdrop
 * (the finished frame at 1/8, blurred 22 CSS px, saturate 1.6) is one GPU image for Compose, sent only while the page scrolls
 * or the sky changes; at most one GPU frame in flight.
 *
 * Frames: a new sky converges over 24 jittered frames (shown as it sharpens), its clouds held still (a phone cannot afford the
 * prototype's drifting re-renders). Once converged, the wind, birds and stars move at 30 fps ([live]); a scroll re-composites
 * at once. [pause] stops all GPU work (the page is hidden).
 */
@RequiresApi(29)
class SkyRenderer(private val ctx: Context, val W: Int, val H: Int, private val dpr: Float) {
    val GW = W / 8; val GH = H / 8
    val glassReader: ImageReader = ImageReader.newInstance(GW, GH, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)

    private val thread = HandlerThread("sky-gl").also { it.start() }
    private val h = Handler(thread.looper)
    @Volatile private var running = true
    @Volatile private var paused = false
    private val wake = Object()

    @Volatile var live = true
    @Volatile private var scrollCss = 0f
    @Volatile private var input: SceneParams.Input? = null
    @Volatile private var inputGen = 0
    /** Converged and showing (for tests and the first-frame handoff). */
    @Volatile var converged = false; private set
    @Volatile var lastError: String? = null; private set

    fun start(out: Surface) { h.post { try { init(out); loop() } catch (t: Throwable) { lastError = t.message ?: t.toString(); Log.e(TAG, "sky renderer failed", t) } } }
    fun stop() { running = false; poke(); h.post { release() }; thread.quitSafely() }
    fun pause() { paused = true; Log.i(TAG, "frames: ${frames[0]} full, ${frames[1]} band") }
    fun resume() { paused = false; poke() }
    /** A new sky (or hour, season, seed): converges again. */
    fun set(i: SceneParams.Input) { input = i; inputGen++; poke() }
    /** The page's scroll in CSS px (412 wide): SC1 lifts and blurs the land. */
    fun setScroll(css: Float) { if (css != scrollCss) { scrollCss = css; poke() } }
    private fun poke() = synchronized(wake) { wake.notifyAll() }

    // ---------------------------------------------------------------- GL plumbing
    private lateinit var dpy: EGLDisplay; private lateinit var cfg: EGLConfig; private lateinit var eglCtx: EGLContext
    private lateinit var pbuf: EGLSurface; private lateinit var sceneSurf: EGLSurface; private lateinit var glassSurf: EGLSurface
    private fun now() = System.nanoTime()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun egl(out: Surface) {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val cs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attrs, 0, cs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        cfg = cs[0]!!
        eglCtx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        sceneSurf = EGL14.eglCreateWindowSurface(dpy, cfg, out, intArrayOf(EGL14.EGL_NONE), 0)
        glassSurf = EGL14.eglCreateWindowSurface(dpy, cfg, glassReader.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx))
    }

    private fun asset(n: String) = ctx.assets.open("sky/$n").bufferedReader().readText()
    private fun shader(type: Int, src: String): Int { val s = GLES30.glCreateShader(type); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s); return s }
    private fun checkProg(p: Int, fs: Int, name: String) {
        val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) error("$name: " + GLES30.glGetShaderInfoLog(fs).ifBlank { GLES30.glGetProgramInfoLog(p) }.take(600))
    }
    private fun program(fsSrc: String, name: String): Int {
        val p = GLES30.glCreateProgram(); val fs = shader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VERT)); GLES30.glAttachShader(p, fs)
        GLES30.glBindAttribLocation(p, 0, "a"); GLES30.glLinkProgram(p); checkProg(p, fs, name); return p
    }

    /** The scene programs from the program-binary cache when valid (a few ms), else compiled once and cached. */
    private fun scenePrograms(): Pair<Int, Int> {
        val srcs = listOf("SKY" to asset("SKY.glsl"), "LAND" to asset("LAND.glsl"))
        val dir = File(ctx.cacheDir, "sky-programs").also { it.mkdirs() }
        val key = { s: String -> MessageDigest.getInstance("SHA-1").digest((s + GLES30.glGetString(GLES30.GL_VERSION)).toByteArray()).joinToString("") { "%02x".format(it) }.take(16) }
        val fmts = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_NUM_PROGRAM_BINARY_FORMATS, fmts, 0)
        if (fmts[0] > 0) {
            val ps = srcs.map { (n, s) ->
                val f = File(dir, "$n-${key(s)}.bin"); if (!f.exists()) return@map 0
                val bytes = f.readBytes(); val fmt = ByteBuffer.wrap(bytes, 0, 4).int
                val buf = ByteBuffer.allocateDirect(bytes.size - 4).order(ByteOrder.nativeOrder()); buf.put(bytes, 4, bytes.size - 4); buf.position(0)
                val p = GLES30.glCreateProgram(); GLES30.glProgramBinary(p, fmt, buf, bytes.size - 4)
                val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
                if (st[0] == 0) { GLES30.glDeleteProgram(p); f.delete(); 0 } else p
            }
            if (ps.all { it != 0 }) return ps[0] to ps[1]
        }
        val t0 = now()
        val built = srcs.map { (_, s) ->     // compile both before reading any status, so a driver that builds in parallel can
            val p = GLES30.glCreateProgram(); val fs = shader(GLES30.GL_FRAGMENT_SHADER, s)
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VERT)); GLES30.glAttachShader(p, fs)
            GLES30.glBindAttribLocation(p, 0, "a")
            GLES30.glProgramParameteri(p, GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES30.GL_TRUE)
            GLES30.glLinkProgram(p); p to fs
        }
        built.forEachIndexed { i, (p, fs) -> checkProg(p, fs, srcs[i].first) }
        Log.i(TAG, "scene programs compiled in ${"%.0f".format(ms(t0))} ms")
        if (fmts[0] > 0) built.forEachIndexed { i, (p, _) ->
            val len = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_PROGRAM_BINARY_LENGTH, len, 0)
            val buf = ByteBuffer.allocateDirect(len[0]).order(ByteOrder.nativeOrder()); val fmt = IntArray(1); val got = IntArray(1)
            GLES30.glGetProgramBinary(p, len[0], got, 0, fmt, 0, buf)
            val bytes = ByteArray(4 + got[0]); ByteBuffer.wrap(bytes).putInt(fmt[0]); buf.position(0); buf.get(bytes, 4, got[0])
            File(dir, "${srcs[i].first}-${key(srcs[i].second)}.bin").writeBytes(bytes)
        }
        return built[0].first to built[1].first
    }

    private class Tgt(val f: Int, val t: Int, val w: Int, val h: Int, val m: Int = 0)
    private var halfFloat = false
    private fun tex2D(w: Int, h: Int, fmt: String, mips: Boolean = false): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE6); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val lv = if (mips) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1   // SC1: the land slices carry a mip chain for the scroll blur
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, lv, if (fmt == "u8" || !halfFloat) GLES30.GL_RGBA8 else GLES30.GL_RGBA16F, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }
    private fun target(w: Int, h: Int, mat: String? = null, fmt: String = "f16", mips: Boolean = false): Tgt {
        val t = tex2D(w, h, fmt, mips); val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t, 0)
        var m = 0
        if (mat != null) {   // second target: the wind material (land) or a pad (sky), one render-target layout for all four slices
            m = tex2D(w, h, if (mat == "pad") "u8" else fmt, mips && mat != "pad")
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT1, GLES30.GL_TEXTURE_2D, m, 0)
            GLES30.glDrawBuffers(2, intArrayOf(GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_COLOR_ATTACHMENT1), 0)
        }
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(st == GLES30.GL_FRAMEBUFFER_COMPLETE) { "framebuffer ${w}x$h $fmt incomplete: 0x${Integer.toHexString(st)}" }
        return Tgt(f[0], t, w, h, m)
    }

    // ---------------------------------------------------------------- uniforms: by name, typed from the program itself
    private class U(val loc: Int, val type: Int, val size: Int)
    private val uni = HashMap<Int, HashMap<String, U>>()
    private fun uniforms(p: Int): HashMap<String, U> = uni.getOrPut(p) {
        val m = HashMap<String, U>(); val n = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_ACTIVE_UNIFORMS, n, 0)
        val len = IntArray(1); val size = IntArray(1); val type = IntArray(1); val name = ByteArray(256)
        for (i in 0 until n[0]) {
            GLES30.glGetActiveUniform(p, i, name.size, len, 0, size, 0, type, 0, name, 0)
            val s = String(name, 0, len[0]).removeSuffix("[0]")
            m[s] = U(GLES30.glGetUniformLocation(p, s), type[0], size[0])
        }
        m
    }
    private fun loc(p: Int, n: String) = uniforms(p)[n]?.loc ?: -1
    /** Sets every value the program has; names it lacks (optimised out) are skipped. */
    private fun apply(p: Int, vals: Map<String, DoubleArray>, ints: Map<String, IntArray> = emptyMap()) {
        GLES30.glUseProgram(p); val us = uniforms(p)
        for ((n, v) in vals) {
            val u = us[n] ?: continue; val f = FloatArray(v.size) { v[it].toFloat() }
            when (u.type) {
                GLES30.GL_FLOAT -> GLES30.glUniform1fv(u.loc, f.size, f, 0)
                GLES30.GL_FLOAT_VEC2 -> GLES30.glUniform2fv(u.loc, f.size / 2, f, 0)
                GLES30.GL_FLOAT_VEC3 -> GLES30.glUniform3fv(u.loc, f.size / 3, f, 0)
                GLES30.GL_FLOAT_VEC4 -> GLES30.glUniform4fv(u.loc, f.size / 4, f, 0)
                else -> Log.w(TAG, "uniform $n: type 0x${Integer.toHexString(u.type)}")
            }
        }
        for ((n, v) in ints) { val u = us[n] ?: continue; if (u.type == GLES30.GL_INT_VEC2) GLES30.glUniform2iv(u.loc, 1, v, 0) else GLES30.glUniform1iv(u.loc, v.size, v, 0) }
    }
    private fun u1(p: Int, n: String, v: Float) = GLES30.glUniform1f(loc(p, n), v)
    private fun u2(p: Int, n: String, a: Float, b: Float) = GLES30.glUniform2f(loc(p, n), a, b)
    private fun u1i(p: Int, n: String, v: Int) = GLES30.glUniform1i(loc(p, n), v)

    // ---------------------------------------------------------------- the scene
    private var pSky = 0; private var pLand = 0; private var pB = 0; private var pBl = 0; private var pF = 0; private var pC = 0; private var pR = 0; private var pG = 0
    private lateinit var slices: List<Tgt>; private lateinit var acc: Tgt; private lateinit var a: Tgt; private lateinit var b: Tgt
    private lateinit var c: Tgt; private lateinit var d: Tgt; private lateinit var r: Tgt; private lateinit var fin: Tgt
    private lateinit var g2: Tgt; private lateinit var g4: Tgt; private lateinit var g8: Tgt; private lateinit var g8b: Tgt
    private var margin = 0
    private val t0 = System.nanoTime()
    private fun secs() = (System.nanoTime() - t0) / 1e9f
    private var prm: SceneParams.Out? = null

    private fun init(out: Surface) {
        val tAll = now()
        egl(out)
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        halfFloat = ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")
        val vb = IntArray(1); GLES30.glGenBuffers(1, vb, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0])
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, direct(tri), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        noise()
        val (s, l) = scenePrograms(); pSky = s; pLand = l
        pB = program(asset("BRIGHT.glsl"), "BRIGHT"); pBl = program(asset("BLUR.glsl"), "BLUR"); pF = program(asset("FINAL.glsl"), "FINAL")
        pC = program(asset("COMPOSE.glsl"), "COMPOSE"); pR = program(asset("RAYS.glsl"), "RAYS"); pG = program(GLASS, "GLASS")
        // the scene's samplers: noise 0-2, trees 15 (as the prototype)
        listOf(pSky, pLand).forEach { p -> GLES30.glUseProgram(p); u1i(p, "uShape", 0); u1i(p, "uDetail", 1); u1i(p, "uWeather", 2); u1i(p, "uTreeTex", 15) }
        margin = Math.ceil(28.0 * dpr).toInt()
        val sw = W + 2 * margin; val sh = H + 2 * margin
        slices = (0..3).map { target(sw, sh, if (it >= 2) "mat" else "pad", mips = it != 1) }
        acc = target(W, H); val q = { n: Int -> intArrayOf(maxOf(1, (W + n - 1) / n), maxOf(1, (H + n - 1) / n)) }
        a = target(q(4)[0], q(4)[1]); b = target(q(4)[0], q(4)[1]); c = target(q(16)[0], q(16)[1]); d = target(q(16)[0], q(16)[1]); r = target(q(4)[0], q(4)[1])
        fin = target(W, H, null, "u8")
        g2 = target(W / 2, H / 2, null, "u8"); g4 = target(W / 4, H / 4, null, "u8"); g8 = target(GW, GH, null, "u8"); g8b = target(GW, GH, null, "u8")
        Log.i(TAG, "init ${"%.0f".format(ms(tAll))} ms (half-float targets $halfFloat)")
    }

    private fun direct(f: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(f); it.position(0) }
    private fun noise() {
        val p = program(asset("GEN.glsl"), "GEN"); GLES30.glUseProgram(p)
        val fb = IntArray(1); GLES30.glGenFramebuffers(1, fb, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
        val ifmt = if (halfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8; val ty = if (halfFloat) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        fun setup(tg: Int) { GLES30.glTexParameteri(tg, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(tg, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R).forEach { GLES30.glTexParameteri(tg, it, GLES30.GL_REPEAT) } }
        fun vol(n: Int, mode: Int, unit: Int) {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, t[0])
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, ifmt, n, n, n, 0, GLES30.GL_RGBA, ty, null); setup(GLES30.GL_TEXTURE_3D)
            GLES30.glViewport(0, 0, n, n); u1(p, "uN", n.toFloat()); u1i(p, "uMode", mode)
            for (z in 0 until n) { GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, t[0], 0, z); u1(p, "uZ", z.toFloat()); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3) }
        }
        vol(128, 0, 0); vol(64, 1, 1)
        val w = IntArray(1); GLES30.glGenTextures(1, w, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, w[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, ifmt, 512, 512, 0, GLES30.GL_RGBA, ty, null); setup(GLES30.GL_TEXTURE_2D)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, w[0], 0)
        GLES30.glViewport(0, 0, 512, 512); u1(p, "uN", 512f); u1i(p, "uMode", 2); u1(p, "uZ", 0f); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // the maples' branching: unit 15
        val tt = IntArray(1); GLES30.glGenTextures(1, tt, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE15); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tt[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, SceneParams.SEG_MAX * 2, 2, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, direct(SceneParams.treeTexture))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    /** One jittered frame into the four slices, blended into the running average (clouds still: the same uT for all 24). */
    private var k = 0
    private var sceneT = 40f
    private fun accumulate() {
        val o = prm ?: return
        val off = if (k == 0) floatArrayOf(0f, 0f) else floatArrayOf(Math.random().toFloat() - .5f, Math.random().toFloat() - .5f)
        listOf(pSky, pLand).forEach { p ->
            apply(p, o.scene, o.ints)
            u2(p, "uRes", W.toFloat(), H.toFloat()); u2(p, "uMargin", margin.toFloat(), margin.toFloat()); u1(p, "uSplit", 30f)
            u2(p, "uOff", off[0], off[1]); u1(p, "uFrame", (k % 97).toFloat()); u1(p, "uT", sceneT)
        }
        if (k == 0) GLES30.glDisable(GLES30.GL_BLEND) else { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendColor(0f, 0f, 0f, 1f / (k + 1)); GLES30.glBlendFunc(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA) }
        slices.forEachIndexed { i, x ->
            val p = if (i < 2) pSky else pLand
            GLES30.glUseProgram(p); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, x.f); GLES30.glViewport(0, 0, x.w, x.h); u1(p, "uSlice", i.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glDisable(GLES30.GL_BLEND); k++; mipDirty = true
    }

    private var vpW = W; private var vpH = H
    private fun pass(p: Int, dst: Tgt?, src: Tgt, set: () -> Unit) {
        GLES30.glUseProgram(p); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst?.f ?: 0); GLES30.glViewport(0, 0, dst?.w ?: vpW, dst?.h ?: vpH)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.t); u1i(p, "uSrc", 3)
        set(); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    private fun blur(src: Tgt, tmp: Tgt, dst: Tgt, k: Float = 1f) {
        pass(pBl, tmp, src) { u2(pBl, "uDst", tmp.w.toFloat(), tmp.h.toFloat()); u2(pBl, "uDir", k / src.w, 0f) }
        pass(pBl, dst, tmp) { u2(pBl, "uDst", dst.w.toFloat(), dst.h.toFloat()); u2(pBl, "uDir", 0f, k / tmp.h) }
    }

    private var mipDirty = true
    private fun mips() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        listOf(slices[0].t, slices[2].t, slices[3].t, slices[2].m, slices[3].m).forEach { t ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR) }
        mipDirty = false
    }
    private fun compose(scroll: Float, band: Boolean) {
        val o = prm ?: return
        // RP2: a scroll-only frame re-composites only the rows the scroll changes (BAND_TOP..BAND_BOT); the rest of acc keeps the
        // last full frame, which is what a full compose would draw there (apart from the live layer, refreshed by the next full frame)
        if (band) { GLES30.glEnable(GLES30.GL_SCISSOR_TEST); val y0 = (H * (1f - BAND_BOT)).toInt(); GLES30.glScissor(0, y0, W, Math.ceil(H * (1.0 - BAND_TOP)).toInt() - y0) }
        apply(pC, o.compose)
        slices.forEachIndexed { i, x -> GLES30.glActiveTexture(GLES30.GL_TEXTURE7 + i); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, x.t); u1i(pC, "uS$i", 7 + i); u2(pC, "uO$i", 0f, 0f) }
        listOf(2, 3).forEachIndexed { j, i -> GLES30.glActiveTexture(GLES30.GL_TEXTURE11 + j); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slices[i].m); u1i(pC, "uM$i", 11 + j) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, acc.f); GLES30.glViewport(0, 0, W, H)
        u2(pC, "uRes", W.toFloat(), H.toFloat()); u2(pC, "uMargin", margin.toFloat(), margin.toFloat())
        u2(pC, "uSliceRes", (W + 2 * margin).toFloat(), (H + 2 * margin).toFloat()); u2(pC, "uSliceRes1", (W + 2 * margin).toFloat(), (H + 2 * margin).toFloat())
        GLES30.glUniform4f(loc(pC, "uXf"), 1f, 1f, 0f, 0f); u1(pC, "uT", if (live) 40f + secs() else 40f)
        // SC1: the scroll is the renderer's own (it moves faster than the sky is set)
        val sc = maxOf(0f, scroll); val kS = minOf(1f, sc / 200f)
        if (kS > 0f && mipDirty) mips()
        u1(pC, "uScrK", kS); u1(pC, "uLift", .15f * minOf(sc, 200f) * dpr)
        if (!live) u1(pC, "uBirds", 0f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (band) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }
    private fun post() {
        val o = prm ?: return
        pass(pB, a, acc) { u2(pB, "uDst", a.w.toFloat(), a.h.toFloat()); u2(pB, "uSrcTexel", 1.5f / acc.w, 1.5f / acc.h); u1(pB, "uTh", .72f) }
        blur(a, b, a)
        pass(pB, c, a) { u2(pB, "uDst", c.w.toFloat(), c.h.toFloat()); u2(pB, "uSrcTexel", 1.5f / a.w, 1.5f / a.h); u1(pB, "uTh", -10f) }
        blur(c, d, c)
        if (o.raysOn) { pass(pR, r, acc) { apply(pR, o.rays); u2(pR, "uDst", r.w.toFloat(), r.h.toFloat()); u2(pR, "uRes", W.toFloat(), H.toFloat()) }; blur(r, b, r) }
        apply(pF, o.final)
        pass(pF, fin, acc) {
            u1i(pF, "uScene", 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.t); u1i(pF, "uB1", 4)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, c.t); u1i(pF, "uB2", 5)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE6); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, r.t); u1i(pF, "uR", 6)
            u2(pF, "uRes", W.toFloat(), H.toFloat()); u1(pF, "uT", secs() % 100f)
        }
    }
    /** The glass's backdrop: the finished frame at 1/8, blurred to CSS blur(22px) at this dpr, saturate(1.6). */
    private fun glass() {
        val down = { src: Tgt, dst: Tgt -> pass(pB, dst, src) { u2(pB, "uDst", dst.w.toFloat(), dst.h.toFloat()); u2(pB, "uSrcTexel", .5f / src.w, .5f / src.h); u1(pB, "uTh", -10f) } }
        down(fin, g2); down(g2, g4); down(g4, g8)
        val kk = (22f * dpr / 8f) / (1.64f * Math.sqrt(2.0).toFloat())   // 22 CSS px as sigma at 1/8; the 5-tap blur is sigma ~1.64 per pass
        blur(g8, g8b, g8, kk); blur(g8, g8b, g8, kk)
        EGL14.eglMakeCurrent(dpy, glassSurf, glassSurf, eglCtx); vpW = GW; vpH = GH
        pass(pG, null, g8) { u2(pG, "uDst", GW.toFloat(), GH.toFloat()); u1(pG, "uSat", 1.6f) }
        EGL14.eglSwapBuffers(dpy, glassSurf)
        vpW = W; vpH = H
    }
    private fun present(withGlass: Boolean) {
        EGL14.eglMakeCurrent(dpy, sceneSurf, sceneSurf, eglCtx)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fin.f); GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        EGL14.eglSwapBuffers(dpy, sceneSurf)
        if (withGlass) glass()
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx)
    }

    // ---------------------------------------------------------------- the loop
    private var fence = 0L
    private fun frame(scroll: Float, withGlass: Boolean, band: Boolean = false) {
        // one frame in flight: the UI's own GPU work never queues behind several of ours (SP1)
        if (fence != 0L) { GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L); GLES30.glDeleteSync(fence); fence = 0L }
        compose(scroll, band); post(); present(withGlass)
        fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); GLES30.glFlush()
        frames[if (band) 1 else 0]++
    }
    /** Frames drawn since start: [full, band-only] (logged on pause, for the RP2 measurement). */
    private val frames = IntArray(2)

    /**
     * Scroll frames (RP2). 1: clamped at 200 px, band-only between the live layer's 30 fps frames (adopted). 0: every scroll change
     * in full, as RP1, for A/B in debug builds: `adb shell setprop debug.sky.scroll 0` (read every 2 s, so it can change while the
     * page is open). A 30 fps cap while scrolling was measured too and dropped (RP2: 25-32% janky; the page itself drew fewer frames).
     */
    private var mode = 1
    private fun prop(n: String) = try { Runtime.getRuntime().exec(arrayOf("getprop", n)).inputStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }

    private fun loop() {
        var gen = -1; var lastScroll = Float.NaN; var last = 0L
        val debuggable = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0; var propAt = 0L
        while (running) {
            if (debuggable && ms(propAt) > 2000) {   // debug A/B: the mode can change while the page is open
                propAt = now(); val m = if (prop("debug.sky.scroll") == "0") 0 else 1
                if (m != mode || frames.sum() == 0) { mode = m; Log.i(TAG, "scroll frames: mode $mode") }
                if (frames.sum() == 0 && prop("debug.sky.live") == "0") { live = false; Log.i(TAG, "live layer off (debug)") }   // RP2's seam check
            }
            if (paused) { idle(); continue }
            val g = inputGen
            if (g != gen) {   // a new sky: converge again from frame 0
                val i = input
                if (i == null) { idle(); continue }
                gen = g; prm = SceneParams.build(i.copy(dpr = dpr.toDouble(), w = W, h = H, live = live, scroll = 0.0)); k = 0; converged = false
                sceneT = 40f
            }
            // SC1's lift, blur and fade are complete at 200 CSS px: past it a scroll changes nothing the renderer draws (RP2)
            val sc = if (mode > 0) scrollCss.coerceIn(0f, 200f) else scrollCss; val moved = sc != lastScroll
            if (k < 24) {
                accumulate()
                frame(sc, withGlass = true); lastScroll = sc; last = now()
                if (k == 24) { converged = true; Log.i(TAG, "converged") }
                continue
            }
            val due = live && ms(last) >= 1000.0 / 30
            if (!moved && !due) { if (live) Thread.sleep(2) else idle(); continue }
            // at rest the cards sit on the flat page (T8): their backdrop only changes while the page scrolls. A scroll between the
            // live layer's full frames re-composites the scroll band only (RP2); `last` times the full frames
            val band = mode == 1 && !due
            frame(sc, withGlass = moved, band = band); lastScroll = sc; if (!band) last = now()
        }
    }
    private fun idle() = synchronized(wake) { wake.wait(250) }

    private fun release() {
        if (!::dpy.isInitialized) return
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, sceneSurf); EGL14.eglDestroySurface(dpy, glassSurf); EGL14.eglDestroySurface(dpy, pbuf)
        EGL14.eglDestroyContext(dpy, eglCtx); EGL14.eglTerminate(dpy)
        glassReader.close()
    }

    companion object {
        private const val TAG = "SkyRenderer"
        /**
         * The rows a scroll changes (screen fraction from the top), from COMPOSE.glsl's SC1 terms: below 0.60 nothing moves (band = 0);
         * above, the highest thing moved is the taller maple's crown (lip ~0.387 - height 0.108 = 0.279), lifted up to 30 CSS px
         * and reached by the blur's taps (~35 frame px at its top mip): 0.279 - (60 + 80)/1830 ~ 0.20; kept at 0.18 for a margin.
         */
        private const val BAND_TOP = .18f
        private const val BAND_BOT = .61f
        const val VERT = "#version 300 es\nin vec2 a; void main(){ gl_Position = vec4(a, 0., 1.); }"
        // CSS saturate(s) (Filter Effects feColorMatrix), on the sRGB values
        const val GLASS = """#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst; uniform float uSat; out vec4 o;
void main(){ vec3 c = texture(uSrc, gl_FragCoord.xy/uDst).rgb; float s = uSat;
  mat3 m = mat3(.213 + .787*s, .213 - .213*s, .213 - .213*s,  .715 - .715*s, .715 + .285*s, .715 - .715*s,  .072 - .072*s, .072 - .072*s, .072 + .928*s);
  o = vec4(clamp(m*c, 0., 1.), 1.); }"""
    }
}
