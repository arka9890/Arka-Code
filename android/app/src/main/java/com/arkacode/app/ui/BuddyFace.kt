package com.arkacode.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
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
 * Arka Buddy — figur chibi anime, digambar sepenuhnya dengan Compose Canvas.
 *
 * Tidak ada Lottie / aset bitmap: seluruh geometri (kepala, rambut, poni, mata,
 * badan, lengan, kaki) dihitung dari satu angka waktu, sehingga animasi murni
 * sinematika dan nol dependensi.
 */

/** Mood wajah Buddy. */
enum class BuddyMood { IDLE, LISTENING, THINKING, TALKING, HAPPY }

/**
 * Geometri wajah untuk satu titik waktu. Nilai murni (tanpa Compose) supaya
 * bisa diuji langsung dari unit test JVM — lihat `BuddyFaceTest`.
 *
 * @param eyeOpen 0 = terpejam, 1 = lebar penuh.
 * @param mouthOpen 0 = mulut tertutup, 1 = membuka penuh.
 * @param mouthCurve -1 cemberut … +1 senyum (kontrol kurva, Y positif = ke bawah).
 * @param lookX/lookY pergeseran pupil -1…1.
 * @param squash regangan vertikal (>1 = memanjang, <1 = memipih; volume dijaga).
 * @param bounce pergeseran melayang -1…1.
 */
data class BuddyFrame(
    val eyeOpen: Float,
    val mouthOpen: Float,
    val mouthCurve: Float,
    val lookX: Float,
    val lookY: Float,
    val squash: Float,
    val bounce: Float,
)

// Semua periode di bawah HARUS habis membagi CYCLE_MS, supaya animasi melingkar
// mulus — nilai di-wrap dari CYCLE_MS ke 0 tanpa lompatan yang terlihat.
private const val CYCLE_MS = 60_000f
private const val BLINK_MS = 3_000f

// Amber indikator "berpikir": di luar palet clay agar keadaan ke-4 terbaca
// sekilas tanpa perlu menambah warna tema.
private val ThinkAmber = Color(0xFFF2B33D)

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
        )

        BuddyMood.LISTENING -> BuddyFrame(
            // Jarang kedip: mata melebar, fokus ke lawan bicara.
            eyeOpen = 1f - closed * 0.4f,
            mouthOpen = 0.30f + wave(t, 1_000f) * 0.06f,
            mouthCurve = 0f,
            lookX = 0f,
            lookY = 0f,
            squash = 1f,
            bounce = float * 0.4f,
        )

        BuddyMood.THINKING -> BuddyFrame(
            eyeOpen = 1f - closed,
            mouthOpen = 0f,
            mouthCurve = wave(t, 1_200f) * 0.5f,
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
                squash = 1f + (open - 0.4f) * 0.12f,
                bounce = float * 0.5f,
            )
        }

        BuddyMood.HAPPY -> BuddyFrame(
            eyeOpen = 0f,
            mouthOpen = 0.32f + wave(t, 1_000f) * 0.05f,
            mouthCurve = 1f,
            lookX = 0f,
            lookY = 0f,
            squash = 1.04f,
            bounce = float * 0.8f,
        )
    }
}

/**
 * Tata letak figur chibi Buddy di kanvas selebar [w] setinggi [h].
 *
 * Proporsi chibi: kepala besar, tubuh kecil — itulah yang bikin dia "imut".
 * Semua nilai dalam piksel; [unit] adalah satuan ukuran (tinggi figur ≈ 4,2
 * unit dari puncak japek sampai dasar sepatu) supaya bagian-bagiannya bisa
 * digeser tanpa menyentuh tiap gambar.
 */
data class BuddyFigure(
    val unit: Float,
    val headCx: Float,
    val headCy: Float,
    val bodyTop: Float,
    val bodyBottom: Float,
    val footY: Float,
)

/**
 * Hitung tata letak figur untuk kanvas [w] x [h] dan [frame].
 *
 * Figur punya proporsi tetap dan hanya diperkecil seperlunya supaya muat di
 * kanvas.
 *
 * Fungsi murni supaya bisa dites tanpa Compose — lihat `BuddyFaceTest`.
 */
fun buddyFigure(w: Float, h: Float, frame: BuddyFrame): BuddyFigure {
    // Skala figuur: tinggi ±3,9 unit, dilebihkan 12% biar ada margin.
    val unit = min(h / 3.9f / 1.12f, w / 2.8f)
    val bob = frame.bounce * unit * 0.020f
    // Pusat figur (japek s/d kaki) di tengah kanvas.
    val headCy = h / 2f - 0.765f * unit + bob
    return BuddyFigure(
        unit = unit,
        headCx = w * 0.500f,
        headCy = headCy,
        bodyTop = headCy + 1.20f * unit,
        bodyBottom = headCy + 2.10f * unit,
        footY = headCy + 2.52f * unit,
    )
}

