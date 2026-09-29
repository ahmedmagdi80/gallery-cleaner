package com.example.gallerycleaner

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Size

data class Photo(val id: Long, val uri: Uri, val w: Int, val h: Int, val size: Long, val hash: Long, val lap: Double)
class Result(val groups: List<List<Photo>>, val blurry: List<Photo>, val low: List<Photo>)

object Analyzer {
    private val COLLECTION = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** يقرأ كل الصور من المعرض ويحسب لكل صورة بصمة + درجة وضوح. لازم يشتغل في خيط خلفي. */
    fun scan(ctx: Context, onProgress: (Int, Int) -> Unit): List<Photo> {
        val out = mutableListOf<Photo>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE
        )
        ctx.contentResolver.query(COLLECTION, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            val total = c.count
            var i = 0
            while (c.moveToNext()) {
                onProgress(++i, total)
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(COLLECTION, id)
                val bmp = (try { ctx.contentResolver.loadThumbnail(uri, Size(256, 256), null) } catch (e: Exception) { null }) ?: continue
                val b = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: continue
                out += Photo(id, uri, c.getInt(1), c.getInt(2), c.getLong(3), dHash(b), laplacianVar(b))
            }
        }
        return out
    }

    private fun gray(px: Int) = 0.3f * Color.red(px) + 0.59f * Color.green(px) + 0.11f * Color.blue(px)

    // بصمة الصورة (dHash): بتفضل شبه ثابتة حتى لو الحجم أو الضغط اتغيّر
    private fun dHash(b: Bitmap): Long {
        val s = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        for (r in 0 until 8) for (k in 0 until 8)
            if (gray(s.getPixel(k, r)) > gray(s.getPixel(k + 1, r))) h = h or (1L shl (r * 8 + k))
        return h
    }

    // تباين اللابلاسيان: رقم صغير = الصورة مهزوزة أو ملامحها ضايعة
    private fun laplacianVar(b: Bitmap): Double {
        val w = b.width; val h = b.height
        if (w < 3 || h < 3) return 0.0
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h) { gray(px[it]) }
        var sum = 0.0; var sq = 0.0; var n = 0
        for (r in 1 until h - 1) for (k in 1 until w - 1) {
            val p = r * w + k
            val l = (y[p - 1] + y[p + 1] + y[p - w] + y[p + w] - 4 * y[p]).toDouble()
            sum += l; sq += l * l; n++
        }
        val m = sum / n
        return sq / n - m * m
    }

    fun classify(p: List<Photo>, blurT: Double, lowMP: Double = 0.5): Result {
        val used = BooleanArray(p.size)
        val groups = mutableListOf<List<Photo>>()
        for (i in p.indices) {
            if (used[i]) continue
            val g = mutableListOf(p[i])
            for (j in i + 1 until p.size)
                if (!used[j] && java.lang.Long.bitCount(p[i].hash xor p[j].hash) <= 6) { g += p[j]; used[j] = true }
            if (g.size > 1) {
                used[i] = true
                // الأول في المجموعة = الأفضل جودة (بيتحفظ)
                groups += g.sortedByDescending { it.w.toLong() * it.h * it.size }
            }
        }
        return Result(groups, p.filter { it.lap < blurT }, p.filter { it.w.toLong() * it.h < lowMP * 1_000_000 })
    }
}
