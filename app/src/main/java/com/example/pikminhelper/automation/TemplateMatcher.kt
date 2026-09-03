package com.example.pikminhelper.automation

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TemplateMatcher {
    data class Match(val center: PointF, val score: Float)

    fun match(screen: Bitmap, template: Bitmap, roi: Rect, step: Int = 4): Match? {
        val safe = Rect(max(0, roi.left), max(0, roi.top), min(screen.width, roi.right), min(screen.height, roi.bottom))
        if (template.width <= 0 || template.height <= 0) return null
        if (safe.width() < template.width || safe.height() < template.height) return null

        var bestScore = Float.NEGATIVE_INFINITY
        var bestX = 0
        var bestY = 0
        val sampleStride = 4

        var y = safe.top
        while (y <= safe.bottom - template.height) {
            var x = safe.left
            while (x <= safe.right - template.width) {
                var diff = 0L
                var samples = 0
                var ty = 0
                while (ty < template.height) {
                    var tx = 0
                    while (tx < template.width) {
                        val a = screen.getPixel(x + tx, y + ty)
                        val b = template.getPixel(tx, ty)
                        diff += abs(((a shr 16) and 0xff) - ((b shr 16) and 0xff))
                        diff += abs(((a shr 8) and 0xff) - ((b shr 8) and 0xff))
                        diff += abs((a and 0xff) - (b and 0xff))
                        samples += 3
                        tx += sampleStride
                    }
                    ty += sampleStride
                }
                val score = 1f - (diff / (255f * samples))
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += step
            }
            y += step
        }
        return Match(PointF(bestX + template.width / 2f, bestY + template.height / 2f), bestScore)
    }
}
