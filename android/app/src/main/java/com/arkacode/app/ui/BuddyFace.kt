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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Arka Buddy — figur humanoid imut, digambar sepenuhnya dengan Compose Canvas.
 *
 * Tidak ada Lottie / aset bitmap: seluruh geometri (kepala, rambut, tubuh,
 * lengan, kaki, mata, mulut) dihitung dari satu angka waktu, sehingga animasi
 * murni sinematika dan nol dependensi.
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
 * Tata letak figur humanoid Buddy di kanvas selebar [w] setinggi [h].
 *
 * Proporsi chibi: kepala besar, tubuh kecil — itulah yang bikin dia
 * "imut". Semua nilai dalam piksel; [unit] adalah satuan ukuran figur
 * (tinggi figur ≈ 0,87 unit) supaya bagian-bagiannya bisa digeser tanpa
 * menyentuh tiap gambar.
 */
data class BuddyFigure(
    val unit: Float,
    val headCx: Float,
    val headCy: Float,
    val headRx: Float,
    val headRy: Float,
    val bodyTop: Float,
    val bodyBottom: Float,
    val bodyHalfW: Float,
    val shoulderY: Float,
    val handY: Float,
    val handDx: Float,
    val footY: Float,
    val legDx: Float,
)

/**
 * Hitung tata letak figur untuk kanvas [w] x [h] dan [frame].
 *
 * Figur punya proporsi tetap (tinggi ≈ 0,9 unit, lebar ≈ 0,44 unit) dan
 * hanya diperkecil seperlunya supaya muat di kanvas.
 *
 * Fungsi murni supaya bisa dites tanpa Compose — lihat `BuddyFaceTest`.
 */
