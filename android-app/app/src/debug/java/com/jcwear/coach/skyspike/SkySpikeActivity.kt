package com.jcwear.coach.skyspike

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.viewinterop.AndroidView
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.FrameMetrics
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import java.io.File

/**
 * LOCK phase 2 spike (debug only): the sky renderer handing two GPU images to Compose, and glass cards that draw their
 * own window of the blurred one while the page scrolls.
 *   adb shell am start -n com.jcwear.coach/.skyspike.SkySpikeActivity --es state fair13 --ez autoscroll true
 * Extras: state (fair13 | stratus13 | fair2010), w/h (frame px, default 824x1830 = 412x915 at x2), dpr, nocache, autoscroll,
 * scene (surface, default: a SurfaceView under the page | image: through an ImageReader into Compose), pace (one GPU frame in flight,
 * default true), glassOnScroll (send the glass image only while the page scrolls, default true), vsync (wake on the display's vsync,
 * default false: it hurt scrolling). The defaults are the setup adopted after SP1 (LOG.md).
 */
@RequiresApi(29)
class SkySpikeActivity : ComponentActivity() {
    private lateinit var renderer: SkySpikeRenderer
    private val metrics = HandlerThread("frame-metrics").also { it.start() }

    /** Takes each image the renderer queues and wraps it for Compose; keeps the last two open while they may still be drawn. */
    class HandOff(reader: ImageReader) {
        val frame = mutableIntStateOf(0)
        @Volatile var bitmap: ImageBitmap? = null
        @Volatile var ts = 0L
        private val held = ArrayDeque<Image>()
        init {
            reader.setOnImageAvailableListener({ r ->
                val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val hb = img.hardwareBuffer ?: run { img.close(); return@setOnImageAvailableListener }
                val bm = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)); hb.close()
                if (bm == null) { img.close(); return@setOnImageAvailableListener }
                held.addLast(img); while (held.size > 2) held.removeFirst().close()
                bitmap = bm.asImageBitmap(); ts = img.timestamp; frame.intValue++
            }, Handler(Looper.getMainLooper()))
        }
    }

    // hand-off delay: GL queued the frame -> Compose drew it (ms); UI frame times from FrameMetrics
    private val delays = ArrayList<Double>()
    private val ui = ArrayList<Double>(); private var uiAll = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val x = intent
        renderer = SkySpikeRenderer(this, x.getStringExtra("state") ?: "fair13", x.getIntExtra("w", 824), x.getIntExtra("h", 1830),
            x.getFloatExtra("dpr", 2f), !x.getBooleanExtra("nocache", false))
        val scene = HandOff(renderer.sceneReader); val glass = HandOff(renderer.glassReader)
        val auto = x.getBooleanExtra("autoscroll", false)
        renderer.fps = x.getIntExtra("fps", 30); renderer.doBench = x.getBooleanExtra("bench", false); renderer.maskDefault = x.getBooleanExtra("mask", false); renderer.flushLayers = x.getBooleanExtra("flush", false); renderer.exp = x.getStringExtra("exp") ?: ""; hud.value = x.getBooleanExtra("hud", true)
        renderer.pace = x.getBooleanExtra("pace", true)
        renderer.vsync = x.getBooleanExtra("vsync", false)
        renderer.glassOnScroll = x.getBooleanExtra("glassOnScroll", true)
        val surface = x.getStringExtra("scene") != "image"
        renderer.stats.put("mode", "scene ${if (surface) "SurfaceView" else "ImageReader -> Compose"}, pace ${renderer.pace}, vsync ${renderer.vsync}, glass on scroll only ${renderer.glassOnScroll}")
        setContent { Screen(scene, glass, auto, surface) }
        if (!surface) renderer.start()
        window.addOnFrameMetricsAvailableListener({ _, fm, _ ->
            val t = fm.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6
            if (fm.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@addOnFrameMetricsAvailableListener
            if (!renderer.steady) return@addOnFrameMetricsAvailableListener   // count from the converged sky on
            ui.add(t); uiAll++
            if (ui.size == 240) {
                val s = ui.sorted(); val f = { x: Double -> "%.1f".format(x) }
                renderer.stats.put("UI frames ${uiAll - 239}-$uiAll", "avg ${f(ui.average())} p50 ${f(s[120])} p90 ${f(s[216])} max ${f(s[239])} ms; over 8.3: ${ui.count { it > 8.33 }}, over 16.7: ${ui.count { it > 16.7 }}")
                ui.clear()
                synchronized(delays) { if (delays.isNotEmpty()) { val s = delays.sorted(); renderer.stats.put("hand-off delay (GL queue -> Compose draw)", "median ${"%.1f".format(s[s.size / 2])} ms, p90 ${"%.1f".format(s[s.size * 9 / 10])}") } }
            }
        }, Handler(metrics.looper))
    }

    override fun onPause() {
        super.onPause()
        File(getExternalFilesDir(null), "skyspike-result.txt").writeText(renderer.stats.text() + "\n")
    }

    override fun onDestroy() { renderer.stop(); metrics.quitSafely(); super.onDestroy() }

    private val hud = mutableStateOf(true)
    private class Holder { var c: LayoutCoordinates? = null; var root = IntSize(1, 1) }

    @Composable
    private fun Screen(scene: HandOff, glass: HandOff, auto: Boolean, surface: Boolean) {
        val scroll = rememberScrollState()
        val rootH = LocalConfiguration.current.screenHeightDp
        val root = remember { Holder() }
        // SC1: the page's scroll, in the prototype's CSS px (412 wide), drives the land
        LaunchedEffect(scroll) { androidx.compose.runtime.snapshotFlow { scroll.value }.collect { renderer.scrollCss = it * 412f / root.root.width.coerceAtLeast(1) } }
        LaunchedEffect(auto) {
            if (!auto) return@LaunchedEffect
            delay(4000)
            while (true) { scroll.animateScrollTo(scroll.maxValue, tween(2500, easing = LinearEasing)); scroll.animateScrollTo(0, tween(2500, easing = LinearEasing)) }
        }
        Box(if (surface) Modifier.fillMaxSize() else Modifier.fillMaxSize().background(Color.Black)) {
            if (surface) AndroidView({ c -> SurfaceView(c).apply {
                holder.setFormat(PixelFormat.RGBA_8888); holder.setFixedSize(renderer.W, renderer.H)
                holder.addCallback(object : SurfaceHolder.Callback {
                    var started = false
                    override fun surfaceCreated(h: SurfaceHolder) { if (!started) { started = true; renderer.start(h.surface) } }
                    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) {}
                })
            } }, Modifier.fillMaxSize().onGloballyPositioned { root.root = it.size })
            else Canvas(Modifier.fillMaxSize()) {
                scene.frame.intValue
                root.root = IntSize(size.width.toInt(), size.height.toInt())
                val img = scene.bitmap ?: return@Canvas
                drawImage(img, dstSize = root.root, filterQuality = FilterQuality.Low)
                val d = (System.nanoTime() - scene.ts) / 1e6
                synchronized(delays) { delays.add(d); if (delays.size > 600) delays.subList(0, 300).clear() }
            }
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Spacer(Modifier.height((rootH * .42f).dp))
                repeat(8) { i -> GlassCard(glass, scroll, root, i) }
                Spacer(Modifier.height(200.dp))
            }
            if (hud.value) Hud(Modifier.align(Alignment.TopStart))
        }
    }

    /** Its own scope: the twice-a-second text never recomposes the page. */
    @Composable
    private fun Hud(m: Modifier) {
        var text by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { while (true) { text = renderer.stats.text(); delay(500) } }
        Text(text, m.padding(top = 32.dp, start = 8.dp, end = 8.dp)
            .background(Color(0xAA000000)).clickable { renderer.measureDrift() }.padding(6.dp),
            color = Color.White, fontSize = 9.sp, lineHeight = 11.sp, fontFamily = FontFamily.Monospace)
    }

    @Composable
    private fun GlassCard(glass: HandOff, scroll: androidx.compose.foundation.ScrollState, root: Holder, i: Int) {
        val me = remember { Holder() }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(if (i % 3 == 0) 160.dp else 104.dp)
            .onGloballyPositioned { me.c = it }
            .drawBehind {
                glass.frame.intValue; scroll.value                              // redraw (draw phase only) on a new glass image and on scroll
                val pos = me.c?.takeIf { it.isAttached }?.positionInRoot() ?: return@drawBehind
                val img = glass.bitmap ?: return@drawBehind
                val r = CornerRadius(26.dp.toPx())
                clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }) {
                    translate(-pos.x, -pos.y) { drawImage(img, dstSize = root.root, filterQuality = FilterQuality.Low) }
                    drawRect(Color.White.copy(alpha = .22f))
                }
                drawRoundRect(Color.White.copy(alpha = .40f), cornerRadius = r, style = Stroke(1.dp.toPx()))
            }.padding(20.dp)) {
            Text("Glass card ${i + 1}", color = Color(0xFF0E2A43), fontSize = 17.sp)
            Text("Drawn from the renderer's own blur", Modifier.padding(top = 26.dp), color = Color(0xFF2E4A62), fontSize = 13.sp)
        }
    }
}
