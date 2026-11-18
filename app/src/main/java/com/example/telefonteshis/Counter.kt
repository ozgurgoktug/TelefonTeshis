package com.example.telefonteshis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Düz zemin üzerindeki benzer nesneleri sayar:
 * gri ton -> bulanıklaştır -> Otsu eşikleme -> açınım -> bağlı bileşenler.
 * Birbirine değen nesneler, ortanca nesne alanına göre tahmini bölünür.
 */
object Counter {
    class Result(val total: Int, val blobs: Int, val image: Bitmap)

    private class Blob(val area: Int, val l: Int, val t: Int, val r: Int, val b: Int, var est: Int = 1)

    fun analyze(src: Bitmap, maxSide: Int, shift: Int, minAreaPct: Float, invert: Boolean): Result {
        val scale = min(1f, maxSide.toFloat() / max(src.width, src.height))
        val bmp = if (scale < 1f)
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            ) else src
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(n) {
            val c = px[it]
            (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8
        }

        // 3x3 ortalama bulanıklaştırma
        val g2 = IntArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            var c = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    s += gray[yy * w + xx]
                    c++
                }
            }
            g2[y * w + x] = s / c
        }

        // Otsu eşiği
        val hist = IntArray(256)
        for (v in g2) hist[v]++
        var sumAll = 0L
        for (i in 0..255) sumAll += i.toLong() * hist[i]
        var wB = 0
        var sumB = 0L
        var best = -1.0
        var thr = 128
        for (t in 0..255) {
            wB += hist[t]
            if (wB == 0) continue
            val wF = n - wB
            if (wF == 0) break
            sumB += t.toLong() * hist[t]
            val mB = sumB.toDouble() / wB
            val mF = (sumAll - sumB).toDouble() / wF
            val v = wB.toDouble() * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = t }
        }
        val th = (thr + shift).coerceIn(1, 254)

        // Kenarlara bakarak zemin açık mı koyu mu bul
        var bright = 0
        var bn = 0
        fun chk(i: Int) { if (g2[i] > th) bright++; bn++ }
        for (x in 0 until w) { chk(x); chk((h - 1) * w + x) }
        for (y in 0 until h) { chk(y * w); chk(y * w + w - 1) }
        val bgBright = bright * 2 > bn
        val objDark = bgBright != invert

        var mask = BooleanArray(n) { if (objDark) g2[it] <= th else g2[it] > th }
        mask = dilate(erode(mask, w, h), w, h)

        // Bağlı bileşenler (8 komşuluk)
        val vis = BooleanArray(n)
        val stack = IntArray(n)
        val blobs = ArrayList<Blob>()
        val minArea = max(15, (n * minAreaPct / 100f).toInt())
        val maxArea = (n * 0.6f).toInt()
        for (s in 0 until n) {
            if (!mask[s] || vis[s]) continue
            var sp = 0
            stack[sp++] = s
            vis[s] = true
            var area = 0
            var l = w; var t = h; var r = 0; var b = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w
                val y = i / w
                area++
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val xx = x + dx
                    val yy = y + dy
                    if (xx < 0 || yy < 0 || xx >= w || yy >= h) continue
                    val j = yy * w + xx
                    if (mask[j] && !vis[j]) { vis[j] = true; stack[sp++] = j }
                }
            }
            if (area in minArea..maxArea) blobs += Blob(area, l, t, r, b)
        }

        // Birleşik nesneleri ortanca alana göre tahmin et
        if (blobs.isNotEmpty()) {
            val med = blobs.map { it.area }.sorted()[blobs.size / 2]
            for (bl in blobs) {
                if (bl.area > 1.6 * med) bl.est = (bl.area.toFloat() / med).roundToInt().coerceIn(1, 30)
            }
        }

        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val stroke = max(2f, w / 300f)
        val box = Paint().apply { style = Paint.Style.STROKE; strokeWidth = stroke }
        val txt = Paint().apply {
            color = Color.YELLOW
            textSize = max(14f, w / 28f)
            isAntiAlias = true
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        for (bl in blobs) {
            box.color = if (bl.est == 1) Color.rgb(0, 230, 118) else Color.rgb(255, 152, 0)
            canvas.drawRect(bl.l.toFloat(), bl.t.toFloat(), bl.r.toFloat(), bl.b.toFloat(), box)
            if (bl.est > 1) canvas.drawText("x${bl.est}", bl.l.toFloat(), bl.t.toFloat() - 2f, txt)
        }
        return Result(blobs.sumOf { it.est }, blobs.size, out)
    }

    private fun erode(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            o[i] = m[i] && m[i - 1] && m[i + 1] && m[i - w] && m[i + w] &&
                m[i - w - 1] && m[i - w + 1] && m[i + w - 1] && m[i + w + 1]
        }
        return o
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val o = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            o[i] = m[i] || m[i - 1] || m[i + 1] || m[i - w] || m[i + w] ||
                m[i - w - 1] || m[i - w + 1] || m[i + w - 1] || m[i + w + 1]
        }
        return o
    }
}