fun buddyFigure(w: Float, h: Float, frame: BuddyFrame): BuddyFigure {
    // Skala figuur: batasi tinggi (0,96 unit) dan lebar (0,48 unit, rambut).
    val unit = min(h / 0.96f, w / 0.48f)
    val bob = frame.bounce * unit * 0.020f
    // Volume dijaga: kepala memanjang sedikit menyempit sedikit.
    val r = unit * 0.200f
    val headRx = r / frame.squash
    val headRy = r * frame.squash
    // Figur dari puncak japek (1,42 ry di atas pusat kepala) sampai dasar sepatu.
    val figH = 2.20f * headRy + 0.43f * unit
    val headCy = (h - figH) / 2f + 1.42f * headRy + bob
    val bodyTop = headCy + headRy * 0.78f
    val bodyBottom = bodyTop + 0.26f * unit
    return BuddyFigure(
        unit = unit,
        headCx = w * 0.500f,
        headCy = headCy,
        headRx = headRx,
        headRy = headRy,
        bodyTop = bodyTop,
        bodyBottom = bodyBottom,
        bodyHalfW = unit * 0.105f,
        shoulderY = bodyTop + unit * 0.020f,
        handY = bodyBottom - unit * 0.010f,
        handDx = unit * 0.150f,
        footY = bodyBottom + 0.135f * unit,
        legDx = unit * 0.045f,
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
 * Avatar Arka Buddy. Isi kanvas dari [modifier], lalu gambar figur
 * humanoid: rambut, kepala clay, mata yang bisa melihat & berkedip, pipi,
 * mulut yang membuka saat bicara, tubuh, lengan, dan kaki.
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
    // Kepala: gradien biru clay — terang di atas, lebih pekat di bawah, supaya
    // tetap punya volume dan kontras di atas kartu (terang maupun gelap).
    val primary = MaterialTheme.colorScheme.primary
    val dark = isClayDark()
    val headTop = lerp(primary, Color.White, if (dark) 0.25f else 0.55f)
    val headBottom = lerp(primary, Color.Black, if (dark) 0.45f else 0.15f)
    // Tinta mata/mulut/rambut navy pekat: kontras terhadap kedua ujung gradien.
    val ink = Color(0xFF0B2136)
    val cheek = MaterialTheme.colorScheme.error

    Canvas(modifier) {
        val f = buddyFigure(size.width, size.height, frame)
        if (f.unit <= 0f) return@Canvas
        val u = f.unit
        val cx = f.headCx

        // --- kaki & lengan (di belakang badan) ---
        for (side in intArrayOf(-1, 1)) {
            drawLine(
                color = headBottom,
                start = Offset(cx + f.legDx * 1.6f * side, f.bodyBottom - u * 0.010f),
                end = Offset(cx + f.legDx * 1.6f * side, f.footY),
                strokeWidth = u * 0.048f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = headBottom,
                start = Offset(cx + f.bodyHalfW * 0.80f * side, f.shoulderY),
                end = Offset(cx + f.handDx * side, f.handY),
                strokeWidth = u * 0.050f,
                cap = StrokeCap.Round,
            )
            // Sepatu
            drawRoundRect(
                color = ink,
                topLeft = Offset(cx + f.legDx * 1.6f * side - u * 0.038f, f.footY - u * 0.012f),
                size = Size(u * 0.076f, u * 0.034f),
                cornerRadius = CornerRadius(u * 0.016f, u * 0.016f),
            )
        }

        // --- badan: pill clay lembut ---
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(primary, lerp(primary, Color.Black, if (dark) 0.55f else 0.22f)),
                startY = f.bodyTop,
                endY = f.bodyBottom,
            ),
            topLeft = Offset(cx - f.bodyHalfW, f.bodyTop),
            size = Size(f.bodyHalfW * 2f, f.bodyBottom - f.bodyTop),
            cornerRadius = CornerRadius(f.bodyHalfW, f.bodyHalfW),
        )

        // --- telinga (di belakang kepala) ---
        for (side in intArrayOf(-1, 1)) {
            drawCircle(
                color = headBottom,
                radius = f.headRx * 0.16f,
                center = Offset(cx + f.headRx * 0.95f * side, f.headCy + f.headRy * 0.10f),
            )
        }

        // --- kepala ---
        val head = Offset(cx - f.headRx, f.headCy - f.headRy)
        val headSize = Size(f.headRx * 2f, f.headRy * 2f)
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(headTop, headBottom),
                startY = head.y,
                endY = head.y + headSize.height,
            ),
            topLeft = head,
            size = headSize,
        )
        // Highlight lembut khas clay di pelipis atas.
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.12f else 0.35f),
                    Color.Transparent,
                ),
                startY = head.y,
                endY = head.y + headSize.height * 0.55f,
            ),
            topLeft = head,
            size = headSize,
        )
        // Garis luar tipis agar wajah tak melebur ke warna kartu.
        drawOval(
            color = ink.copy(alpha = 0.14f),
            topLeft = head,
            size = headSize,
            style = Stroke(width = u * 0.012f),
        )

        // --- rambut: mahkota menutupi atas kepala, poni melengkung ---
        // Bawah poni dijaga di atas garis mata supaya mata tak tertutup.
        val hair = Path().apply {
            moveTo(cx - f.headRx * 1.04f, f.headCy - f.headRy * 0.20f)
            quadraticBezierTo(
                cx - f.headRx * 1.10f, f.headCy - f.headRy * 1.18f,
                cx, f.headCy - f.headRy * 1.18f,
            )
            quadraticBezierTo(
                cx + f.headRx * 1.10f, f.headCy - f.headRy * 1.18f,
                cx + f.headRx * 1.04f, f.headCy - f.headRy * 0.20f,
            )
            quadraticBezierTo(
                cx + f.headRx * 0.60f, f.headCy - f.headRy * 0.62f,
                cx + f.headRx * 0.10f, f.headCy - f.headRy * 0.42f,
            )
            quadraticBezierTo(
                cx - f.headRx * 0.30f, f.headCy - f.headRy * 0.36f,
                cx - f.headRx * 1.04f, f.headCy - f.headRy * 0.20f,
            )
            close()
        }
        drawPath(hair, ink)

        // --- japek: satu helai rambut di atas kepala, ujungnya menyala
        // sebagai penanda keadaan (warna per mood) ---
        val tuftBase = Offset(cx + f.headRx * 0.10f, f.headCy - f.headRy * 1.00f)
        val tuftTip = Offset(cx + f.headRx * 0.42f, f.headCy - f.headRy * 1.42f)
        drawLine(
            color = ink,
            start = tuftBase,
            end = tuftTip,
            strokeWidth = u * 0.026f,
            cap = StrokeCap.Round,
        )
        drawCircle(glow.copy(alpha = 0.20f), u * 0.058f, tuftTip)
        drawCircle(glow.copy(alpha = 0.45f), u * 0.040f, tuftTip)
        drawCircle(glow, u * 0.024f, tuftTip)

        // --- mata ---
        // Mata duduk sedikit di bawah garis rambut poni supaya tak tertutup.
        val eyeDX = f.headRx * 0.40f
        val eyeY = f.headCy + f.headRy * 0.05f
        val eyeW = f.headRx * 0.30f
        val eyeH = f.headRy * 0.34f
        val sclera = Color(0xFFEFF4FB)
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + eyeDX * side
            when {
                mood == BuddyMood.HAPPY -> {
                    // Mata bahagia: busur terbuka ke atas.
                    val p = Path().apply {
                        moveTo(ex - eyeW, eyeY + eyeH * 0.30f)
                        quadraticBezierTo(ex, eyeY - eyeH * 0.85f, ex + eyeW, eyeY + eyeH * 0.30f)
                    }
                    drawPath(
                        path = p,
                        color = ink,
                        style = Stroke(width = u * 0.030f, cap = StrokeCap.Round),
                    )
                }

                frame.eyeOpen < 0.10f -> {
                    // Terpejam: garis pipih.
                    drawLine(
                        color = ink,
                        start = Offset(ex - eyeW, eyeY),
                        end = Offset(ex + eyeW, eyeY),
                        strokeWidth = u * 0.026f,
                        cap = StrokeCap.Round,
                    )
                }

                else -> {
                    val h = eyeH * frame.eyeOpen
                    drawOval(
                        color = sclera,
                        topLeft = Offset(ex - eyeW, eyeY - h),
                        size = Size(eyeW * 2f, h * 2f),
                    )
                    // Pupil + kilau, di-clip supaya tak keluar dari bola mata.
                    val pw = eyeW * 0.66f
                    val px = ex + frame.lookX * eyeW * 0.40f
                    val py = eyeY + frame.lookY * h * 0.30f
                    clipRect(
                        left = ex - eyeW,
                        top = eyeY - h,
                        right = ex + eyeW,
                        bottom = eyeY + h,
                    ) {
                        drawCircle(ink, pw, Offset(px, py))
                        drawCircle(
                            color = Color.White.copy(alpha = 0.90f),
                            radius = eyeW * 0.26f,
                            center = Offset(px - pw * 0.36f, py - pw * 0.40f),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.55f),
                            radius = eyeW * 0.13f,
                            center = Offset(px + pw * 0.40f, py + pw * 0.36f),
                        )
                    }
                }
            }
        }

        // --- pipi ---
        val cheekR = f.headRx * 0.17f
        val cheekY = f.headCy + f.headRy * 0.42f
        drawCircle(cheek.copy(alpha = 0.30f), cheekR, Offset(cx + f.headRx * 0.62f, cheekY))
        drawCircle(cheek.copy(alpha = 0.30f), cheekR, Offset(cx - f.headRx * 0.62f, cheekY))

        // --- mulut ---
        // Satu jalur "lens": dua kurva berbagi ujung. Saat mouthOpen = 0 kedua
        // kurva berimpit sehingga hanya garis tersimpan — mulut jadi terlihat
        // tertutup tanpa lompatan visual saat mulai membuka.
        val mouthY = f.headCy + f.headRy * 0.55f
        val mw = f.headRx * 0.30f
        val base = mw * 0.80f * frame.mouthCurve
        val mouth = Path().apply {
            moveTo(cx - mw, mouthY)
            quadraticBezierTo(cx, mouthY + base + mw * 1.25f * frame.mouthOpen, cx + mw, mouthY)
            quadraticBezierTo(cx, mouthY + base - mw * 0.85f * frame.mouthOpen, cx - mw, mouthY)
            close()
        }
        drawPath(mouth, ink, style = Fill)
        drawPath(
            path = mouth,
            color = ink,
            style = Stroke(width = u * 0.024f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