/** Warna indikator keadaan per mood. */
@Composable
private fun moodGlow(mood: BuddyMood): Color = when (mood) {
    BuddyMood.IDLE, BuddyMood.TALKING -> MaterialTheme.colorScheme.primary
    BuddyMood.LISTENING, BuddyMood.HAPPY -> MaterialTheme.colorScheme.tertiary
    BuddyMood.THINKING -> ThinkAmber
}

/** Path busur kuadrat: dari [p0] lewat [ctrl] ke [p1]. */
private fun quadPath(p0: Offset, ctrl: Offset, p1: Offset): Path =
    Path().apply {
        moveTo(p0.x, p0.y)
        quadraticBezierTo(ctrl.x, ctrl.y, p1.x, p1.y)
    }

/** Path busur kubik: dari [p0] lewat [c1],[c2] ke [p1]. */
private fun cubicPath(p0: Offset, c1: Offset, c2: Offset, p1: Offset): Path =
    Path().apply {
        moveTo(p0.x, p0.y)
        cubicTo(c1.x, c1.y, c2.x, c2.y, p1.x, p1.y)
    }

/** Satu gumpalan poni: pangkal lebar di garis rambut, ujung runcing ke bawah. */
private data class Fringe(val dx: Float, val tip: Float, val skew: Float, val halfW: Float)

private val Fringes = listOf(
    Fringe(-0.52f, -0.10f, 0.08f, 0.31f),
    Fringe(0.00f, -0.05f, 0.00f, 0.29f),
    Fringe(0.52f, -0.12f, -0.08f, 0.31f),
)

