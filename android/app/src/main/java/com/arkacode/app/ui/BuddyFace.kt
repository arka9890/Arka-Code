package com.arkacode.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Arka Buddy — maskot chibi anime, digambar sepenuhnya dengan Compose Canvas.
 *
 * Tidak ada Lottie / aset bitmap: seluruh geometri (rambut bob + poni lembut,
 * ahoge bercahaya, mata besar berkilau, pipi merona, hoodie, tangan, kaki)
 * dihitung dari satu angka waktu, sehingga animasi murni sinematika dan nol
 * dependensi.
 *
 * Semua koordinat gambar memakai satuan `u` relatif ke pusat kepala — lihat
 * [BuddyFigure]. Proporsi chibi: kepala+rambut ±58% tinggi figur.
 */

/** Mood wajah Buddy. */
enum class BuddyMood { IDLE, LISTENING, THINKING, TALKING, HAPPY }

/**
 * Geometri wajah & pose untuk satu titik waktu. Nilai murni (tanpa Compose)
 * supaya bisa diuji langsung dari unit test JVM — lihat `BuddyFaceTest`.
 *
 * @param eyeOpen 0 = terpejam, 1 = lebar penuh.
 * @param mouthOpen 0 = mulut tertutup, 1 = membuka penuh.
 * @param mouthCurve -1 cemberut … +1 senyum (>0,3 = mulut kucing "ω").
 * @param lookX/lookY pergeseran pupil -1…1.
 * @param squash regangan vertikal (>1 = memanjang, <1 = memipih; volume dijaga).
 * @param bounce pergeseran melayang -1…1.
 * @param armRaise 0 = tangan di samping badan, 1 = terangkat ke atas.
 * @param armWave lambaian tangan kanan -1…1.
 */
data class BuddyFrame(
    val eyeOpen: Float,
    val mouthOpen: Float,
    val mouthCurve: Float,
    val lookX: Float,
    val lookY: Float,
    val squash: Float,
    val bounce: Float,
    val armRaise: Float = 0f,
    val armWave: Float = 0f,
)

// Semua periode di bawah HARUS habis membagi CYCLE_MS, supaya animasi melingkar
// mulus — nilai di-wrap dari CYCLE_MS ke 0 tanpa lompatan yang terlihat.
private const val CYCLE_MS = 60_000f
private const val BLINK_MS = 3_000f

// Amber indikator "berpikir": di luar palet clay agar keadaan ke-4 terbaca
// sekilas tanpa perlu menambah warna tema.
private val ThinkAmber = Color(0xFFF2B33D)

// Palet tetap figur (kulit hangat, tinta mata, rona pipi, mulut).
private val Ink = Color(0xFF0B2136)
private val Skin = Color(0xFFFFEADF)
private val SkinShade = Color(0xFFF7CDBE)
private val Blush = Color(0xFFFF8FA3)
private val BlushLine = Color(0xFFF0708A)
private val MouthIn = Color(0xFF9C3346)
private val Tongue = Color(0xFFF27D93)
private val StarPink = Color(0xFFFF8FA3)

private fun wave(t: Float, period: Float): Float =
    sin(2.0 * PI * ((t % period) / period)).toFloat()

/** Kedipan: naik-turun 140 ms sekali tiap 3 detik. */
private fun blink(t: Float): Float {
    val p = t % BLINK_MS
    return when {
        p < 70f -> p / 70f
        p < 140f -> 1f - (p - 70f) / 140f
        else -> 0f
    }
}

/**
 * Hitung geometri wajah pada [timeMs] untuk [mood]. Fungsi murni — deterministik,
 * aman diulang di test, tanpa Compose sama sekali.
 */
