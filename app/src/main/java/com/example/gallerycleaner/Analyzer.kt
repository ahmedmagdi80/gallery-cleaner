package com.example.gallerycleaner

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

data class Folder(val id: Long, val name: String, val count: Int, val cover: Uri)
class Photo(
    val id: Long, val uri: Uri, val w: Int, val h: Int, val size: Long,
    val hash: LongArray,      // بصمة 256 بت
    val color: IntArray,      // ملخص ألوان 4x4
    val sharp: Double         // درجة الوضوح (أعلى = أوضح)
)
class Result(val groups: List<List<Photo>>, val blurry: List<Photo>, val low: List<Photo>)
class Params(val blur: Double, val dupBits: Int, val lowMP: Double)

object Analyzer {
    private val COLLECTION = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    /** الفولدرات (الألبومات) اللي فيها صور مع عدد الصور وصورة الغلاف */
    fun folders(ctx: Context): List<Folder> {
        val map = LinkedHashMap<Long, Folder>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        ctx.contentResolver.query(COLLECTION, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext()) {
                val bid = c.getLong(1)
                val old = map[bid]
                map[bid] = if (old == null)
                    Folder(bid, c.getString(2) ?: "بدون اسم", 1, ContentUris.withAppendedId(COLLECTION, c.getLong(0)))
                else old.copy(count = old.count + 1)
            }
        }
        return map.values.sortedByDescending { it.count }
    }

    /** يفحص صور فولدر واحد بس */
    suspend fun scan(ctx: Context, bucketId: Long, onProgress: (Int, Int) -> Unit): List<Photo> {
        val out = mutableListOf<Photo>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT, MediaStore.Images.Media.SIZE
        )
        ctx.contentResolver.query(
            COLLECTION, proj, "${MediaStore.Images.Media.BUCKET_ID} = ?", arrayOf(bucketId.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val total = c.count
            var i = 0
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive()
                onProgress(++i, total)
                val id = c.getLong(0)
                val uri = ContentUris.withAppendedId(COLLECTION, id)
                val bmp = (try { ctx.contentResolver.loadThumbnail(uri, Size(512, 512), null) } catch (e: Exception) { null }) ?: continue
                val b = bmp.copy(Bitmap.Config.ARGB_8888, false) ?: continue
                out += Photo(id, uri, c.getInt(1), c.getInt(2), c.getLong(3), hash256(b), colorSig(b), sharpness(b))
            }
        }
        return out
    }

    private fun gray(px: Int) = 0.3f * Color.red(px) + 0.59f * Color.green(px) + 0.11f * Color.blue(px)

    // بصمة 16x16 = 256 بت (أدق بكتير من 64 بت وبتقلل التطابق الغلط)
    private fun hash256(b: Bitmap): LongArray {
        val s = Bitmap.createScaledBitmap(b, 17, 16, true)
        val h = LongArray(4)
        for (r in 0 until 16) for (k in 0 until 16) {
            if (gray(s.getPixel(k, r)) > gray(s.getPixel(k + 1, r))) {
                val bit = r * 16 + k
                h[bit / 64] = h[bit / 64] or (1L shl (bit % 64))
            }
        }
        return h
    }

    // متوسط ألوان 4x4: بيمنع اعتبار صورتين بنفس الشكل بس بألوان مختلفة مكررات
    private fun colorSig(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 4, 4, true)
        return IntArray(48) {
            val p = s.getPixel((it / 3) % 4, (it / 3) / 4)
            when (it % 3) { 0 -> Color.red(p); 1 -> Color.green(p); else -> Color.blue(p) }
        }
    }

    // الوضوح: نقسم الصورة 4x4 مربعات ونحسب تباين اللابلاسيان لكل مربع،
    // وناخد متوسط أوضح 4 مربعات. كده الصورة اللي فيها وجه واضح وخلفية مطموسة (بورتريه) ما تتحسبش مهزوزة.
    private fun sharpness(b: Bitmap): Double {
        val w = b.width; val h = b.height
        if (w < 24 || h < 24) return 0.0
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        val y = FloatArray(w * h) { gray(px[it]) }
        val g = 4
        val scores = ArrayList<Double>()
        for (ty in 0 until g) for (tx in 0 until g) {
            val x0 = maxOf(1, tx * w / g); val x1 = minOf(w - 1, (tx + 1) * w / g)
            val y0 = maxOf(1, ty * h / g); val y1 = minOf(h - 1, (ty + 1) * h / g)
            var s = 0.0; var sq = 0.0; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) {
                val p = yy * w + xx
                val l = (y[p - 1] + y[p + 1] + y[p - w] + y[p + w] - 4 * y[p]).toDouble()
                s += l; sq += l * l; n++
            }
            if (n > 0) { val m = s / n; scores += sq / n - m * m }
        }
        scores.sortDescending()
        return scores.take(4).average()
    }

    private fun similar(a: Photo, b: Photo, maxBits: Int): Boolean {
        if (a.h > 0 && b.h > 0) {
            val ra = a.w.toDouble() / a.h; val rb = b.w.toDouble() / b.h
            if (abs(ra - rb) / ra > 0.05) return false
        }
        var bits = 0
        for (k in 0 until 4) bits += java.lang.Long.bitCount(a.hash[k] xor b.hash[k])
        if (bits > maxBits) return false
        var d = 0
        for (k in 0 until 48) d += abs(a.color[k] - b.color[k])
        return d / 48 <= 25
    }

    fun classify(p: List<Photo>, s: Params): Result {
        val used = BooleanArray(p.size)
        val groups = mutableListOf<List<Photo>>()
        for (i in p.indices) {
            if (used[i]) continue
            val g = mutableListOf(p[i])
            for (j in i + 1 until p.size)
                if (!used[j] && similar(p[i], p[j], s.dupBits)) { g += p[j]; used[j] = true }
            if (g.size > 1) {
                used[i] = true
                groups += g.sortedByDescending { it.w.toLong() * it.h * it.size } // الأول = الأفضل
            }
        }
        return Result(
            groups,
            p.filter { it.sharp < s.blur }.sortedBy { it.sharp },
            p.filter { it.w.toLong() * it.h < s.lowMP * 1_000_000 }.sortedBy { it.w.toLong() * it.h }
        )
    }
}