/**
 * Avatar Arka Buddy. Isi kanvas dari [modifier], lalu gambar figur chibi
 * anime: rambut berponi, kepala besar, mata besar yang bisa melihat &
 * berkedip, pipi, mulut yang membuka saat bicara, badan, lengan, dan kaki.
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
    val dark = isClayDark()
    // Tinta mata/mulut navy pekat: kontras terhadap wajah terang.
    val ink = Color(0xFF0B2136)
    // Rambut & iris diturunkan dari warna tema supaya tetap satu keluarga
    // warna dengan claymorphism app.
    val hair = lerp(ink, primary, 0.25f)
    val hairHi = lerp(hair, primary, 0.40f)
    val skin = Color(0xFFEFF5FC)
    val irisTop = lerp(primary, ink, 0.45f)
    val irisBottom = lerp(primary, Color.White, 0.30f)
    val cheek = MaterialTheme.colorScheme.error
    val cloth = lerp(primary, Color.Black, if (dark) 0.45f else 0.12f)
    val limb = Color(0xFFE6EEF8)

    Canvas(modifier) {
        val f = buddyFigure(size.width, size.height, frame)
        if (f.unit <= 0f) return@Canvas
        val u = f.unit
        val cx = f.headCx
        val cy = f.headCy
        val shoulderY = f.bodyTop
        val hipY = f.bodyBottom
        val footY = f.footY
        val eyeY = cy + 0.22f * u
        val blushY = cy + 0.54f * u
        val noseY = cy + 0.60f * u
        val mouthY = cy + 0.72f * u

        // Squash & stretch: figur memanjang/memipis dari kaki ke atas,
        // volumenya tetap terjaga.
        withTransform({
            this.scale(1f / frame.squash, frame.squash, Offset(cx, footY))
        }) {
            // --- rambut belakang: siluet lebih besar dari kepala ---
            drawOval(
                color = hair,
                topLeft = Offset(cx - 0.92f * u, cy - 1.16f * u),
                size = Size(1.84f * u, 2.04f * u),
            )
            drawOval(
                color = hair,
                topLeft = Offset(cx - 0.88f * u, cy - 0.26f * u),
                size = Size(1.76f * u, 1.52f * u),
            )

            // --- kaki + sepatu ---
            for (side in intArrayOf(-1, 1)) {
                val lx = cx + side * 0.34f * u
                drawLine(
                    color = limb,
                    start = Offset(lx, hipY),
                    end = Offset(lx, footY),
                    strokeWidth = 0.28f * u,
                    cap = StrokeCap.Round,
                )
                drawOval(
                    color = hair,
                    topLeft = Offset(lx + side * 0.05f * u - 0.30f * u, footY - 0.12f * u),
                    size = Size(0.60f * u, 0.32f * u),
                )
            }

            // --- lengan ---
            for (side in intArrayOf(-1, 1)) {
                drawLine(
                    color = limb,
                    start = Offset(cx + side * 0.80f * u, shoulderY),
                    end = Offset(cx + side * 1.22f * u, hipY - 0.34f * u),
                    strokeWidth = 0.19f * u,
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = limb,
                    radius = 0.15f * u,
                    center = Offset(cx + side * 1.22f * u, hipY - 0.34f * u),
                )
            }

            // --- badan: tunic ---
            drawPath(
                Path().apply {
                    moveTo(cx - 0.94f * u, shoulderY)
                    lineTo(cx + 0.94f * u, shoulderY)
                    lineTo(cx + 1.10f * u, hipY)
                    lineTo(cx - 1.10f * u, hipY)
                    close()
                },
                Brush.verticalGradient(
                    colors = listOf(primary, cloth),
                    startY = shoulderY,
                    endY = hipY,
                ),
            )
            // Kerah
            drawPath(
                Path().apply {
                    moveTo(cx - 0.30f * u, shoulderY)
                    lineTo(cx, shoulderY + 0.11f * u)
                    lineTo(cx + 0.30f * u, shoulderY)
                    lineTo(cx, shoulderY - 0.04f * u)
                    close()
                },
                skin,
            )
            // Lipatan rok
            drawPath(
                Path().apply {
                    moveTo(cx - 0.80f * u, hipY - 0.18f * u)
                    quadraticBezierTo(
                        cx, hipY - 0.06f * u,
                        cx + 0.80f * u, hipY - 0.18f * u,
                    )
                },
                lerp(primary, Color.Black, 0.35f),
                style = Stroke(width = 0.06f * u, cap = StrokeCap.Round),
            )

            // --- wajah: oval chibi, agak turun supaya ada dahi di bawah poni ---
            drawOval(
                color = skin,
                topLeft = Offset(cx - 0.78f * u, cy - 0.68f * u),
                size = Size(1.56f * u, 1.60f * u),
            )
            // Pipi (blush) lembut
            for (side in intArrayOf(-1, 1)) {
                val bx = cx + side * 0.50f * u
                for (i in 0 until 6) {
                    val k = 1f - i / 6f
                    drawOval(
                        color = cheek.copy(alpha = 0.10f),
                        topLeft = Offset(bx - 0.24f * u * k, blushY - 0.15f * u * k),
                        size = Size(0.48f * u * k, 0.30f * u * k),
                    )
                }
            }

            // --- rambut: mahkota + garis rambut + poni ---
            drawPath(
                Path().apply {
                    moveTo(cx - 0.90f * u, cy - 0.24f * u)
                    quadraticBezierTo(cx - 0.86f * u, cy - 1.02f * u, cx, cy - 1.02f * u)
                    quadraticBezierTo(cx + 0.86f * u, cy - 1.02f * u, cx + 0.90f * u, cy - 0.24f * u)
                    // Garis rambut: busur halus.
                    quadraticBezierTo(cx, cy - 0.46f * u, cx - 0.90f * u, cy - 0.24f * u)
                    close()
                },
                hair,
            )
            // Poni: tiga gumpalan membulat yang menjuntai dari garis rambut.
            for (fr in Fringes) {
                val x0 = cx + fr.dx * u
                val tl = Offset(x0 - fr.halfW * u, cy - 0.40f * u)
                val tr = Offset(x0 + fr.halfW * u, cy - 0.40f * u)
                drawPath(
                    Path().apply {
                        moveTo(tl.x, tl.y)
                        lineTo(tr.x, tr.y)
                        quadraticBezierTo(x0 + fr.skew * u, cy + fr.tip * u, tl.x, tl.y)
                        close()
                    },
                    hair,
                )
            }
            // Highlight rambut
            drawPath(
                quadPath(
                    Offset(cx - 0.52f * u, cy - 0.74f * u),
                    Offset(cx - 0.18f * u, cy - 0.86f * u),
                    Offset(cx + 0.30f * u, cy - 0.72f * u),
                ),
                hairHi.copy(alpha = 0.9f),
                style = Stroke(width = 0.072f * u, cap = StrokeCap.Round),
            )

            // --- japek (ahoge): helai rambut melengkung di atas kepala ---
            drawPath(
                cubicPath(
                    Offset(cx + 0.04f * u, cy - 0.86f * u),
                    Offset(cx + 0.20f * u, cy - 1.40f * u),
                    Offset(cx + 0.58f * u, cy - 1.38f * u),
                    Offset(cx + 0.66f * u, cy - 1.16f * u),
                ),
                hair,
                style = Stroke(width = 0.080f * u, cap = StrokeCap.Round),
            )
            // Ujung japek menyala = penanda keadaan (warna per mood).
            val tip = Offset(cx + 0.66f * u, cy - 1.16f * u)
            drawCircle(glow.copy(alpha = 0.20f), 0.058f * u, tip)
            drawCircle(glow.copy(alpha = 0.45f), 0.040f * u, tip)
            drawCircle(glow, 0.024f * u, tip)

            // --- MATA anime chibi: iris besar, kilau, kelopak atas tebal ---
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * 0.34f * u
                val ew = 0.27f * u
                val eh = 0.32f * u
                if (mood == BuddyMood.HAPPY) {
                    drawPath(
                        quadPath(
                            Offset(ex - ew, eyeY + 0.10f * u),
                            Offset(ex, eyeY - 0.50f * u),
                            Offset(ex + ew, eyeY + 0.10f * u),
                        ),
                        ink,
                        style = Stroke(width = 0.10f * u, cap = StrokeCap.Round),
                    )
                    continue
                }
                val eye = Path().apply {
                    addOval(Rect(ex - ew, eyeY - eh, ex + ew, eyeY + eh))
                }
                drawPath(eye, Color(0xFFF4F8FF))
                // Iris di tengah bola mata, digeser sedikit ke bawah.
                val ir = ew * 0.92f
                val iy = eyeY + eh * 0.10f + frame.lookY * 0.05f * u
                val ix = ex + frame.lookX * 0.10f * u
                clipPath(eye) {
                    drawOval(
                        brush = Brush.verticalGradient(
                            colors = listOf(irisTop, irisBottom),
                            startY = iy - ir * 1.24f,
                            endY = iy + ir * 1.24f,
                        ),
                        topLeft = Offset(ix - ir, iy - ir * 1.24f),
                        size = Size(ir * 2f, ir * 2.48f),
                    )
                    // Pupil + kilau besar + kilau kecil
                    drawOval(
                        color = ink,
                        topLeft = Offset(ix - ir * 0.44f, iy - ir * 0.64f),
                        size = Size(ir * 0.88f, ir * 1.28f),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.95f),
                        radius = ir * 0.36f,
                        center = Offset(ix - ir * 0.30f, iy - ir * 0.46f),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.80f),
                        radius = ir * 0.15f,
                        center = Offset(ix + ir * 0.34f, iy + ir * 0.46f),
                    )
                    // Kelopak atas: pita gelap di busur atas bola mata. Saat
                    // berkedip pita ini turun menutupi iris.
                    val lash = Path()
                    val back = ArrayList<Offset>(17)
                    for (i in 0..16) {
                        val a = PI.toFloat() * (1.04f - 1.08f * i / 16f)
                        val px = ex + ew * cos(a)
                        val py = eyeY - eh * sin(a)
                        if (i == 0) lash.moveTo(px, py) else lash.lineTo(px, py)
                        val taper = abs(cos(a))
                        val depth = eh * (0.40f + 1.60f * (1f - frame.eyeOpen)) * (1f - 0.55f * taper)
                        back.add(Offset(px, py + depth))
                    }
                    back.asReversed().forEach { lash.lineTo(it.x, it.y) }
                    lash.close()
                    drawPath(lash, ink)
                }
                // Eyelash di sudut luar (mata tersenyum)
                drawLine(
                    color = ink,
                    start = Offset(ex + side * ew * 0.86f, eyeY - eh * 0.52f),
                    end = Offset(ex + side * ew * 1.14f, eyeY - eh * 0.66f),
                    strokeWidth = 0.024f * u,
                    cap = StrokeCap.Round,
                )
            }

            // --- MULUT kecil ---
            val mw = 0.17f * u
            if (frame.mouthOpen > 0.25f) {
                drawOval(
                    color = Color(0xFF8E3A52),
                    topLeft = Offset(cx - mw, mouthY - mw * (0.35f + frame.mouthOpen * 0.9f)),
                    size = Size(mw * 2f, mw * (0.70f + frame.mouthOpen * 1.8f)),
                )
                drawOval(
                    color = Color(0xFFE07C94),
                    topLeft = Offset(cx - mw * 0.72f, mouthY + mw * 0.10f),
                    size = Size(mw * 1.44f, mw * 0.72f),
                )
            } else {
                drawPath(
                    quadPath(
                        Offset(cx - mw, mouthY),
                        Offset(
                            cx,
                            mouthY + 0.11f * u * frame.mouthCurve + 0.12f * u * frame.mouthOpen,
                        ),
                        Offset(cx + mw, mouthY),
                    ),
                    ink,
                    style = Stroke(width = 0.065f * u, cap = StrokeCap.Round),
                )
            }

            // --- HIDUNG kecil
            drawLine(
                color = Color(0xFFC6D6EC),
                start = Offset(cx + 0.02f * u, noseY),
                end = Offset(cx + 0.07f * u, noseY + 0.06f * u),
                strokeWidth = 0.045f * u,
                cap = StrokeCap.Round,
            )
        }
    }
}
