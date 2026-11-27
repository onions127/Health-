package com.jcwear.coach.skyspike

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
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.security.MessageDigest

/**
 * LOCK phase 2 spike (debug only): the prototype's scene shaders, run unchanged on GLES 3.0, with the uniforms of one
 * state recorded from the prototype (tools/visual-loop/spike-export.js). It measures what the renderer port (path A)
 * depends on: shader compile, the noise volumes, half-float targets, the cost of a scene frame and of the per-frame
 * passes, and hands two GPU images to Compose each frame: the finished scene and the scene blurred for the glass.
 */
@RequiresApi(29)
class SkySpikeRenderer(
    private val ctx: Context,
    private val stateName: String,
    val W: Int,
    val H: Int,
    private val dpr: Float,
    private val useCache: Boolean,
) {
    val stats = Stats()
    val sceneReader: ImageReader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
    val GW = W / 8; val GH = H / 8
    val glassReader: ImageReader = ImageReader.newInstance(GW, GH, PixelFormat.RGBA_8888, 4,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)

    class Stats {
        @Volatile var lines = linkedMapOf<String, String>()
        fun put(k: String, v: String) { lines = LinkedHashMap(lines).also { it[k] = v }; Log.i("SkySpike", "$k: $v") }
        fun text() = lines.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private val thread = HandlerThread("sky-gl").also { it.start() }
    private val h = Handler(thread.looper)
    @Volatile private var running = true

    private lateinit var dpy: EGLDisplay; private lateinit var cfg: EGLConfig; private lateinit var eglCtx: EGLContext
    private lateinit var pbuf: EGLSurface; private lateinit var sceneSurf: EGLSurface; private lateinit var glassSurf: EGLSurface

    /** [sceneOut]: a SurfaceView's surface to show the scene directly (SurfaceFlinger composites it), else the scene goes to [sceneReader] for Compose. */
    private var sceneOut: android.view.Surface? = null
    fun start(sceneOut: android.view.Surface? = null) { this.sceneOut = sceneOut; h.post { try { init(); loop() } catch (t: Throwable) { stats.put("ERROR", t.message ?: t.toString()); Log.e("SkySpike", "failed", t) } } }
    fun stop() { running = false; h.post { release() }; thread.quitSafely() }

    // ---------------------------------------------------------------- GL plumbing
    private fun now() = System.nanoTime()
    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6
    private fun f1(x: Double) = String.format("%.1f", x)

    private fun egl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT_KHR */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        val cs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        check(EGL14.eglChooseConfig(dpy, attrs, 0, cs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
        cfg = cs[0]!!
        eglCtx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        sceneSurf = EGL14.eglCreateWindowSurface(dpy, cfg, sceneOut ?: sceneReader.surface, intArrayOf(EGL14.EGL_NONE), 0)
        glassSurf = EGL14.eglCreateWindowSurface(dpy, cfg, glassReader.surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx))
    }

    private fun asset(n: String) = ctx.assets.open("skyspike/$n").bufferedReader().readText()

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

    /** The heavy scene programs: from the program-binary cache if it is there and valid, else compiled (and then cached). */
    private fun scenePrograms(): Pair<Int, Int> {
        val inj = when (exp) { "fract" -> "#define textureLod(s, p, l) textureLod(s, fract(p), l)" + NL; else -> "" }
        val srcs = listOf("SKY" to asset("SKY.glsl"), "LAND" to asset("LAND.glsl")).map { (n, s) -> n to s.replaceFirst("#version 300 es" + NL, "#version 300 es" + NL + inj) }
        val dir = File(ctx.cacheDir, "skyspike").also { it.mkdirs() }
        val key = { s: String -> MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16) }
        val fmts = IntArray(1); GLES30.glGetIntegerv(GLES30.GL_NUM_PROGRAM_BINARY_FORMATS, fmts, 0)
        stats.put("program binary formats", "${fmts[0]}")
        if (useCache && fmts[0] > 0) {
            val t0 = now()
            val ps = srcs.map { (n, s) ->
                val f = File(dir, "$n-${key(s)}.bin"); if (!f.exists()) return@map 0
                val bytes = f.readBytes(); val fmt = ByteBuffer.wrap(bytes, 0, 4).int
                val buf = ByteBuffer.allocateDirect(bytes.size - 4).order(ByteOrder.nativeOrder()); buf.put(bytes, 4, bytes.size - 4); buf.position(0)
                val p = GLES30.glCreateProgram(); GLES30.glProgramBinary(p, fmt, buf, bytes.size - 4)
                val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
                if (st[0] == 0) { GLES30.glDeleteProgram(p); 0 } else p
            }
            if (ps.all { it != 0 }) { stats.put("scene programs from cache", "${f1(ms(t0))} ms"); return ps[0] to ps[1] }
            stats.put("cache", "miss")
        }
        // compile both before reading any status, so a driver that builds in parallel can
        val t0 = now()
        val built = srcs.map { (_, s) ->
            val p = GLES30.glCreateProgram(); val fs = shader(GLES30.GL_FRAGMENT_SHADER, s)
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, VERT)); GLES30.glAttachShader(p, fs)
            GLES30.glBindAttribLocation(p, 0, "a")
            GLES30.glProgramParameteri(p, GLES30.GL_PROGRAM_BINARY_RETRIEVABLE_HINT, GLES30.GL_TRUE)
            GLES30.glLinkProgram(p); p to fs
        }
        built.forEachIndexed { i, (p, fs) -> checkProg(p, fs, srcs[i].first) }
        stats.put("scene compile+link (sky+land)", "${f1(ms(t0))} ms")
        if (fmts[0] > 0) built.forEachIndexed { i, (p, _) ->
            val len = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_PROGRAM_BINARY_LENGTH, len, 0)
            val buf = ByteBuffer.allocateDirect(len[0]).order(ByteOrder.nativeOrder()); val fmt = IntArray(1); val got = IntArray(1)
            GLES30.glGetProgramBinary(p, len[0], got, 0, fmt, 0, buf)
            val bytes = ByteArray(4 + got[0]); ByteBuffer.wrap(bytes).putInt(fmt[0]); buf.position(0); buf.get(bytes, 4, got[0])
            File(dir, "${srcs[i].first}-${key(srcs[i].second)}.bin").writeBytes(bytes)
            stats.put("binary ${srcs[i].first}", "${got[0] / 1024} KB")
        }
        return built[0].first to built[1].first
    }

    private class Tgt(val f: Int, val t: Int, val w: Int, val h: Int, val m: Int = 0)
    private var halfFloatTargets = false
    private fun tex2D(w: Int, h: Int, fmt: String, mips: Boolean = false): Int {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE6); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        val lv = if (mips) 32 - Integer.numberOfLeadingZeros(maxOf(w, h)) else 1   // SC1: the land slices carry a mip chain for the scroll blur
        if (fmt == "u8" || !halfFloatTargets) GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, lv, GLES30.GL_RGBA8, w, h)
        else GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, lv, GLES30.GL_RGBA16F, w, h)
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

    // ---------------------------------------------------------------- uniforms, replayed from the prototype
    private lateinit var rec: JSONObject
    private val locs = HashMap<Int, HashMap<String, Int>>()
    private fun loc(p: Int, n: String) = locs.getOrPut(p) { HashMap() }.getOrPut(n) { GLES30.glGetUniformLocation(p, n) }
    private fun replay(p: Int, prog: String) {
        GLES30.glUseProgram(p)
        val u = rec.getJSONObject(prog)
        for (n in u.keys()) {
            val l = loc(p, n); if (l < 0) continue
            val o = u.getJSONObject(n); val t = o.getString("t"); val a = o.getJSONArray("v")
            val f = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            when (t) {
                "1f" -> GLES30.glUniform1f(l, f[0]); "2f" -> GLES30.glUniform2f(l, f[0], f[1]); "3f" -> GLES30.glUniform3f(l, f[0], f[1], f[2])
                "4f" -> GLES30.glUniform4f(l, f[0], f[1], f[2], f[3]); "1i" -> GLES30.glUniform1i(l, f[0].toInt()); "2i" -> GLES30.glUniform2i(l, f[0].toInt(), f[1].toInt())
                "1fv" -> GLES30.glUniform1fv(l, f.size, f, 0); "2fv" -> GLES30.glUniform2fv(l, f.size / 2, f, 0)
                "3fv" -> GLES30.glUniform3fv(l, f.size / 3, f, 0); "4fv" -> GLES30.glUniform4fv(l, f.size / 4, f, 0)
                else -> Log.w("SkySpike", "uniform type $t ($n)")
            }
        }
    }
    private fun u1(p: Int, n: String, v: Float) = GLES30.glUniform1f(loc(p, n), v)
    private fun u2(p: Int, n: String, a: Float, b: Float) = GLES30.glUniform2f(loc(p, n), a, b)
    private fun u1i(p: Int, n: String, v: Int) = GLES30.glUniform1i(loc(p, n), v)
    private fun recF(prog: String, n: String) = rec.getJSONObject(prog).getJSONObject(n).getJSONArray("v").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }

    // ---------------------------------------------------------------- the scene
    private var pSky = 0; private var pLand = 0; private var pB = 0; private var pBl = 0; private var pF = 0; private var pC = 0; private var pR = 0; private var pG = 0; private var pM = 0
    private lateinit var slices: List<Tgt>; private lateinit var acc: Tgt; private lateinit var a: Tgt; private lateinit var b: Tgt
    private lateinit var c: Tgt; private lateinit var d: Tgt; private lateinit var r: Tgt; private lateinit var fin: Tgt; private lateinit var msk: Tgt
    private lateinit var g2: Tgt; private lateinit var g4: Tgt; private lateinit var g8: Tgt; private lateinit var g8b: Tgt
    private var margin = 0
    private val t0 = System.nanoTime()

    private fun init() {
        val tAll = now()
        egl()
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        stats.put("GPU", "${GLES30.glGetString(GLES30.GL_RENDERER)} / ${GLES30.glGetString(GLES30.GL_VERSION)}")
        val hf = ext.contains("GL_EXT_color_buffer_half_float"); val ff = ext.contains("GL_EXT_color_buffer_float")
        halfFloatTargets = hf || ff
        stats.put("half-float targets", "EXT_color_buffer_half_float=$hf, EXT_color_buffer_float=$ff, KHR_parallel_shader_compile=${ext.contains("KHR_parallel_shader_compile")}")
        stats.put("state", "$stateName at ${W}x$H (dpr $dpr)")
        rec = JSONObject(asset("state-$stateName.json")).also { j ->
            val tree = j.getJSONArray("tree"); treeData = FloatArray(tree.length()) { tree.getDouble(it).toFloat() }
        }.getJSONObject("uniforms")

        val vb = IntArray(1); GLES30.glGenBuffers(1, vb, 0); GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0])
        val tri = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, tri.size * 4, direct(tri), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        noise()
        val tc = now(); val (s, l) = scenePrograms(); pSky = s; pLand = l
        val tp = now()
        pB = program(asset("BRIGHT.glsl"), "BRIGHT"); pBl = program(asset("BLUR.glsl"), "BLUR"); pF = program(asset("FINAL.glsl"), "FINAL")
        pC = program(asset("COMPOSE.glsl"), "COMPOSE"); pM = program(MASK, "MASK"); pR = program(asset("RAYS.glsl"), "RAYS"); pG = program(GLASS, "GLASS")
        stats.put("post programs compile+link", "${f1(ms(tp))} ms")
        stats.put("all programs", "${f1(ms(tc))} ms")
        // the scene's samplers: noise 0-2, trees 15 (as the prototype)
        listOf(pSky, pLand).forEach { p -> GLES30.glUseProgram(p); u1i(p, "uShape", 0); u1i(p, "uDetail", 1); u1i(p, "uWeather", 2); u1i(p, "uTreeTex", 15) }

        margin = Math.ceil(28.0 * dpr).toInt()
        val sw = W + 2 * margin; val sh = H + 2 * margin
        slices = (0..3).map { target(sw, sh, if (it >= 2) "mat" else "pad", mips = it != 1) }
        acc = target(W, H); val q4 = { n: Int -> intArrayOf(maxOf(1, (W + n - 1) / n), maxOf(1, (H + n - 1) / n)) }
        a = target(q4(4)[0], q4(4)[1]); b = target(q4(4)[0], q4(4)[1]); c = target(q4(16)[0], q4(16)[1]); d = target(q4(16)[0], q4(16)[1]); r = target(q4(4)[0], q4(4)[1])
        fin = target(W, H, null, "u8"); msk = target(q4(4)[0], q4(4)[1], null, "u8")
        g2 = target(W / 2, H / 2, null, "u8"); g4 = target(W / 4, H / 4, null, "u8"); g8 = target(GW, GH, null, "u8"); g8b = target(GW, GH, null, "u8")
        stats.put("init total (to first frame)", "${f1(ms(tAll))} ms")
    }

    @Volatile var flushLayers = false
    @Volatile var exp = ""
    private lateinit var treeData: FloatArray
    private fun direct(f: FloatArray): FloatBuffer = ByteBuffer.allocateDirect(f.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(f); it.position(0) }
    private fun noise() {
        val p = program(asset("GEN.glsl"), "GEN"); GLES30.glUseProgram(p)
        val fb = IntArray(1); GLES30.glGenFramebuffers(1, fb, 0); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
        val ifmt = if (halfFloatTargets) GLES30.GL_RGBA16F else GLES30.GL_RGBA8; val ty = if (halfFloatTargets) GLES30.GL_HALF_FLOAT else GLES30.GL_UNSIGNED_BYTE
        fun setup(tg: Int) { GLES30.glTexParameteri(tg, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR); GLES30.glTexParameteri(tg, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            listOf(GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R).forEach { GLES30.glTexParameteri(tg, it, GLES30.GL_REPEAT) } }
        val t0 = now()
        fun vol(N: Int, mode: Int, unit: Int): Int {
            val t = IntArray(1); GLES30.glGenTextures(1, t, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit); GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, t[0])
            GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, ifmt, N, N, N, 0, GLES30.GL_RGBA, ty, null); setup(GLES30.GL_TEXTURE_3D)
            GLES30.glViewport(0, 0, N, N); u1(p, "uN", N.toFloat()); u1i(p, "uMode", mode)
            for (z in 0 until N) { GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, t[0], 0, z); u1(p, "uZ", z.toFloat()); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3); if (flushLayers) GLES30.glFlush() }
            return t[0]
        }
        val shapeT = vol(128, 0, 0); GLES30.glFinish(); val tShape = ms(t0)
        // check: a pixel of several layers, read back (all layers alike would mean the per-layer draws were lost)
        val px = FloatArray(4); val fbuf = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder())
        stats.put("shape layers (r at 37,53)", listOf(0, 1, 31, 64, 100, 127).joinToString(" ") { z ->
            GLES30.glFramebufferTextureLayer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, shapeT, 0, z)
            fbuf.position(0); GLES30.glReadPixels(37, 53, 1, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, fbuf); fbuf.asFloatBuffer().get(px)
            "z$z=" + String.format("%.3f", px[0]) })
        val t1 = now(); vol(64, 1, 1); GLES30.glFinish(); val tDet = ms(t1)
        val t2 = now()
        val w = IntArray(1); GLES30.glGenTextures(1, w, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE2); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, w[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, ifmt, 512, 512, 0, GLES30.GL_RGBA, ty, null); setup(GLES30.GL_TEXTURE_2D)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, w[0], 0)
        GLES30.glViewport(0, 0, 512, 512); u1(p, "uN", 512f); u1i(p, "uMode", 2); u1(p, "uZ", 0f); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3); GLES30.glFinish()
        stats.put("noise volumes (${if (halfFloatTargets) "RGBA16F" else "RGBA8"})", "shape 128³ ${f1(tShape)} ms, detail 64³ ${f1(tDet)} ms, weather 512² ${f1(ms(t2))} ms")
        // trees: unit 15
        val tt = IntArray(1); GLES30.glGenTextures(1, tt, 0); GLES30.glActiveTexture(GLES30.GL_TEXTURE15); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tt[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, 800, 2, 0, GLES30.GL_RGBA, GLES30.GL_FLOAT, direct(treeData))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST); GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    }

    private var k = 0
    private fun sceneUniforms(kk: Int) {
        val off = if (kk == 0) floatArrayOf(0f, 0f) else floatArrayOf(Math.random().toFloat() - .5f, Math.random().toFloat() - .5f)
        listOf(pSky to "SKY", pLand to "LAND").forEach { (p, n) ->
            replay(p, n)
            u2(p, "uRes", W.toFloat(), H.toFloat()); u2(p, "uMargin", margin.toFloat(), margin.toFloat()); u1(p, "uSplit", 30f)
            u2(p, "uOff", off[0], off[1]); u1(p, "uFrame", (kk % 97).toFloat()); u1(p, "uT", 40f)
        }
    }
    /** One jittered frame into the four slices (blended into the running average). Returns ms per slice (with glFinish if timed). */
    private fun accumulate(timed: Boolean): DoubleArray {
        sceneUniforms(k)
        val alpha = if (k == 0) 1f else 1f / (k + 1)
        if (k == 0) GLES30.glDisable(GLES30.GL_BLEND) else { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendColor(0f, 0f, 0f, alpha); GLES30.glBlendFunc(GLES30.GL_CONSTANT_ALPHA, GLES30.GL_ONE_MINUS_CONSTANT_ALPHA) }
        val out = DoubleArray(4)
        slices.forEachIndexed { i, x ->
            if (timed) GLES30.glFinish()
            val t = now(); val p = if (i < 2) pSky else pLand
            GLES30.glUseProgram(p); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, x.f); GLES30.glViewport(0, 0, x.w, x.h); u1(p, "uSlice", i.toFloat())
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            if (timed) { GLES30.glFinish(); out[i] = ms(t) }
        }
        GLES30.glDisable(GLES30.GL_BLEND); k++; mipDirty = true
        return out
    }

    private var vpW = W; private var vpH = H   // the current window surface's size (scene or glass)
    private fun pass(p: Int, dst: Tgt?, src: Tgt, set: () -> Unit) {
        GLES30.glUseProgram(p); GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, dst?.f ?: 0); GLES30.glViewport(0, 0, dst?.w ?: vpW, dst?.h ?: vpH)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src.t); u1i(p, "uSrc", 3)
        set(); GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    private fun blur(src: Tgt, tmp: Tgt, dst: Tgt, k: Float = 1f) {
        pass(pBl, tmp, src) { u2(pBl, "uDst", tmp.w.toFloat(), tmp.h.toFloat()); u2(pBl, "uDir", k / src.w, 0f) }
        pass(pBl, dst, tmp) { u2(pBl, "uDst", dst.w.toFloat(), dst.h.toFloat()); u2(pBl, "uDir", 0f, k / tmp.h) }
    }
    private fun secs() = (System.nanoTime() - t0) / 1e9f

    private fun compose() {
        replay(pC, "COMPOSE")
        slices.forEachIndexed { i, x -> GLES30.glActiveTexture(GLES30.GL_TEXTURE7 + i); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, x.t); u1i(pC, "uS$i", 7 + i); u2(pC, "uO$i", 0f, 0f) }
        listOf(2, 3).forEachIndexed { j, i -> GLES30.glActiveTexture(GLES30.GL_TEXTURE11 + j); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, slices[i].m); u1i(pC, "uM$i", 11 + j) }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, acc.f); GLES30.glViewport(0, 0, W, H)
        u2(pC, "uRes", W.toFloat(), H.toFloat()); u2(pC, "uMargin", margin.toFloat(), margin.toFloat())
        u2(pC, "uSliceRes", (W + 2 * margin).toFloat(), (H + 2 * margin).toFloat()); u2(pC, "uSliceRes1", (W + 2 * margin).toFloat(), (H + 2 * margin).toFloat())
        GLES30.glUniform4f(loc(pC, "uXf"), 1f, 1f, 0f, 0f); u1(pC, "uDpr", dpr); u1(pC, "uT", 40f + secs())
        // SC1, as scene-gl.js compose(): progress over 200 CSS px, lift .15 x scroll, blur up to 24 CSS px through the land's mip chains
        val sc = maxOf(0f, scrollCss); val kS = minOf(1f, sc / 200f)
        if (kS > 0f && mipDirty) mips()
        u1(pC, "uScrK", kS); u1(pC, "uLift", .15f * minOf(sc, 200f) * dpr); u1(pC, "uScrLod", (Math.log(24.0 * dpr) / Math.log(2.0)).toFloat())
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }
    private fun post() { bloom(); rays(); final() }
    @Volatile var scrollCss = 0f
    private var mipDirty = true
    private fun mips() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE6)
        listOf(slices[0].t, slices[2].t, slices[3].t, slices[2].m, slices[3].m).forEach { t ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t); GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR) }
        mipDirty = false; mipsBuilt++
    }
    private var mipsBuilt = 0
    private fun bloom() {
        pass(pB, a, acc) { u2(pB, "uDst", a.w.toFloat(), a.h.toFloat()); u2(pB, "uSrcTexel", 1.5f / acc.w, 1.5f / acc.h); u1(pB, "uTh", .72f) }
        blur(a, b, a)
        pass(pB, c, a) { u2(pB, "uDst", c.w.toFloat(), c.h.toFloat()); u2(pB, "uSrcTexel", 1.5f / a.w, 1.5f / a.h); u1(pB, "uTh", -10f) }
        blur(c, d, c)
    }
    @Volatile var raysMask = false
    private fun rays() {
        val sun = recF("FINAL", "uSunUV"); val rays = recF("FINAL", "uRays")[0]
        val src = if (raysMask) { pass(pM, msk, acc) { u2(pM, "uDst", msk.w.toFloat(), msk.h.toFloat()); u2(pM, "uSrcTexel", 1.5f / acc.w, 1.5f / acc.h) }; msk } else acc
        if (rays > 0) { pass(pR, r, src) { u2(pR, "uDst", r.w.toFloat(), r.h.toFloat()); u2(pR, "uSunUV", sun[0], sun[1]); u2(pR, "uRes", W.toFloat(), H.toFloat()) }; blur(r, b, r) }
    }
    @Volatile var flareOff = false
    private fun final() {
        replay(pF, "FINAL")
        pass(pF, fin, acc) {
            u1i(pF, "uScene", 3)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE4); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, a.t); u1i(pF, "uB1", 4)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE5); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, c.t); u1i(pF, "uB2", 5)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE6); GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, r.t); u1i(pF, "uR", 6)
            u2(pF, "uRes", W.toFloat(), H.toFloat()); u1(pF, "uT", secs() % 100f)
            if (flareOff) u1(pF, "uFlare", 0f)
        }
    }
    /** The glass's backdrop: the finished frame at 1/8, blurred to CSS blur(22px) at this dpr, saturate(1.6). */
    private fun glass() {
        val down = { src: Tgt, dst: Tgt -> pass(pB, dst, src) { u2(pB, "uDst", dst.w.toFloat(), dst.h.toFloat()); u2(pB, "uSrcTexel", .5f / src.w, .5f / src.h); u1(pB, "uTh", -10f) } }
        down(fin, g2); down(g2, g4); down(g4, g8)
        // 22 CSS px = 22*dpr device px = 22*dpr/8 px here (sigma); the 5-tap blur is sigma ~1.64 per pass at step 1, two passes at step k
        val kk = (22f * dpr / 8f) / (1.64f * Math.sqrt(2.0).toFloat())
        blur(g8, g8b, g8, kk); blur(g8, g8b, g8, kk)
        EGL14.eglMakeCurrent(dpy, glassSurf, glassSurf, eglCtx); vpW = GW; vpH = GH
        pass(pG, null, g8) { u2(pG, "uDst", GW.toFloat(), GH.toFloat()); u1(pG, "uSat", 1.6f) }
        vpW = W; vpH = H
    }

    private fun present(withGlass: Boolean = true) {
        EGL14.eglMakeCurrent(dpy, sceneSurf, sceneSurf, eglCtx)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fin.f); GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
        GLES30.glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        EGL14.eglSwapBuffers(dpy, sceneSurf)
        if (withGlass) { glass(); EGL14.eglSwapBuffers(dpy, glassSurf) }
        EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx)
    }

    /** Each per-frame pass on its own, GPU-timed (glFinish around each). */
    private fun breakdown(): String {
        val out = ArrayList<String>()
        fun t(n: String, f: () -> Unit) { GLES30.glFinish(); val t = now(); f(); GLES30.glFinish(); out += "$n ${f1(ms(t))}" }
        t("compose") { compose() }
        t("bloom") { bloom() }
        t("rays") { rays() }
        t("final") { final() }
        flareOff = true; t("final, no sun optics") { final() }; flareOff = false
        t("scene out") { EGL14.eglMakeCurrent(dpy, sceneSurf, sceneSurf, eglCtx); GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, fin.f); GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
            GLES30.glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST); EGL14.eglSwapBuffers(dpy, sceneSurf) }
        t("glass") { glass(); EGL14.eglSwapBuffers(dpy, glassSurf); EGL14.eglMakeCurrent(dpy, pbuf, pbuf, eglCtx) }
        return out.joinToString(", ") + " ms"
    }

    /** The finished frame as a PNG (exact, for pixdiff against the prototype). */
    @Volatile var maskDefault = false
    private fun dump(tag: String = "") {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fin.f)
        val buf = ByteBuffer.allocateDirect(W * H * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, W, H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val bm = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888); buf.position(0); bm.copyPixelsFromBuffer(buf)
        val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
        val up = android.graphics.Bitmap.createBitmap(bm, 0, 0, W, H, m, false)
        File(ctx.getExternalFilesDir(null), "skyspike-$stateName$tag${if (exp.isEmpty()) "" else "-$exp"}.png").outputStream().use { up.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Volatile var pace = false
    @Volatile var vsync = false
    @Volatile var glassOnScroll = false
    private var fence = 0L
    @Volatile var fps = 30
    @Volatile var doBench = false
    @Volatile var steady = false
    private fun loop() {
        // converge: 24 jittered frames, the first five timed per slice (the first draw carries the driver's own compile)
        val conv = now()
        for (i in 0 until 24) {
            val timed = i < 5 || i == 23
            val per = accumulate(timed)
            if (timed) stats.put("scene frame $i (slices 0-3)", per.joinToString(" / ") { f1(it) } + " = ${f1(per.sum())} ms")
            if (i == 0 || i == 23) { compose(); post(); present() }
            if (!running) return
        }
        GLES30.glFinish(); stats.put("converged (24 frames)", "${f1(ms(conv))} ms")
        dump()
        if (doBench) {
            fun bench(n: Int, glassToo: Boolean): Double { GLES30.glFinish(); val t = now(); repeat(n) { compose(); post(); if (glassToo) present() }; GLES30.glFinish(); return ms(t) / n }
            bench(10, true)
            raysMask = false; val b0 = bench(40, false); val b0g = bench(40, true)
            raysMask = true; val b1 = bench(40, false); val b1g = bench(40, true)
            compose(); post(); dump("-mask")
            flareOff = true; val b2 = bench(40, false); flareOff = false
            stats.put("bench back-to-back (ms/frame)", "compose+post ${f1(b0)} (+glass, hand-off ${f1(b0g)}); rays on a 1/4 mask ${f1(b1)} (${f1(b1g)}); mask, no sun optics ${f1(b2)}")
            stats.put("passes", breakdown())
        }
        raysMask = maskDefault
        // then what the phone runs while the sky shows: compose + post + the glass backdrop and hand-off, at `fps` (0: once, then still)
        compose(); post(); present(); steady = true
        // a new frame whenever the page scrolled (SC1), else at the ambient rate (wind); every 15th scroll frame GPU-timed
        var n = 0; var ns = 0; var nt = 0; var sumT = 0.0; var last = now(); var lastScroll = scrollCss; val ts = now()
        // vsync: wake on the display's frame clock (a Choreographer on its own thread), so our frames land just after a vsync, as the scroll's do
        val vs = if (vsync) HandlerThread("sky-vsync").also { it.start() } else null
        val tick = Object(); var vsN = 0L; var seen = 0L
        vs?.let { t -> Handler(t.looper).post { val c = android.view.Choreographer.getInstance()
            c.postFrameCallback(object : android.view.Choreographer.FrameCallback { override fun doFrame(ft: Long) {
                synchronized(tick) { vsN++; tick.notifyAll() }; if (running) c.postFrameCallback(this) } }) } }
        while (running) {
            if (vs != null) { synchronized(tick) { while (vsN == seen && running) tick.wait(50) ; seen = vsN } }
            val sc = scrollCss; val moved = sc != lastScroll; val due = fps > 0 && ms(last) >= 1000.0 / fps - (if (vs != null) 4.0 else 0.0)
            if (!moved && !due) { if (vs == null) Thread.sleep(2); continue }
            lastScroll = sc; last = now()
            val timed = moved && ns % 15 == 0
            if (timed) GLES30.glFinish()
            // pace: at most one frame in flight on the GPU, so the UI's own GPU work never queues behind several of ours
            if (pace && fence != 0L) { GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 100_000_000L); GLES30.glDeleteSync(fence); fence = 0L }
            // glassOnScroll: at rest the cards sit on the flat page (T8), so their backdrop only changes when the page scrolls
            val t = now(); compose(); post(); present(!glassOnScroll || moved); if (timed) { GLES30.glFinish(); sumT += ms(t); nt++ }
            if (pace) { fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0); GLES30.glFlush() }
            n++; if (moved) ns++
            if (n % 90 == 0) stats.put("scene frames", "$n in ${f1(ms(ts) / 1000)} s (ambient $fps fps), $ns on scroll; scroll frame compose+post+glass ${if (nt > 0) f1(sumT / nt) else "-"} ms (GPU-timed $nt); land mips rebuilt $mipsBuilt x")
        }
        vs?.quitSafely()
    }

    /** One more full scene frame (what a live sky's cloud drift costs per re-render). */
    fun measureDrift() = h.post {
        if (!::slices.isInitialized) return@post
        k = 24; val per = accumulate(true)
        stats.put("drift re-render (4 slices)", per.joinToString(" / ") { f1(it) } + " = ${f1(per.sum())} ms")
    }

    private fun release() {
        if (!::dpy.isInitialized) return
        EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(dpy, sceneSurf); EGL14.eglDestroySurface(dpy, glassSurf); EGL14.eglDestroySurface(dpy, pbuf)
        EGL14.eglDestroyContext(dpy, eglCtx); EGL14.eglTerminate(dpy)
        sceneReader.close(); glassReader.close()
    }

    companion object {
        const val NL = "\n"
        const val VERT = "#version 300 es\nin vec2 a; void main(){ gl_Position = vec4(a, 0., 1.); }"
        // CSS saturate(s) (Filter Effects feColorMatrix), on the sRGB values
        // the open-sky mask at quarter resolution (4 taps over each 4x4 block), so the rays march a small texture
        const val MASK = """#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst, uSrcTexel; out vec4 o;
void main(){ vec2 uv = gl_FragCoord.xy/uDst, d = uSrcTexel;
  float m = (texture(uSrc, uv + d*vec2(-1., -1.)).a + texture(uSrc, uv + d*vec2(1., -1.)).a + texture(uSrc, uv + d*vec2(-1., 1.)).a + texture(uSrc, uv + d*vec2(1., 1.)).a)*.25;
  o = vec4(m); }"""
        const val GLASS = """#version 300 es
precision highp float; uniform sampler2D uSrc; uniform vec2 uDst; uniform float uSat; out vec4 o;
void main(){ vec3 c = texture(uSrc, gl_FragCoord.xy/uDst).rgb; float s = uSat;
  mat3 m = mat3(.213 + .787*s, .213 - .213*s, .213 - .213*s,  .715 - .715*s, .715 + .285*s, .715 - .715*s,  .072 - .072*s, .072 - .072*s, .072 + .928*s);
  o = vec4(clamp(m*c, 0., 1.), 1.); }"""
    }
}
