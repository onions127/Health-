package com.jcwear.coach.ui.sky

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jcwear.coach.domain.sky.SceneParams
import com.jcwear.coach.domain.sky.SkyRegime
import com.jcwear.coach.domain.sky.SkyState
import com.jcwear.coach.ui.HealthColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/**
 * "Sky (preview)" (lock plan step 3): the live sky as the app renders it, for today's readiness at the clock's hour, under a
 * few glass cards that scroll (SC1). Behind the switch in Settings until the screens are rebuilt on it (steps 4-5).
 * The body's other layers (sleep fog, HRV cirrus, activity) arrive with the Today screen.
 */
@RequiresApi(29)
@Composable
fun SkyPreviewScreen(readiness: Int?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // the frame: 412 CSS px wide at x2, as the prototype on a phone, with the view's own aspect; SurfaceFlinger scales it to the view
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frameH = (824 * maxHeight.value / maxWidth.value).toInt() and 1.inv()
        key(frameH) { SkyPage(readiness, onBack, frameH, maxHeight) }
    }
}

@RequiresApi(29)
@Composable
private fun SkyPage(readiness: Int?, onBack: () -> Unit, frameH: Int, pageH: androidx.compose.ui.unit.Dp) {
    val ctx = LocalContext.current
    val renderer = remember { SkyRenderer(ctx.applicationContext, 824, frameH, 2f) }
    val glass = remember { GlassImage(renderer.glassReader) }
    val scroll = rememberScrollState()
    val root = remember { Box1() }

    // the sky: today's readiness at the clock's hour (re-set every 10 minutes: a live change converges again, ~8 s of GPU)
    LaunchedEffect(readiness) {
        while (true) {
            val now = LocalTime.now(); val date = LocalDate.now(); val hour = now.hour + now.minute / 60.0
            renderer.set(SceneParams.Input(SkyState.weather(readiness?.toDouble()), hour, date.toEpochDay().toInt(), date.dayOfYear - 1 + hour / 24))
            delay(10 * 60_000L)
        }
    }
    LaunchedEffect(scroll) { snapshotFlow { scroll.value }.collect { renderer.setScroll(it * 412f / root.size.width.coerceAtLeast(1)) } }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) renderer.pause() else if (e == Lifecycle.Event.ON_START) renderer.resume() }
        life.lifecycle.addObserver(o)
        onDispose { life.lifecycle.removeObserver(o); renderer.stop() }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { root.size = it.size }) {
        AndroidView({ c -> SurfaceView(c).apply {
            holder.setFormat(PixelFormat.RGBA_8888); holder.setFixedSize(renderer.W, renderer.H)
            holder.addCallback(object : SurfaceHolder.Callback {
                var started = false
                override fun surfaceCreated(h: SurfaceHolder) { if (!started) { started = true; renderer.start(h.surface) } }
                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) {}
            })
        } }, Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Spacer(Modifier.height(pageH * .42f))
            val word = when (SkyRegime.of(readiness?.toDouble())) {
                SkyRegime.FAIR -> "Mostly clear"; SkyRegime.PARTLY -> "Partly cloudy"; SkyRegime.STRATOCUMULUS -> "Broken cloud"
                SkyRegime.STRATUS -> "Low grey cloud"; SkyRegime.FIRST_RUN -> "A plain fair sky"
            }
            GlassCard(glass, scroll, root, readiness?.let { "Readiness $it" } ?: "No readiness yet", word)
            GlassCard(glass, scroll, root, "The live sky", "Rendered on this phone: shaders from the design prototype, converged over 24 frames.")
            GlassCard(glass, scroll, root, "Still to come", "Your sleep, HRV and activity in the sky; bakes; tilt; transitions.")
            Spacer(Modifier.height(400.dp))
        }
        TextButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) { Text("‹ Back", color = HealthColors.GlassInk, fontSize = 16.sp) }
    }
}

private class Box1 { var size = IntSize(1, 1) }

/** The renderer's glass image (1/8 frame, blurred), wrapped for Compose as each arrives; the last two kept open while drawn. */
@RequiresApi(29)
private class GlassImage(reader: ImageReader) {
    val frame = mutableIntStateOf(0)
    @Volatile var bitmap: ImageBitmap? = null
    private val held = ArrayDeque<Image>()
    init {
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val hb = img.hardwareBuffer ?: run { img.close(); return@setOnImageAvailableListener }
            val bm = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB)); hb.close()
            if (bm == null) { img.close(); return@setOnImageAvailableListener }
            held.addLast(img); while (held.size > 2) held.removeFirst().close()
            bitmap = bm.asImageBitmap(); frame.intValue++
        }, Handler(Looper.getMainLooper()))
    }
}

/** A glass card: its own window of the blurred sky, then 22% white, a light edge, dark ink (day glass; the hour's rules come with the design system). */
@RequiresApi(29)
@Composable
private fun GlassCard(glass: GlassImage, scroll: ScrollState, root: Box1, title: String, body: String) {
    val me = remember { object { var c: LayoutCoordinates? = null } }
    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 104.dp)
        .onGloballyPositioned { me.c = it }
        .drawBehind {
            glass.frame.intValue; scroll.value                              // redraw (draw phase only) on a new glass image and on scroll
            val pos = me.c?.takeIf { it.isAttached }?.positionInRoot() ?: return@drawBehind
            val img = glass.bitmap ?: return@drawBehind
            val r = CornerRadius(26.dp.toPx())
            clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, r)) }) {
                translate(-pos.x, -pos.y) { drawImage(img, dstSize = root.size, filterQuality = FilterQuality.Low) }
                drawRect(Color.White.copy(alpha = .22f))
            }
            drawRoundRect(Color.White.copy(alpha = .40f), cornerRadius = r, style = Stroke(1.dp.toPx()))
        }.padding(20.dp)) {
        Column {
            Text(title, color = HealthColors.GlassInk, fontSize = 17.sp)
            Text(body, Modifier.padding(top = 8.dp), color = HealthColors.GlassInk, fontSize = 13.sp)
        }
    }
}
