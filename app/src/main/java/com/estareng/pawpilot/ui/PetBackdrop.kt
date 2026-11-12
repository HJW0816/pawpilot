package com.estareng.pawpilot.ui

import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Static, decorative background. No view, touch target, animation or network resource. */
class PetBackdrop(
    private val kind: Int,
    private val base: Int,
    private val accent: Int,
    private val night: Boolean,
    private val portrait: Boolean = false,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    private var filter: ColorFilter? = null

    private fun pigment(alpha: Int) {
        paint.color = accent
        paint.alpha = alpha * opacity / 255
        paint.style = Paint.Style.FILL
        paint.colorFilter = filter
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        paint.color = base
        paint.alpha = opacity
        paint.style = Paint.Style.FILL
        paint.colorFilter = filter
        canvas.drawRect(bounds, paint)
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (portrait && kind in 0..1) {
            val frame = canvas.save()
            canvas.translate(bounds.exactCenterX(), bounds.top + h * .53f)
            val scale = minOf(w, h) / 224f
            canvas.scale(scale, scale)
            drawPortrait(canvas)
            canvas.restoreToCount(frame)
            return
        }
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        val decoration = canvas.saveLayerAlpha(0f, 0f, w, h, if (portrait) 255 else if (night) 16 else 18)
        // Strong portrait is only placed in a reserved illustration view, never under text.
        val radius = if (portrait) minOf(w * .43f, h * .43f) else minOf(w * .12f, h * .14f)
        val cx = if (portrait) w * .5f else w * .88f
        val cy = if (portrait) h * .57f else h * .36f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(radius / 100f, radius / 100f)
        pigment(150)
        if (kind == 0 || kind == 1) {
            if (kind == 1) {
                // Pointed ears; curved head with a softer chin.
                path.reset()
                path.moveTo(-76f, -16f)
                path.lineTo(-82f, -96f)
                path.lineTo(-30f, -59f)
                path.quadTo(0f, -70f, 30f, -59f)
                path.lineTo(82f, -96f)
                path.lineTo(76f, -16f)
                path.cubicTo(95f, 66f, -95f, 66f, -76f, -16f)
                canvas.drawPath(path, paint)
            } else {
                canvas.drawOval(-76f, -66f, 76f, 66f, paint)
                canvas.drawOval(-105f, -76f, -49f, 28f, paint)
                canvas.drawOval(49f, -76f, 105f, 28f, paint)
            }
            // Face details remain visible in the dedicated portrait area.
            pigment(255)
            canvas.drawCircle(-27f, -7f, 4f, paint)
            canvas.drawCircle(27f, -7f, 4f, paint)
            canvas.drawOval(-8f, 11f, 8f, 21f, paint)
            if (kind == 1) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.strokeCap = Paint.Cap.ROUND
                canvas.drawLine(-49f, 15f, -93f, 5f, paint)
                canvas.drawLine(-49f, 28f, -92f, 36f, paint)
                canvas.drawLine(49f, 15f, 93f, 5f, paint)
                canvas.drawLine(49f, 28f, 92f, 36f, paint)
            }
        } else {
            paw(canvas, 0f, 0f, 2.3f)
        }
        canvas.restore()
        if (!portrait) {
            paw(canvas, w * .05f, h * .72f, radius / 130f)
            paw(canvas, w * .12f, h * .85f, radius / 155f)
        }
        canvas.restoreToCount(decoration)
        canvas.restoreToCount(saved)
    }

    private fun shade(hex: String) {
        paint.color = Color.parseColor(hex)
        paint.alpha = opacity
        paint.style = Paint.Style.FILL
        paint.colorFilter = filter
    }

    private fun drawPortrait(c: Canvas) {
        // Layered, static character art. These colors are confined to the artwork.
        shade(if (night) "#484039" else "#DED5CA")
        c.drawOval(-66f, 74f, 66f, 88f, paint)
        if (kind == 0) {
            shade("#99613D")
            c.drawOval(-102f, -65f, -42f, 41f, paint)
            c.drawOval(42f, -65f, 102f, 41f, paint)
            shade("#C48B60")
            c.drawOval(-91f, -48f, -60f, 23f, paint)
            c.drawOval(60f, -48f, 91f, 23f, paint)
            shade("#F3CF9D")
            c.drawOval(-75f, -73f, 75f, 77f, paint)
            shade("#D5A16E")
            c.drawOval(6f, -49f, 62f, 20f, paint)
            shade("#FFF0D6")
            c.drawOval(-48f, 8f, 48f, 63f, paint)
        } else {
            shade("#B8A8CC")
            path.reset()
            path.moveTo(-74f, -14f); path.quadTo(-96f, -103f, -68f, -91f)
            path.lineTo(-27f, -58f); path.quadTo(0f, -68f, 27f, -58f)
            path.lineTo(68f, -91f); path.quadTo(96f, -103f, 74f, -14f)
            path.cubicTo(92f, 91f, -92f, 91f, -74f, -14f)
            c.drawPath(path, paint)
            shade("#E5B2BE")
            path.reset(); path.moveTo(-68f, -39f); path.lineTo(-74f, -76f); path.lineTo(-43f, -51f); path.close(); c.drawPath(path, paint)
            path.reset(); path.moveTo(68f, -39f); path.lineTo(74f, -76f); path.lineTo(43f, -51f); path.close(); c.drawPath(path, paint)
            shade("#8C77A6")
            c.drawOval(-6f, -57f, 6f, -25f, paint)
            c.drawOval(-30f, -52f, -20f, -29f, paint)
            c.drawOval(20f, -52f, 30f, -29f, paint)
            shade("#EEE7F3")
            c.drawOval(-45f, 12f, 45f, 59f, paint)
        }
        shade("#3B3035")
        c.drawOval(-39f, -16f, -23f, 5f, paint)
        c.drawOval(23f, -16f, 39f, 5f, paint)
        shade("#FFFFFF")
        c.drawCircle(-33f, -10f, 3f, paint)
        c.drawCircle(29f, -10f, 3f, paint)
        shade("#E5A394")
        c.drawOval(-57f, 13f, -36f, 25f, paint)
        c.drawOval(36f, 13f, 57f, 25f, paint)
        shade(if (kind == 0) "#4C3630" else "#9A607D")
        path.reset(); path.moveTo(-10f, 18f); path.quadTo(0f, 13f, 10f, 18f)
        path.quadTo(8f, 25f, 0f, 29f); path.quadTo(-8f, 25f, -10f, 18f); c.drawPath(path, paint)
        shade("#654750")
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.strokeCap = Paint.Cap.ROUND
        path.reset(); path.moveTo(0f, 29f); path.lineTo(0f, 34f)
        path.quadTo(-11f, 44f, -19f, 34f); path.moveTo(0f, 34f); path.quadTo(11f, 44f, 19f, 34f)
        c.drawPath(path, paint)
        if (kind == 1) {
            c.drawLine(-51f, 29f, -91f, 20f, paint); c.drawLine(-51f, 39f, -90f, 44f, paint)
            c.drawLine(51f, 29f, 91f, 20f, paint); c.drawLine(51f, 39f, 90f, 44f, paint)
        } else {
            shade("#E78E91")
            c.drawRoundRect(-8f, 42f, 8f, 58f, 7f, 7f, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun paw(canvas: Canvas, x: Float, y: Float, scale: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        canvas.rotate(-22f)
        pigment(130)
        canvas.drawOval(-17f, -2f, 17f, 23f, paint)
        canvas.drawOval(-29f, -21f, -15f, -3f, paint)
        canvas.drawOval(-14f, -34f, 0f, -12f, paint)
        canvas.drawOval(4f, -32f, 18f, -10f, paint)
        canvas.drawOval(20f, -18f, 34f, 0f, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
