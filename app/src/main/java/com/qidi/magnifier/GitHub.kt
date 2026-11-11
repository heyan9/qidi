package com.qidi.magnifier
import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GitHub {
    private fun call(method: String, url: String, token: String, body: String?): Pair<Int, String> {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.requestMethod = method; cn.connectTimeout = 15000; cn.readTimeout = 40000
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

            val ks = Store.keywordStats(c)
            val kw = StringBuilder("# 关键词统计\n\n（占比 = 含该词的题数 / 总题数；正确率 = 含该词的题里判“对”的比例）\n")
            fun table(st: Store.KStats, k: String, title: String) {
                kw.append("\n### $title\n\n| 关键词 | 次数 | 占比 | 正确率 |\n|---|---|---|---|\n")
                st.lists[k]!!.forEach { kw.append("| ${it.w} | ${it.n} | ${st.share(it)}% | ${it.pct}% |\n") }
            }
            kw.append("\n## 全部（${ks.total}题，正确率${ks.pct}%）\n")
            table(ks, "type", "内容类型"); table(ks, "pos", "正向关键词"); table(ks, "neg", "反向关键词")
            for (t in ks.lists["type"]!!.map { it.w }) {
                val kt = Store.keywordStats(c, t)
                kw.append("\n## 内容类型：$t（${kt.total}题，正确率${kt.pct}%）\n")
                table(kt, "pos", "正向关键词"); table(kt, "neg", "反向关键词")
            }
            putAuto(tok, o, r, "KEYWORDS.md", kw.toString())

            val opt = JSONObject(); Store.DEFAULTS.keys.forEach { opt.put(it, org.json.JSONArray(Store.options(c, it))) }
            putAuto(tok, o, r, "config/options.json", opt.toString(2))

            val days = if (all) (Store.dates(c) + Store.noteDates(c)).distinct() else listOf(Store.today())
            for (d in days) {
                val lg = Store.log(c, d); val nt = Store.notes(c, d)
                if (lg.isNotEmpty()) putAuto(tok, o, r, "logs/$d.csv", "time,choice,correct,type,positive,negative\n$lg")
                if (nt.isNotBlank()) putAuto(tok, o, r, "notes/$d.md", "# $d 错题笔记\n\n$nt\n")
            }
            val up = Shots.uploaded(c)
            for (f in Shots.files(c)) {
                if (f.name in up) continue
                putBin(tok, o, r, "screenshots/${Shots.dateOf(f.name)}/${f.name}", f.readBytes())
                Shots.markUploaded(c, f.name)
            }
            "同步成功 ✓"
        } catch (e: Exception) { "同步失败：" + e.message }
    }

    private fun list(t: String, o: String, r: String, path: String): List<String> {
        val (c, s) = call("GET", "https://api.github.com/repos/$o/$r/contents/$path", t, null)
        if (c == 404) return emptyList()
        if (c != 200) throw Exception("读取目录$path 失败($c)")
        val a = JSONArray(s)
        return (0 until a.length()).map { a.getJSONObject(it).getString("name") }
    }

    /** 从云端把本地缺少的数据拉回来（重装App后恢复）。返回 (恢复的天数, 错误信息或null)。只补空缺，不覆盖本地已有数据 */
    @Synchronized fun restore(c: Context): Pair<Int, String?> {
        val sp = Store.sp(c)
        val tok = sp.getString("token", "") ?: ""; val o = sp.getString("owner", "") ?: ""; val r = sp.getString("repo", "") ?: ""
        if (tok.isBlank() || o.isBlank() || r.isBlank()) return 0 to null
        return try {
            var n = 0
            val (st, _) = get(tok, o, r, "data/stats.json")
            if (st != null) {
                val j = JSONObject(st)
                for (d in j.keys()) {
                    val e = j.getJSONObject(d)
                    if (Store.total(c, d) == 0 && e.optInt("total") > 0) { Store.setCounts(c, d, e.optInt("total"), e.optInt("correct")); n++ }
                    if (Store.score(c, d) == 0 && e.optInt("score") > 0) Store.setScore(c, d, e.optInt("score"))
                }
            }
            for (name in list(tok, o, r, "logs")) {
                if (!name.endsWith(".csv")) continue
                val d = name.removeSuffix(".csv")
                if (Store.log(c, d).isNotEmpty()) continue
                val (txt, _) = get(tok, o, r, "logs/$name"); if (txt == null) continue
                val body = txt.lines().drop(1).filter { it.isNotBlank() }; if (body.isEmpty()) continue
                Store.setLog(c, d, body.joinToString("\n") + "\n")
                Store.setCounts(c, d, body.size, body.count { it.split(",").getOrNull(2) == "1" })
            }
            for (name in list(tok, o, r, "notes")) {
                if (!name.endsWith(".md")) continue
                val d = name.removeSuffix(".md")
                if (Store.notes(c, d).isNotBlank()) continue
                val (txt, _) = get(tok, o, r, "notes/$name"); if (txt == null) continue
                val body = txt.substringAfter("错题笔记\n\n", "").trim()
                if (body.isNotEmpty()) Store.setNotes(c, d, body)
            }
            val (op, _) = get(tok, o, r, "config/options.json")
            if (op != null) {
                val j = JSONObject(op)
                for (k in Store.DEFAULTS.keys) if (sp.getString("opt_$k", null) == null && j.has(k)) {
                    val a = j.getJSONArray(k)
                    sp.edit().putString("opt_$k", (0 until a.length()).joinToString("\n") { a.getString(it) }).apply()
                }
            }
            val (rc, rs) = call("GET", "https://api.github.com/repos/$o/$r", tok, null)
            if (rc == 200) {
                val br = JSONObject(rs).getString("default_branch")
                val (tc, ts) = call("GET", "https://api.github.com/repos/$o/$r/git/trees/$br?recursive=1", tok, null)
                if (tc == 200) {
                    val arr = JSONObject(ts).getJSONArray("tree")
                    for (i in 0 until arr.length()) {
                        val path = arr.getJSONObject(i).getString("path")
                        if (!path.startsWith("screenshots/") || !path.endsWith(".jpg")) continue
                        val name = path.substringAfterLast('/')
                        val lf = java.io.File(Shots.dir(c), name)
                        if (!lf.exists()) { val b = getBin(tok, o, r, path); if (b != null) { lf.writeBytes(b); n++ } }
                        Shots.markUploaded(c, name)
                    }
                }
            }
            n to null
        } catch (e: Exception) { 0 to "恢复失败：" + e.message }
    }

    private fun getBin(t: String, o: String, r: String, path: String): ByteArray? {
        val (c, s) = call("GET", "https://api.github.com/repos/$o/$r/contents/$path", t, null)
        if (c == 404) return null
        if (c != 200) throw Exception("下载$path 失败($c)")
        val ct = JSONObject(s).optString("content"); if (ct.isEmpty()) return null
        return Base64.decode(ct, Base64.DEFAULT)
    }
    private fun putBin(t: String, o: String, r: String, path: String, bytes: ByteArray) {
        val j = JSONObject().put("message", "screenshot").put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val (c, _) = call("PUT", "https://api.github.com/repos/$o/$r/contents/$path", t, j.toString())
        if (c !in 200..201 && c != 422) throw Exception("上传截图失败($c)")   // 422 = 云端已存在
    }
    /** 删除云端截图，成功返回 true */
    fun deleteShot(c: Context, name: String): Boolean {
        val sp = Store.sp(c)
        val tok = sp.getString("token", "") ?: ""; val o = sp.getString("owner", "") ?: ""; val r = sp.getString("repo", "") ?: ""
        if (tok.isBlank() || o.isBlank() || r.isBlank()) return false
        return try {
            val url = "https://api.github.com/repos/$o/$r/contents/screenshots/${Shots.dateOf(name)}/$name"
            val (code, s) = call("GET", url, tok, null)
            if (code == 404) return true
            if (code != 200) return false
            val (c2, _) = call("DELETE", url, tok, JSONObject().put("message", "delete screenshot").put("sha", JSONObject(s).getString("sha")).toString())
            c2 in 200..201
        } catch (e: Exception) { false }
    }
}
