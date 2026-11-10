package com.qidi.magnifier
import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHub {
    private fun call(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.requestMethod = method; cn.connectTimeout = 15000; cn.readTimeout = 20000
        cn.setRequestProperty("Authorization", "Bearer $token")
        cn.setRequestProperty("Accept", "application/vnd.github+json")
        if (body != null) {
            cn.doOutput = true; cn.setRequestProperty("Content-Type", "application/json")
            cn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = cn.responseCode
        val s = (if (code < 400) cn.inputStream else cn.errorStream)?.bufferedReader()?.readText() ?: ""
        return code to s
    }
    private fun get(t: String, o: String, r: String, path: String): Pair<String?, String?> {
        val (c, s) = call("GET", "https://api.github.com/repos/$o/$r/contents/$path", t, null)
        if (c == 404) return null to null
        if (c != 200) throw Exception("读取$path 失败($c)")
        val j = JSONObject(s)
        return String(Base64.decode(j.getString("content"), Base64.DEFAULT)) to j.getString("sha")
    }
    private fun put(t: String, o: String, r: String, path: String, content: String, sha: String?) {
        val j = JSONObject().put("message", "sync $path")
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
        if (sha != null) j.put("sha", sha)
        val (c, _) = call("PUT", "https://api.github.com/repos/$o/$r/contents/$path", t, j.toString())
        if (c !in 200..201) throw Exception("上传$path 失败($c)")
    }
    /** 内容没变就不上传 */
    private fun putAuto(t: String, o: String, r: String, path: String, content: String) {
        val (old, sha) = get(t, o, r, path)
        if (old == content) return
        put(t, o, r, path, content, sha)
    }

    @Synchronized fun sync(c: Context, all: Boolean = false): String {
        val sp = Store.sp(c)
        val tok = sp.getString("token", "") ?: ""; val o = sp.getString("owner", "") ?: ""; val r = sp.getString("repo", "") ?: ""
        if (tok.isBlank() || o.isBlank() || r.isBlank()) return "未配置GitHub"
        return try {
            val (old, sha) = get(tok, o, r, "data/stats.json")
            val st = if (old != null) JSONObject(old) else JSONObject()
            for (d in Store.dates(c)) {
                st.put(d, JSONObject().put("total", Store.total(c, d)).put("correct", Store.correct(c, d))
                    .put("rate", Math.round(Store.rate(c, d) * 1000) / 1000.0).put("score", Store.score(c, d)))
            }
            val stText = st.toString(2)
            if (stText != old) put(tok, o, r, "data/stats.json", stText, sha)

            val rows = st.keys().asSequence().map { it to st.getJSONObject(it) }
                .filter { it.second.optInt("total") > 0 }
                .sortedWith(compareByDescending<Pair<String, JSONObject>> { it.second.optDouble("rate") }
                    .thenByDescending { it.second.optInt("total") }).toList()
            val md = StringBuilder("# 奇的放大镜 · 准确率排行榜\n\n| 名次 | 日期 | 准确率 | 对/已做 | 当日评分 |\n|---|---|---|---|---|\n")
            rows.forEachIndexed { i, (d, j) ->
                val sc = j.optInt("score")
                md.append("| ${i + 1} | $d | ${"%.1f".format(j.optDouble("rate") * 100)}% | ${j.optInt("correct")}/${j.optInt("total")} | ${if (sc == 0) "-" else sc} |\n")
            }
            putAuto(tok, o, r, "LEADERBOARD.md", md.toString())

            val (ok, bad) = Store.keywordStats(c)
            val kw = StringBuilder("# 关键词统计\n\n## ✔ 高频正确关键词\n\n| 关键词 | 次数 |\n|---|---|\n")
            ok.forEach { kw.append("| ${it.first} | ${it.second} |\n") }
            kw.append("\n## ✘ 高频错误关键词\n\n| 关键词 | 次数 |\n|---|---|\n")
            bad.forEach { kw.append("| ${it.first} | ${it.second} |\n") }
            putAuto(tok, o, r, "KEYWORDS.md", kw.toString())

            val opt = JSONObject(); Store.DEFAULTS.keys.forEach { opt.put(it, org.json.JSONArray(Store.options(c, it))) }
            putAuto(tok, o, r, "config/options.json", opt.toString(2))

            val days = if (all) (Store.dates(c) + Store.noteDates(c)).distinct() else listOf(Store.today())
            for (d in days) {
                val lg = Store.log(c, d); val nt = Store.notes(c, d)
                if (lg.isNotEmpty()) putAuto(tok, o, r, "logs/$d.csv", "time,choice,correct,type,tags\n$lg")
                if (nt.isNotBlank()) putAuto(tok, o, r, "notes/$d.md", "# $d 错题笔记\n\n$nt\n")
            }
            "同步成功 ✓"
        } catch (e: Exception) { "同步失败：" + e.message }
    }
}
