package com.qidi.magnifier
import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.*

/** 优质内容截屏：本地存储 + 按周（周一~周日）分组 */
object Shots {
    fun dir(c: Context) = File(c.filesDir, "shots").apply { mkdirs() }
    fun files(c: Context): List<File> = dir(c).listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.name } ?: emptyList()
    fun dateOf(name: String) = name.substring(0, 10)
    fun weekStart(date: String): String = LocalDate.parse(date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
    fun thisWeek() = weekStart(Store.today())
    fun weeks(c: Context): List<String> = files(c).map { weekStart(dateOf(it.name)) }.distinct().sortedDescending()
    fun inWeek(c: Context, w: String) = files(c).filter { weekStart(dateOf(it.name)) == w }
    fun weekLabel(w: String): String { val s = LocalDate.parse(w); val e = s.plusDays(6); return "${s.monthValue}/${s.dayOfMonth} ~ ${e.monthValue}/${e.dayOfMonth}" }
    fun shiftWeek(w: String, n: Int) = LocalDate.parse(w).plusWeeks(n.toLong()).toString()

    fun save(c: Context, bmp: Bitmap): File {
        val name = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date()) + ".jpg"
        val f = File(dir(c), name)
        val w = minOf(900, bmp.width)
        val out = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
        f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return f
    }
    fun uploaded(c: Context): Set<String> = HashSet(Store.sp(c).getStringSet("shots_up", emptySet()) ?: emptySet())
    fun markUploaded(c: Context, name: String) { val s = uploaded(c) + name; Store.sp(c).edit().putStringSet("shots_up", HashSet(s)).apply() }
    fun delete(c: Context, name: String) { File(dir(c), name).delete() }
}