fun buddyFrame(mood: BuddyMood, timeMs: Float): BuddyFrame {
    val t = ((timeMs % CYCLE_MS) + CYCLE_MS) % CYCLE_MS
    val closed = blink(t)
    val float = wave(t, 1_500f)
    return when (mood) {
        BuddyMood.IDLE -> BuddyFrame(
            eyeOpen = 1f - closed,
            mouthOpen = 0f,
            mouthCurve = 0.45f,
            lookX = wave(t, 1_200f) * 0.15f,
            lookY = wave(t, 800f) * 0.10f,
            squash = 1f,
            bounce = float,
            armRaise = 0f,
            armWave = wave(t, 3_000f) * 0.15f,
        )

        BuddyMood.LISTENING -> BuddyFrame(
            // Jarang kedip: mata melebar, fokus ke lawan bicara.
            eyeOpen = 1f - closed * 0.4f,
            mouthOpen = 0.34f + wave(t, 1_000f) * 0.05f,
            mouthCurve = 0f,
            lookX = 0f,
            lookY = 0f,
            squash = 1f,
            bounce = float * 0.4f,
        )

        BuddyMood.THINKING -> BuddyFrame(
            eyeOpen = 1f - closed,
            mouthOpen = 0f,
            // Selalu garis datar "hmm" (tak pernah melewati ambang mulut kucing).
            mouthCurve = -0.15f + wave(t, 1_200f) * 0.15f,
            lookX = 0.45f + wave(t, 1_200f) * 0.30f,
            lookY = -0.50f + wave(t, 800f) * 0.20f,
            squash = 1f,
            bounce = float * 0.6f,
        )

        BuddyMood.TALKING -> {
            val open = ((wave(t, 100f) * 0.55f + wave(t, 60f) * 0.45f) * 0.5f + 0.5f)
                .coerceIn(0f, 1f)
            BuddyFrame(
                eyeOpen = 1f - closed,
                mouthOpen = open,
                mouthCurve = 0.20f,
                lookX = wave(t, 1_200f) * 0.25f,
                lookY = wave(t, 800f) * 0.15f,
                // Mulut membuka → kepala ikut memanjang (squash & stretch).
                squash = 1f + (open - 0.4f) * 0.08f,
                bounce = float * 0.5f,
                armRaise = 0.45f + wave(t, 1_500f) * 0.10f,
                armWave = wave(t, 750f) * 0.6f,
            )
        }

        BuddyMood.HAPPY -> BuddyFrame(
            eyeOpen = 0f,
            mouthOpen = 0.34f + wave(t, 1_000f) * 0.05f,
            mouthCurve = 1f,
            lookX = 0f,
            lookY = 0f,
            squash = 1.04f,
            bounce = float * 0.8f,
            armRaise = 0.90f + wave(t, 1_000f) * 0.10f,
            armWave = 0f,
        )
    }
}

/** Batas atas figur (ujung ahoge + gelembung pikir), dalam unit dari pusat kepala. */
internal const val FIGURE_TOP_U = -1.56f

/** Batas bawah figur (bayangan tanah), dalam unit dari pusat kepala. */
internal const val FIGURE_BOTTOM_U = 1.92f

/** Setengah lebar figur terlebar (gelombang "mendengar"), dalam unit. */
internal const val FIGURE_HALF_W_U = 1.36f

/**
 * Tata letak figur chibi Buddy di kanvas selebar [w] setinggi [h].
 *
 * Semua nilai dalam piksel; [unit] = setengah lebar wajah. Bagian lain diukur
 * relatif ke pusat kepala ([headCx], [headCy]) dalam kelipatan [unit].
 *
 * @param groundY posisi bayangan tanah — sengaja TIDAK ikut melayang.
 */
data class BuddyFigure(
    val unit: Float,
    val headCx: Float,
    val headCy: Float,
    val bodyTop: Float,
    val bodyBottom: Float,
    val footY: Float,
    val groundY: Float,
)

/**
 * Hitung tata letak figur untuk kanvas [w] x [h] dan [frame].
 *
 * Figur punya proporsi tetap dan hanya diperkecil seperlunya supaya muat di
 * kanvas (margin 10% vertikal, 6% horizontal).
 *
 * Fungsi murni supaya bisa dites tanpa Compose — lihat `BuddyFaceTest`.
 */
