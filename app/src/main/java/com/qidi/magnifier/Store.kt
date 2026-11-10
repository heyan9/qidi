package com.qidi.magnifier
import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

object Store {
    val DEFAULTS = mapOf(
        "type" to "搞笑\n摄影\n舞蹈\n手势舞\n绘画",
        "pos" to "可爱动作\n高亮\n简单易学\n颜色不跳\n母\n单人\n性吸引力\n爪\n不同寻常的动作\n全屏9比16\n强张力\n学校\n高饱和高亮\n全装\n萌坏\n萌蠢\n胖太\n私房\n私房互动视频\n动画",
        "neg" to "多人\n半装\n没有露爪\n完成度低\n画面主角不够简单、完整\n自以为很帅\n颜色杂乱\n动作太常见"
    )
    val OPTION_TITLES = listOf("type" to "① 内容类型", "pos" to "② 正向关键词", "neg" to "③ 反向关键词")

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
    fun setNotes(c: Context, d: String, t: String) = sp(c).edit().putString("n_$d", t.trim()).apply()

    // 选项（可自定义）
    fun options(c: Context, key: String): List<String> =
        (sp(c).getString("opt_$key", null) ?: DEFAULTS[key]!!).split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    fun setOptions(c: Context, key: String, text: String) = sp(c).edit().putString("opt_$key", text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()
    fun resetOptions(c: Context) { val e = sp(c).edit(); DEFAULTS.keys.forEach { e.remove("opt_$it") }; e.apply() }

    @Synchronized fun add(c: Context, choice: String, ok: Boolean) {
        val d = today(); val s = sp(c)
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "," + choice + "," + (if (ok) 1 else 0) + "\n"
        s.edit().putInt("t_$d", total(c, d) + 1).putInt("c_$d", correct(c, d) + (if (ok) 1 else 0))
            .putString("log_$d", log(c, d) + line).putString("last", choice).apply()
    }
    fun addOption(c: Context, key: String, text: String) {
        val l = options(c, key).toMutableList(); if (text !in l) l.add(text)
        sp(c).edit().putString("opt_$key", l.joinToString("\n")).apply()
    }
    /** 给今天最后一条记录补上 类型 / 正向 / 反向 关键词 */
    @Synchronized fun setLastTags(c: Context, type: String, pos: List<String>, neg: List<String>) {
        val d = today(); val lines = log(c, d).trimEnd('\n').split("\n").toMutableList()
        if (lines.isEmpty() || lines.last().isEmpty()) return
        val p = lines.last().split(",")
        val j = { l: List<String> -> l.joinToString("|") { it.replace(",", "，").replace("|", "/") } }
        lines[lines.size - 1] = p.take(3).joinToString(",") + "," + type.replace(",", "，") + "," + j(pos) + "," + j(neg)
        sp(c).edit().putString("log_$d", lines.joinToString("\n") + "\n").apply()
    }
    @Synchronized fun addNote(c: Context, text: String) {
        val d = today(); val t = SimpleDateFormat("HH:mm", Locale.US).format(Date())
        val last = sp(c).getString("last", "-")
        sp(c).edit().putString("n_$d", notes(c, d) + (if (notes(c, d).isEmpty()) "" else "\n\n") + "- $t [区间:$last] $text").apply()
    }
    fun dates(c: Context): List<String> = sp(c).all.keys
        .filter { it.startsWith("t_") || it.startsWith("s_") }.map { it.substring(2) }.distinct().sorted()
    fun noteDates(c: Context): List<String> = sp(c).all.keys.filter { it.startsWith("n_") }.map { it.substring(2) }
        .filter { notes(c, it).isNotBlank() }.sortedDescending()
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
    data class KW(val w: String, val n: Int, val ok: Int) { val pct get() = if (n == 0) 0 else ok * 100 / n }
    /** 返回 "type"/"pos"/"neg" 三类关键词：出现次数、其中答对次数，按次数从高到低 */
    fun keywordStats(c: Context): Map<String, List<KW>> {
        val m = mapOf("type" to HashMap<String, IntArray>(), "pos" to HashMap<String, IntArray>(), "neg" to HashMap<String, IntArray>())
        fun hit(k: String, w: String, ok: Boolean) { if (w.isBlank()) return; val a = m[k]!!.getOrPut(w) { IntArray(2) }; a[0]++; if (ok) a[1]++ }
        for (k in sp(c).all.keys.filter { it.startsWith("log_") }) {
            for (line in log(c, k.substring(4)).split("\n")) {
                val p = line.split(","); if (p.size < 4) continue
                val ok = p[2] == "1"
                hit("type", p[3], ok)
                var pos = ""; var neg = ""
                if (p.size >= 6) { pos = p[4]; neg = p[5] } else if (p.size == 5) { if (ok) pos = p[4] else neg = p[4] }
                pos.split("|").forEach { hit("pos", it, ok) }; neg.split("|").forEach { hit("neg", it, ok) }
            }
        }
        return m.mapValues { e -> e.value.entries.sortedByDescending { it.value[0] }.map { KW(it.key, it.value[0], it.value[1]) } }
    }
}
