package com.qidi.magnifier
import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Store {
    fun sp(c: Context) = c.getSharedPreferences("qi", Context.MODE_PRIVATE)
    private fun fmt() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    fun today(): String = fmt().format(Date())
    fun total(c: Context, d: String) = sp(c).getInt("t_$d", 0)
    fun correct(c: Context, d: String) = sp(c).getInt("c_$d", 0)
    fun rate(c: Context, d: String): Float { val t = total(c, d); return if (t == 0) 0f else correct(c, d).toFloat() / t }
    fun score(c: Context, d: String) = sp(c).getInt("s_$d", 0)
    fun setScore(c: Context, d: String, v: Int) = sp(c).edit().putInt("s_$d", v).apply()
    fun log(c: Context, d: String) = sp(c).getString("log_$d", "") ?: ""
    fun notes(c: Context, d: String) = sp(c).getString("n_$d", "") ?: ""

    @Synchronized fun add(c: Context, choice: String, ok: Boolean) {
        val d = today(); val s = sp(c)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "," + choice + "," + (if (ok) 1 else 0) + "\n"
        s.edit().putInt("t_$d", total(c, d) + 1).putInt("c_$d", correct(c, d) + (if (ok) 1 else 0))
            .putString("log_$d", log(c, d) + line).putString("last", choice).apply()
    }
    @Synchronized fun addNote(c: Context, text: String) {
        val d = today(); val t = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val last = sp(c).getString("last", "-")
        sp(c).edit().putString("n_$d", notes(c, d) + "- $t [区间:$last] $text\n\n").apply()
    }
    fun dates(c: Context): List<String> = sp(c).all.keys
        .filter { it.startsWith("t_") || it.startsWith("s_") }.map { it.substring(2) }.distinct().sorted()
    fun lastDays(n: Int): List<String> {
        val cal = Calendar.getInstance(); val f = fmt()
        return (0 until n).map { val s = f.format(cal.time); cal.add(Calendar.DAY_OF_YEAR, -1); s }
    }
    fun avgRate7(c: Context): Float? {
        val l = lastDays(7).filter { total(c, it) > 0 }
        return if (l.isEmpty()) null else l.map { rate(c, it) }.average().toFloat()
    }
    fun avgScore15(c: Context): Float? {
        val l = lastDays(15).filter { score(c, it) > 0 }
        return if (l.isEmpty()) null else l.map { score(c, it) }.average().toFloat()
    }
}