fun buddyFigure(w: Float, h: Float, frame: BuddyFrame): BuddyFigure {
    val totalH = FIGURE_BOTTOM_U - FIGURE_TOP_U
    val unit = min(h / totalH / 1.10f, w / (FIGURE_HALF_W_U * 2f) / 1.06f)
    val centerOff = (FIGURE_TOP_U + FIGURE_BOTTOM_U) / 2f
    val restCy = h / 2f - centerOff * unit
    val bob = frame.bounce * unit * 0.030f
    val headCy = restCy + bob
    return BuddyFigure(
        unit = unit,
        headCx = w * 0.5f,
        headCy = headCy,
        bodyTop = headCy + 0.74f * unit,
        bodyBottom = headCy + 1.42f * unit,
        footY = headCy + 1.72f * unit,
        groundY = restCy + 1.86f * unit,
    )
}

/** Warna indikator keadaan per mood. */
@Composable
private fun moodGlow(mood: BuddyMood): Color = when (mood) {
    BuddyMood.IDLE, BuddyMood.TALKING -> MaterialTheme.colorScheme.primary
    BuddyMood.LISTENING, BuddyMood.HAPPY -> MaterialTheme.colorScheme.tertiary
    BuddyMood.THINKING -> ThinkAmber
}

/**
 * Pembangun Path dalam satuan unit relatif pusat kepala: `x = ox + dx * u`.
 * Membuat tiap bentuk bisa ditulis seperti di sketsa desain.
 */
private class UPath(private val ox: Float, private val oy: Float, private val u: Float) {
    val path = Path()
    private fun x(d: Float) = ox + d * u
    private fun y(d: Float) = oy + d * u
    fun m(dx: Float, dy: Float) = apply { path.moveTo(x(dx), y(dy)) }
    fun l(dx: Float, dy: Float) = apply { path.lineTo(x(dx), y(dy)) }
    fun q(cx: Float, cy: Float, dx: Float, dy: Float) =
        apply { path.quadraticTo(x(cx), y(cy), x(dx), y(dy)) }
    fun c(c1x: Float, c1y: Float, c2x: Float, c2y: Float, dx: Float, dy: Float) =
        apply { path.cubicTo(x(c1x), y(c1y), x(c2x), y(c2y), x(dx), y(dy)) }
    fun z() = apply { path.close() }
}

/** Satu titik tepi bawah poni: ujung helai ([tip] = true) atau pangkal. */
private data class FringePt(val tip: Boolean, val x: Float, val y: Float)

// Tepi bawah poni dari kiri ke kanan: ujung & pangkal bergantian.
private val FringeEdge = listOf(
    FringePt(true, -0.94f, -0.04f),
    FringePt(false, -0.78f, -0.26f),
    FringePt(true, -0.62f, -0.04f),
    FringePt(false, -0.42f, -0.30f),
    FringePt(true, -0.22f, 0.00f),
    FringePt(false, -0.04f, -0.30f),
    FringePt(true, 0.12f, -0.06f),
    FringePt(false, 0.32f, -0.32f),
    FringePt(true, 0.52f, -0.02f),
    FringePt(false, 0.70f, -0.30f),
    FringePt(true, 0.92f, -0.06f),
)

/**
 * Avatar Arka Buddy. Isi kanvas dari [modifier], lalu gambar figur chibi
 * anime lengkap dengan animasi kedip, lirik, bicara, dan pose per mood.
 */
@Composable
fun BuddyFace(
    mood: BuddyMood,
    modifier: Modifier = Modifier,
) {
    // Satu jam animasi untuk seluruh figur: linear 0 → CYCLE_MS lalu ulang,
    // sehingga siklusnya persis sepanjang CYCLE_MS dan [buddyFrame] melingkar
    // mulus tanpa lompatan di titik ulang.
    val transition = rememberInfiniteTransition(label = "buddy")
    val timeMs by transition.animateFloat(
        initialValue = 0f,
        targetValue = CYCLE_MS,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MS.toInt(), easing = LinearEasing),
        ),
        label = "buddy-time",
    )
    val frame = buddyFrame(mood, timeMs)

    val glow = moodGlow(mood)
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val dark = isClayDark()
    // Rambut, iris, dan hoodie diturunkan dari warna tema supaya tetap satu
    // keluarga warna dengan claymorphism app.
    val palette = BuddyPalette(
        hair = lerp(primary, Ink, if (dark) 0.42f else 0.30f),
        primary = primary,
        tertiary = tertiary,
        glow = glow,
    )

    Canvas(modifier) {
        val f = buddyFigure(size.width, size.height, frame)
        if (f.unit <= 0f) return@Canvas
        drawBuddy(f, frame, mood, palette)
    }
}

/** Warna turunan untuk satu render. */
private class BuddyPalette(
    val hair: Color,
    val primary: Color,
    val tertiary: Color,
    val glow: Color,
) {
    val hairDark = lerp(hair, Ink, 0.32f)
    val hairHi = lerp(hair, Color.White, 0.38f)
    val line = lerp(hairDark, Ink, 0.45f)
    val irisTop = lerp(primary, Ink, 0.62f)
    val irisMid = primary
    val irisBot = lerp(primary, Color.White, 0.55f)
    val cloth = lerp(primary, Color.White, 0.10f)
    val clothDark = lerp(primary, Ink, 0.30f)
    val clothLight = Color.White
    val shoe = lerp(hairDark, Ink, 0.20f)
}

private fun DrawScope.drawBuddy(
    f: BuddyFigure,
    frame: BuddyFrame,
    mood: BuddyMood,
    c: BuddyPalette,
) {
    val u = f.unit
    val cx = f.headCx
    val cy = f.headCy
    fun X(d: Float) = cx + d * u
    fun Y(d: Float) = cy + d * u
    fun P(dx: Float, dy: Float) = Offset(X(dx), Y(dy))
    fun up() = UPath(cx, cy, u)
    val lw = 0.035f * u // tebal lineart
    fun stroke(w: Float) = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun fillLine(p: Path, fill: Color, w: Float = lw) {
        drawPath(p, fill)
        drawPath(p, c.line, style = stroke(w))
    }
    fun oval(dx: Float, dy: Float, rx: Float, ry: Float, color: Color) =
        drawOval(color, topLeft = P(dx - rx, dy - ry), size = Size(2f * rx * u, 2f * ry * u))
    fun ovalLine(dx: Float, dy: Float, rx: Float, ry: Float, fill: Color, w: Float = lw) {
        oval(dx, dy, rx, ry, fill)
        drawOval(
            c.line, topLeft = P(dx - rx, dy - ry), size = Size(2f * rx * u, 2f * ry * u),
            style = Stroke(width = w),
        )
    }
    fun seg(x0: Float, y0: Float, x1: Float, y1: Float, color: Color, w: Float) =
        drawLine(color, P(x0, y0), P(x1, y1), strokeWidth = w, cap = StrokeCap.Round)

    // --- bayangan tanah (tidak ikut melayang; mengecil saat Buddy naik) ---
    val sh = 1f - frame.bounce * 0.15f
    drawOval(
        c.primary.copy(alpha = 0.18f),
        topLeft = Offset(cx - 0.62f * u * sh, f.groundY - 0.10f * u * sh),
        size = Size(1.24f * u * sh, 0.20f * u * sh),
    )

    // Squash & stretch: figur memanjang/memipis dari kaki ke atas.
    withTransform({
        scale(1f / frame.squash, frame.squash, Offset(cx, f.footY))
    }) {
        // ---------- rambut belakang ----------
        fillLine(
            up().m(0f, -1.12f).c(0.80f, -1.12f, 1.20f, -0.62f, 1.18f, 0.02f)
                .c(1.16f, 0.52f, 1.10f, 0.86f, 0.94f, 1.06f)
                .q(0.86f, 0.90f, 0.72f, 0.98f)
                .q(0.60f, 0.80f, 0.40f, 0.80f).l(-0.40f, 0.80f)
                .q(-0.60f, 0.80f, -0.72f, 0.98f)
                .q(-0.86f, 0.90f, -0.94f, 1.06f)
                .c(-1.10f, 0.86f, -1.16f, 0.52f, -1.18f, 0.02f)
                .c(-1.20f, -0.62f, -0.80f, -1.12f, 0f, -1.12f).z().path,
            c.hairDark,
        )

        // ---------- kaki + sepatu ----------
        for (side in intArrayOf(-1, 1)) {
            val lx = 0.20f * side
            seg(lx, 1.38f, lx, 1.68f, c.line, 0.20f * u + lw * 2f)
            seg(lx, 1.38f, lx, 1.68f, Skin, 0.20f * u)
            ovalLine(lx + side * 0.04f, 1.74f, 0.17f, 0.10f, c.shoe)
            oval(lx + side * 0.02f, 1.70f, 0.07f, 0.03f, Color.White.copy(alpha = 0.35f))
        }

        // ---------- lengan ----------
        fun hand(side: Int): Offset = when {
            mood == BuddyMood.THINKING && side == 1 -> Offset(0.24f, 0.84f) // ke dagu
            mood == BuddyMood.LISTENING -> Offset(side * 0.14f, 1.22f) // bertaut di depan
            else -> {
                val r = frame.armRaise
                val wv = if (side == 1) frame.armWave else 0f
                Offset(side * (0.66f + 0.22f * r) + wv * 0.10f, 1.30f - 0.62f * r - abs(wv) * 0.10f)
            }
        }
        fun inFront(side: Int) =
            (mood == BuddyMood.THINKING && side == 1) || mood == BuddyMood.LISTENING
        fun arm(side: Int) {
            val h = hand(side)
            val sx = side * 0.40f
            seg(sx, 0.92f, h.x, h.y, c.line, 0.20f * u + lw * 2f)
            seg(sx, 0.92f, h.x, h.y, c.cloth, 0.20f * u)
            ovalLine(h.x, h.y, 0.115f, 0.115f, Skin)
        }
        for (side in intArrayOf(-1, 1)) if (!inFront(side)) arm(side)

        // ---------- badan: hoodie ----------
        val body = up().m(-0.40f, 0.74f).l(0.40f, 0.74f)
            .c(0.52f, 0.80f, 0.60f, 1.20f, 0.60f, 1.38f)
            .q(0.60f, 1.46f, 0.50f, 1.46f).l(-0.50f, 1.46f)
            .q(-0.60f, 1.46f, -0.60f, 1.38f)
            .c(-0.60f, 1.20f, -0.52f, 0.80f, -0.40f, 0.74f).z().path
        drawPath(
            body,
            Brush.verticalGradient(listOf(c.cloth, c.clothDark), startY = Y(0.74f), endY = Y(1.46f)),
        )
        drawPath(body, c.line, style = stroke(lw))
        // kantong kanguru
        drawPath(
            up().m(-0.30f, 1.40f).l(-0.24f, 1.20f).l(0.24f, 1.20f).l(0.30f, 1.40f).path,
            lerp(c.clothDark, Ink, 0.2f).copy(alpha = 0.55f),
            style = stroke(lw * 0.9f),
        )
        // kerah tudung
        val hood = up().m(-0.44f, 0.76f).q(0f, 1.02f, 0.44f, 0.76f).q(0f, 0.88f, -0.44f, 0.76f).z().path
        fillLine(hood, c.clothLight, lw * 0.8f)
        // tali hoodie
        for (side in intArrayOf(-1, 1)) {
            seg(side * 0.10f, 0.90f, side * 0.12f, 1.08f, c.clothLight, 0.035f * u)
            oval(side * 0.12f, 1.10f, 0.03f, 0.03f, c.clothLight)
        }
        // emblem "A" Arkacode
        drawPath(
            up().m(0.30f, 1.04f).l(0.36f, 0.92f).l(0.42f, 1.04f).m(0.325f, 1.00f).l(0.395f, 1.00f).path,
            c.clothLight,
            style = stroke(0.028f * u),
        )

        // ---------- kepala ----------
        val face = up().m(0f, -0.86f).c(0.64f, -0.86f, 0.98f, -0.48f, 0.98f, 0.02f)
            .c(0.98f, 0.46f, 0.60f, 0.78f, 0f, 0.80f)
            .c(-0.60f, 0.78f, -0.98f, 0.46f, -0.98f, 0.02f)
            .c(-0.98f, -0.48f, -0.64f, -0.86f, 0f, -0.86f).z().path
        fillLine(face, Skin)
        // bayangan poni di dahi
        clipPath(face) {
            drawPath(
                up().m(-1f, -0.40f).l(1f, -0.40f).l(1f, -0.12f)
                    .q(0.5f, 0.04f, 0f, -0.04f).q(-0.5f, 0.08f, -1f, -0.10f).z().path,
                SkinShade.copy(alpha = 0.75f),
            )
        }

        // ---------- pipi merona ----------
        for (side in intArrayOf(-1, 1)) {
            val bx = side * 0.60f
            drawOval(
                Brush.radialGradient(
                    listOf(Blush.copy(alpha = 0.55f), Blush.copy(alpha = 0f)),
                    center = P(bx, 0.52f),
                    radius = 0.22f * u,
                ),
                topLeft = P(bx - 0.22f, 0.52f - 0.13f),
                size = Size(0.44f * u, 0.26f * u),
            )
            for (k in 0..2) {
                val x0 = bx + (k - 1) * 0.07f
                seg(x0 + 0.02f, 0.48f, x0 - 0.02f, 0.56f, BlushLine.copy(alpha = 0.8f), 0.018f * u)
            }
        }

        // ---------- mata ----------
        for (side in intArrayOf(-1, 1)) {
            val ex = side * 0.40f
            val ey = 0.22f
            val ew = 0.24f
            val eh = 0.30f
            if (mood == BuddyMood.HAPPY) {
                // ^ ^
                drawPath(
                    up().m(ex - ew, ey + 0.08f).q(ex, ey - 0.36f, ex + ew, ey + 0.08f).path,
                    Ink,
                    style = stroke(0.075f * u),
                )
                continue
            }
            val eye = up().m(ex, ey - eh)
                .c(ex + ew * 0.56f, ey - eh, ex + ew, ey - eh * 0.50f, ex + ew, ey + eh * 0.05f)
                .c(ex + ew, ey + eh * 0.62f, ex + ew * 0.56f, ey + eh, ex, ey + eh)
                .c(ex - ew * 0.56f, ey + eh, ex - ew, ey + eh * 0.62f, ex - ew, ey + eh * 0.05f)
                .c(ex - ew, ey - eh * 0.50f, ex - ew * 0.56f, ey - eh, ex, ey - eh).z().path
            val o = frame.eyeOpen.coerceIn(0f, 1f)
            val pivot = ey + eh * 0.35f // garis tempat kelopak menutup
            val inner = Offset(ex - side * ew * 0.98f, ey - eh * 0.30f * o + (pivot - ey) * (1f - o))
            val outer = Offset(ex + side * ew * 1.04f, ey - eh * 0.22f * o + (pivot - ey) * (1f - o))
            val ctrl = Offset(ex, ey - eh * 1.38f * o + (pivot - ey + eh * 0.30f) * (1f - o))
            if (o > 0.12f) {
                // Kedip = bola mata memipih ke garis kelopak.
                withTransform({ scale(1f, o, P(ex, pivot)) }) {
                    drawPath(eye, Color.White)
                    clipPath(eye) {
                        val ix = ex + frame.lookX * 0.05f
                        val iy = ey + 0.04f + frame.lookY * 0.05f
                        val irw = 0.215f
                        val irh = 0.28f
                        drawOval(
                            Brush.verticalGradient(
                                listOf(c.irisTop, c.irisMid, c.irisBot),
                                startY = Y(iy - irh),
                                endY = Y(iy + irh),
                            ),
                            topLeft = P(ix - irw, iy - irh),
                            size = Size(2f * irw * u, 2f * irh * u),
                        )
                        oval(ix, iy - 0.01f, 0.105f, 0.15f, Ink.copy(alpha = 0.85f))
                        // pantulan cahaya bawah iris
                        drawPath(
                            up().m(ix - 0.13f, iy + 0.15f).q(ix, iy + 0.25f, ix + 0.13f, iy + 0.15f).path,
                            c.irisBot.copy(alpha = 0.9f),
                            style = stroke(0.045f * u),
                        )
                        // kilau: besar, kecil, dan titik
                        oval(ix - 0.08f, iy - 0.10f, 0.085f, 0.095f, Color.White)
                        oval(ix + 0.09f, iy + 0.09f, 0.04f, 0.04f, Color.White.copy(alpha = 0.9f))
                        oval(ix + 0.08f, iy - 0.15f, 0.022f, 0.022f, Color.White.copy(alpha = 0.9f))
                    }
                }
            }
            // garis bulu mata atas
            drawPath(
                up().m(inner.x, inner.y).q(ctrl.x, ctrl.y, outer.x, outer.y).path,
                Ink,
                style = stroke((0.050f + 0.020f * o) * u),
            )
            // kibasan kecil di sudut luar, sedikit turun = kesan lembut
            val flick = up().m(outer.x - side * 0.06f, outer.y - 0.05f * o)
                .l(outer.x + side * 0.07f, outer.y + 0.02f)
                .l(outer.x - side * 0.01f, outer.y + 0.03f).z().path
            drawPath(flick, Ink)
            drawPath(flick, Ink, style = stroke(0.02f * u))
            // bulu mata bawah tipis
            if (o > 0.3f) {
                drawPath(
                    up().m(ex - ew * 0.30f, ey + eh * 1.04f)
                        .q(ex, ey + eh * 1.10f, ex + ew * 0.30f, ey + eh * 1.04f).path,
                    Ink.copy(alpha = 0.45f),
                    style = stroke(0.018f * u),
                )
            }
        }

        // ---------- rambut samping ----------
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            fillLine(
                up().m(s * 1.02f, -0.50f).c(s * 1.08f, 0.05f, s * 1.04f, 0.52f, s * 0.88f, 0.92f)
                    .c(s * 0.86f, 0.60f, s * 0.84f, 0.18f, s * 0.72f, -0.22f).z().path,
                c.hair,
            )
        }

        // ---------- mahkota + poni ----------
        val crown = up().m(1.04f, 0.04f).c(1.10f, -0.60f, 0.70f, -1.14f, 0f, -1.14f)
            .c(-0.70f, -1.14f, -1.10f, -0.60f, -1.04f, 0.04f)
            .l(FringeEdge[0].x, FringeEdge[0].y)
        for (i in 1 until FringeEdge.size) {
            val prev = FringeEdge[i - 1]
            val cur = FringeEdge[i]
            val mx = (prev.x + cur.x) / 2f
            val my = (prev.y + cur.y) / 2f
            if (cur.tip) crown.q(mx + 0.07f, my + 0.02f, cur.x, cur.y) // pangkal → ujung
            else crown.q(mx - 0.02f, my + 0.06f, cur.x, cur.y) // ujung → pangkal
        }
        crown.z()
        drawPath(
            crown.path,
            Brush.verticalGradient(listOf(c.hairHi, c.hair, c.hair), startY = Y(-1.14f), endY = Y(0.04f)),
        )
        drawPath(crown.path, c.line, style = stroke(lw))
        // cincin kilau rambut (angel ring)
        val shine = Color.White.copy(alpha = 0.55f)
        drawPath(up().m(-0.62f, -0.66f).q(-0.30f, -0.84f, -0.02f, -0.80f).path, shine, style = stroke(0.06f * u))
        drawPath(up().m(0.16f, -0.80f).q(0.36f, -0.80f, 0.52f, -0.72f).path, shine, style = stroke(0.06f * u))
        // garis helai
        for (sl in arrayOf(
            floatArrayOf(0.30f, -0.80f, 0.42f, -0.30f),
            floatArrayOf(-0.06f, -0.86f, -0.02f, -0.30f),
            floatArrayOf(-0.40f, -0.80f, -0.50f, -0.26f),
        )) {
            drawPath(
                up().m(sl[0], sl[1]).q((sl[0] + sl[2]) / 2f + 0.05f, (sl[1] + sl[3]) / 2f, sl[2], sl[3]).path,
                c.line.copy(alpha = 0.45f),
                style = stroke(lw * 0.6f),
            )
        }

        // ---------- ahoge + ujung bercahaya (warna = keadaan) ----------
        fillLine(
            up().m(-0.04f, -1.10f).c(0.02f, -1.42f, 0.36f, -1.52f, 0.46f, -1.36f)
                .c(0.30f, -1.44f, 0.10f, -1.38f, 0.05f, -1.10f).z().path,
            c.hair,
        )
        val tip = P(0.46f, -1.36f)
        drawCircle(c.glow.copy(alpha = 0.22f), 0.075f * u, tip)
        drawCircle(c.glow.copy(alpha = 0.45f), 0.050f * u, tip)
        drawCircle(c.glow, 0.030f * u, tip)

        // ---------- mulut ----------
        val my = 0.60f
        if (frame.mouthOpen > 0.25f) {
            val mw = 0.09f + 0.03f * frame.mouthOpen
            val mh = 0.05f + 0.13f * frame.mouthOpen
            val m = up().m(-mw, my - 0.02f).q(0f, my + 0.01f, mw, my - 0.02f)
                .c(mw, my + mh, -mw, my + mh, -mw, my - 0.02f).z().path
            drawPath(m, MouthIn)
            clipPath(m) { oval(0f, my + mh * 0.85f, mw * 0.75f, mh * 0.45f, Tongue) }
            drawPath(m, Ink, style = stroke(0.025f * u))
        } else {
            val m = if (frame.mouthCurve > 0.3f) {
                // mulut kucing "ω"
                up().m(-0.10f, my - 0.02f).q(-0.05f, my + 0.06f, 0f, my).q(0.05f, my + 0.06f, 0.10f, my - 0.02f)
            } else {
                up().m(-0.06f, my + 0.01f).q(0f, my + 0.01f + 0.06f * frame.mouthCurve, 0.06f, my + 0.01f)
            }
            drawPath(m.path, Ink, style = stroke(0.032f * u))
        }

        // tangan di depan (dagu / bertaut) digambar di atas kepala & badan
        for (side in intArrayOf(-1, 1)) if (inFront(side)) arm(side)

        // ---------- penanda keadaan kecil ----------
        when (mood) {
            BuddyMood.THINKING -> for (b in arrayOf(
                floatArrayOf(0.95f, -0.95f, 0.05f),
                floatArrayOf(1.08f, -1.12f, 0.07f),
                floatArrayOf(1.06f, -1.38f, 0.10f),
            )) {
                oval(b[0], b[1], b[2], b[2], Color.White)
                drawOval(
                    ThinkAmber, topLeft = P(b[0] - b[2], b[1] - b[2]),
                    size = Size(2f * b[2] * u, 2f * b[2] * u), style = Stroke(0.02f * u),
                )
            }

            BuddyMood.HAPPY -> {
                drawStar(P(-0.98f, -0.90f), 0.09f * u, ThinkAmber)
                drawStar(P(1.00f, -0.70f), 0.065f * u, StarPink)
            }

            BuddyMood.LISTENING -> for (k in 0..1) {
                val r = 0.12f + 0.10f * k
                val a = (0.8f - 0.3f * k) * (0.75f + 0.25f * frame.bounce)
                drawPath(
                    up().m(1.10f + r * 0.5f, -0.10f - r).q(1.10f + r * 1.1f, 0.02f, 1.10f + r * 0.5f, 0.14f + r).path,
                    c.tertiary.copy(alpha = a.coerceIn(0f, 1f)),
                    style = stroke(0.04f * u),
                )
            }

            else -> Unit
        }
    }
}

/** Bintang kilau 4 sudut. */
private fun DrawScope.drawStar(center: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 8) {
        val rr = if (i % 2 == 0) r else r * 0.38f
        val a = -PI / 2 + i * PI / 4
        val x = center.x + rr * cos(a).toFloat()
        val y = center.y + rr * sin(a).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    drawPath(p, color)
}
