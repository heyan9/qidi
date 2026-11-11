package com.qidi.magnifier
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private val BLUE = "#0084FF"
    private val ORANGE = "#FF8A2B"
    private val GRAY = "#5A6B7F"
    private var syncTv: TextView? = null
    private var tab = 0
    private var week = Shots.thisWeek()
    private var typeFilter: String? = null
    private fun d(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    override fun onResume() { super.onResume(); build(); autoSync() }

    /** 每次打开App：先从云端恢复本地缺的数据，再上传 */
    private fun autoSync() {
        val sp = Store.sp(this)
        if ((sp.getString("token", "") ?: "").isBlank()) return
        syncTv?.text = "☁ 同步中…"
        val x = applicationContext
        Thread {
            val (n, err) = GitHub.restore(x)
            val m = err ?: GitHub.sync(x, true)   // 恢复失败就不上传，避免覆盖云端
            val t = SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date())
            val full = "$t  " + (if (n > 0 && err == null) "已从云端恢复 $n 项数据；" else "") + m
            sp.edit().putString("last_sync", full).apply()
            runOnUiThread { if (n > 0) build(); syncTv?.text = "☁ $full" }
        }.start()
    }
    private fun bgSync() { val x = applicationContext; Thread { GitHub.sync(x) }.start() }

    // ---------- 通用控件 ----------
    private fun tv(t: String, sz: Float = 14f, col: String = "#1F2D3D", bold: Boolean = false) =
        TextView(this).apply { text = t; textSize = sz; setTextColor(Color.parseColor(col)); if (bold) paint.isFakeBoldText = true }
    private fun blob(size: Int, g: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = size / 2f
            colors = intArrayOf(Color.parseColor("#660084FF"), Color.parseColor("#000084FF"))
        }
        layoutParams = FrameLayout.LayoutParams(size, size, g)
    }
    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(d(16), d(14), d(16), d(14))
        background = GradientDrawable().apply { setColor(Color.parseColor("#99D6EBFF")); cornerRadius = d(20).toFloat(); setStroke(d(1), Color.parseColor("#400084FF")) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, d(12)) }
        if (title.isNotEmpty()) addView(tv(title, 13f, GRAY))
    }
    private fun button(t: String, bg: String = BLUE, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(d(8), d(12), d(8), d(12))
        background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = d(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8) }
        setOnClickListener { click() }
    }
    private fun chip(t: String, on: Boolean, click: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; gravity = Gravity.CENTER; setPadding(d(14), d(7), d(14), d(7))
        setTextColor(Color.parseColor(if (on) "#FFFFFF" else BLUE))
        background = GradientDrawable().apply { setColor(Color.parseColor(if (on) BLUE else "#22FFFFFF")); cornerRadius = d(16).toFloat(); setStroke(d(1), Color.parseColor("#550084FF")) }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, d(6), 0) }
        setOnClickListener { click() }
    }
    private fun slider(label: String, key: String, def: Int, card: LinearLayout) {
        card.addView(tv(label, 13f).apply { setPadding(0, d(8), 0, 0) })
        card.addView(SeekBar(this).apply {
            max = 70; progress = Store.sp(context).getInt(key, def) - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    Store.sp(this@MainActivity).edit().putInt(key, p + 30).apply()
                    if (FloatingService.running) startService(Intent(this@MainActivity, FloatingService::class.java))
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
    }
    private fun editNotes(day: String) {
        val et = EditText(this).apply { setText(Store.notes(this@MainActivity, day)); minLines = 6; gravity = Gravity.TOP; textSize = 14f }
        AlertDialog.Builder(this).setTitle("$day 笔记").setView(et)
            .setPositiveButton("保存") { _, _ -> Store.setNotes(this, day, et.text.toString()); bgSync(); build() }
            .setNegativeButton("取消", null).show()
    }

    // ---------- 框架：内容 + 底部标签栏 ----------
    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(blob(d(300), Gravity.TOP or Gravity.END))
        frame.addView(blob(d(320), Gravity.BOTTOM or Gravity.START))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(20), d(16), d(20)) }
        frame.addView(ScrollView(this).apply { addView(col) })
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(tabBar())
        setContentView(root)
        syncTv = null
        when (tab) { 0 -> pageHome(col); 1 -> pageKeywords(col); 2 -> pageHot(col); 3 -> pageNotes(col); else -> pageSettings(col) }
    }
    private fun tabBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.setBackgroundColor(Color.parseColor("#F2F8FF")); bar.setPadding(0, d(6), 0, d(6))
        listOf("🏠\n首页", "🏷️\n关键词", "🔥\n热门", "📝\n笔记", "⚙️\n设置").forEachIndexed { i, t ->
            bar.addView(TextView(this).apply {
                text = t; gravity = Gravity.CENTER; textSize = 11f; setPadding(0, d(4), 0, d(4))
                setTextColor(Color.parseColor(if (i == tab) BLUE else "#7F8FA3")); if (i == tab) paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { tab = i; build() }
            })
        }
        return bar
    }
    private fun title(col: LinearLayout, t: String) = col.addView(tv(t, 26f, BLUE, true).apply { setPadding(d(4), 0, 0, d(10)) })

    // ---------- 页面1：首页 ----------
    private fun pageHome(col: LinearLayout) {
        val sp = Store.sp(this); val today = Store.today()
        title(col, "奇的放大镜")
        syncTv = tv(if ((sp.getString("token", "") ?: "").isBlank()) "☁ 未配置GitHub同步（在「设置」页）" else "☁ " + (sp.getString("last_sync", "") ?: ""), 12f, GRAY)
            .apply { setPadding(d(4), 0, 0, d(12)) }
        col.addView(syncTv)

        val c3 = card("悬浮窗")
        c3.addView(button(if (FloatingService.running) "关闭悬浮窗" else "开启悬浮窗", if (FloatingService.running) ORANGE else BLUE) {
            if (FloatingService.running) { stopService(Intent(this, FloatingService::class.java)); build() }
            else if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else { startForegroundService(Intent(this, FloatingService::class.java)); build() }
        })
        slider("小圆标透明度", "bubble_alpha", 100, c3)
        slider("黑色遮挡透明度", "mask_alpha", 100, c3)
        col.addView(c3)

        val c1 = card("近7日平均胜率")
        val a7 = Store.avgRate7(this)
        c1.addView(tv(if (a7 == null) "--" else "%.1f%%".format(a7 * 100), 40f, BLUE, true))
        c1.addView(tv("今日：${Store.correct(this, today)}/${Store.total(this, today)}  准确率 ${"%.0f".format(Store.rate(this, today) * 100)}%", 14f))
        col.addView(c1)

        val c2 = card("今日评分（1-10）")
        val r = LinearLayout(this)
        val cur = Store.score(this, today)
        for (i in 1..10) r.addView(TextView(this).apply {
            text = "$i"; gravity = Gravity.CENTER; textSize = 14f; setPadding(0, d(10), 0, d(10))
            setTextColor(Color.parseColor(if (i == cur) "#FFFFFF" else BLUE))
            background = GradientDrawable().apply { setColor(Color.parseColor(if (i == cur) BLUE else "#22FFFFFF")); cornerRadius = d(8).toFloat(); setStroke(d(1), Color.parseColor("#550084FF")) }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(2), d(6), d(2), 0) }
            setOnClickListener { Store.setScore(this@MainActivity, today, i); bgSync(); build() }
        })
        c2.addView(r)
        val a15 = Store.avgScore15(this)
        c2.addView(tv(if (a15 == null) "近15日均分：暂无" else "近15日均分：%.1f".format(a15), 14f).apply { setPadding(0, d(10), 0, 0) })
        if (a15 != null && a15 < 5f) c2.addView(tv("⚠ 内容创意存在严重不足，需要提高胜率以及内容敏感度", 14f, ORANGE, true).apply { setPadding(0, d(6), 0, 0) })
        col.addView(c2)

        val c4 = card("准确率排行榜（GitHub 上有完整版 LEADERBOARD.md）")
        val top = Store.dates(this).filter { Store.total(this, it) > 0 }.sortedWith(compareByDescending<String> { Store.rate(this, it) }.thenByDescending { Store.total(this, it) }).take(7)
        if (top.isEmpty()) c4.addView(tv("暂无数据"))
        top.forEachIndexed { i, dt -> c4.addView(tv("${i + 1}. $dt   ${"%.1f".format(Store.rate(this, dt) * 100)}%  (${Store.correct(this, dt)}/${Store.total(this, dt)})")) }
        col.addView(c4)
    }

    // ---------- 页面2：关键词（可按内容类型筛选） ----------
    private fun kwTable(parent: LinearLayout, title: String, color: String, l: List<Store.KW>, ks: Store.KStats) {
        parent.addView(tv(title, 14f, color, true).apply { setPadding(0, d(10), 0, d(4)) })
        if (l.isEmpty()) { parent.addView(tv("暂无")); return }
        fun cell(t: String, w: Float, c: String = "#1F2D3D", b: Boolean = false) = tv(t, 13f, c, b).apply { layoutParams = LinearLayout.LayoutParams(0, -2, w) }
        parent.addView(LinearLayout(this).apply {
            addView(cell("关键词", 2.2f, GRAY)); addView(cell("次数", 1f, GRAY)); addView(cell("占比", 1f, GRAY)); addView(cell("正确率", 1.2f, GRAY))
        })
        l.take(15).forEach { k ->
            parent.addView(LinearLayout(this).apply {
                setPadding(0, d(3), 0, d(3))
                addView(cell(k.w, 2.2f)); addView(cell("${k.n}", 1f)); addView(cell("${ks.share(k)}%", 1f))
                addView(cell("${k.pct}%", 1.2f, if (k.pct >= 60) BLUE else if (k.pct < 40) ORANGE else "#1F2D3D", true))
            })
        }
    }
    private fun pageKeywords(col: LinearLayout) {
        title(col, "关键词分析")
        val all = Store.keywordStats(this)
        val types = (Store.options(this, "type") + all.lists["type"]!!.map { it.w }).distinct()
        if (typeFilter != null && typeFilter !in types) typeFilter = null
        val hs = HorizontalScrollView(this); hs.isHorizontalScrollBarEnabled = false
        val rw = LinearLayout(this); hs.addView(rw)
        rw.addView(chip("全部", typeFilter == null) { typeFilter = null; build() })
        types.forEach { t -> rw.addView(chip(t, typeFilter == t) { typeFilter = t; build() }) }
        hs.setPadding(0, 0, 0, d(12)); col.addView(hs)

        val ks = Store.keywordStats(this, typeFilter)
        val (allN, tagN) = Store.taggedCount(this)
        val c0 = card("")
        c0.addView(tv((typeFilter ?: "全部类型") + "：共 ${ks.total} 题，正确率 ${ks.pct}%", 16f, BLUE, true))
        c0.addView(tv("占比 = 含该关键词的题数 ÷ 该类型总题数\n正确率 = 含该关键词的题里判“对”的比例", 12f, GRAY).apply { setPadding(0, d(6), 0, 0) })
        c0.addView(tv("共记录 $allN 道题，其中 $tagN 道选了类型/关键词（只统计选过的）", 12f, GRAY).apply { setPadding(0, d(2), 0, 0) })
        col.addView(c0)
        val c1 = card("")
        if (typeFilter == null) kwTable(c1, "内容类型", "#1F2D3D", ks.lists["type"]!!, ks)
        kwTable(c1, "✔ 正向关键词", BLUE, ks.lists["pos"]!!, ks)
        kwTable(c1, "✘ 反向关键词", ORANGE, ks.lists["neg"]!!, ks)
        col.addView(c1)
    }

    // ---------- 页面3：一周热门（优质截屏） ----------
    private fun loadInto(iv: ImageView, f: File, sample: Int) {
        Thread {
            val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
            runOnUiThread { if (b != null) iv.setImageBitmap(b) }
        }.start()
    }
    private fun askCapture() {
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 77)
    }
    override fun onActivityResult(rq: Int, rs: Int, data: Intent?) {
        super.onActivityResult(rq, rs, data)
        if (rq == 77 && rs == RESULT_OK && data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).putExtra("code", rs).putExtra("data", data))
            Handler(Looper.getMainLooper()).postDelayed({ build() }, 800)
        }
    }
    private fun pickWeek() {
        val ws = (Shots.weeks(this) + Shots.thisWeek()).distinct().sortedDescending()
        val labels = ws.map { "${Shots.weekLabel(it)}（${Shots.inWeek(this, it).size}张）" + (if (it == Shots.thisWeek()) " 本周" else "") }.toTypedArray()
        AlertDialog.Builder(this).setTitle("跳转到某一周").setItems(labels) { _, i -> week = ws[i]; build() }.show()
    }
    private fun showShot(f: File) {
        val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val fl = FrameLayout(this); fl.setBackgroundColor(Color.BLACK)
        val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.FIT_CENTER
        fl.addView(iv, FrameLayout.LayoutParams(-1, -1)); loadInto(iv, f, 1)
        iv.setOnClickListener { dlg.dismiss() }
        val bar = LinearLayout(this)
        bar.addView(button("删除", ORANGE) {
            AlertDialog.Builder(this).setMessage("删除这张截屏？（云端也会删除）")
                .setPositiveButton("删除") { _, _ ->
                    Shots.delete(this, f.name); dlg.dismiss(); build()
                    val x = applicationContext
                    Thread { if (!GitHub.deleteShot(x, f.name)) runOnUiThread { Toast.makeText(this, "云端删除失败，下次从云端恢复时可能会回来", Toast.LENGTH_LONG).show() } }.start()
                }.setNegativeButton("取消", null).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(16), 0, d(8), d(24)) } })
        bar.addView(button("关闭", "#444444") { dlg.dismiss() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(8), 0, d(16), d(24)) } })
        fl.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        dlg.setContentView(fl); dlg.show()
    }
    private fun pageHot(col: LinearLayout) {
        title(col, "一周热门")
        val cs = card("截屏权限")
        if (CaptureService.ready) {
            cs.addView(tv("✓ 截屏已就绪。在悬浮窗菜单点「截屏」即可保存当前画面。", 14f, BLUE, true))
            cs.addView(button("关闭截屏", "#7F8FA3") { stopService(Intent(this, CaptureService::class.java)); Handler(Looper.getMainLooper()).postDelayed({ build() }, 300) })
        } else {
            cs.addView(tv("截屏需要系统授权。点下面按钮并选择「立即开始」，之后悬浮窗菜单里的「截屏」才能用。授权期间系统状态栏会显示录屏图标，属正常现象；重启手机或被系统清理后需重新授权。", 13f))
            cs.addView(button("开启截屏权限") { askCapture() })
        }
        col.addView(cs)

        val files = Shots.inWeek(this, week)
        val nav = card("")
        val nr = LinearLayout(this); nr.gravity = Gravity.CENTER_VERTICAL
        nr.addView(tv("‹ 上一周", 14f, BLUE, true).apply { setPadding(0, d(8), d(12), d(8)); setOnClickListener { week = Shots.shiftWeek(week, -1); build() } })
        nr.addView(tv(Shots.weekLabel(week) + (if (week == Shots.thisWeek()) "（本周）" else "") + " ▾", 15f, "#1F2D3D", true).apply {
            gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { pickWeek() }
        })
        nr.addView(tv(if (week < Shots.thisWeek()) "下一周 ›" else "      ", 14f, BLUE, true).apply {
            setPadding(d(12), d(8), 0, d(8)); if (week < Shots.thisWeek()) setOnClickListener { week = Shots.shiftWeek(week, 1); build() }
        })
        nav.addView(nr)
        nav.addView(tv("本周共 ${files.size} 张优质截屏（点击标题可跳转到其他周）", 12f, GRAY))
        col.addView(nav)

        if (files.isEmpty()) { col.addView(tv("这一周还没有截屏", 14f, GRAY).apply { setPadding(d(4), d(8), 0, 0) }); return }
        val cellW = (resources.displayMetrics.widthPixels - d(32) - d(12)) / 3
        files.chunked(3).forEach { r ->
            val row = LinearLayout(this)
            r.forEach { f ->
                val iv = ImageView(this); iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setBackgroundColor(Color.parseColor("#E3EEF9"))
                iv.layoutParams = LinearLayout.LayoutParams(cellW, cellW * 16 / 10).apply { setMargins(d(2), d(2), d(2), d(2)) }
                iv.setOnClickListener { showShot(f) }
                loadInto(iv, f, 3); row.addView(iv)
            }
            col.addView(row)
        }
    }

    // ---------- 页面4：笔记 ----------
    private fun pageNotes(col: LinearLayout) {
        title(col, "错题记事本")
        val today = Store.today()
        col.addView(button("＋ 写今天的笔记") { editNotes(today) }.apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = d(12) })
        val nd = Store.noteDates(this).take(30)
        if (nd.isEmpty()) col.addView(tv("还没有笔记。可在悬浮窗记事本里记，也可以点上面按钮直接写。", 14f, GRAY))
        nd.forEach { day ->
            val c = card("")
            val preview = Store.notes(this, day).lines().filter { it.isNotBlank() }.take(4).joinToString("\n")
            c.addView(tv(day, 13f, BLUE, true)); c.addView(tv(preview, 14f).apply { setPadding(0, d(4), 0, 0) })
            c.addView(tv("点击查看/修改", 11f, GRAY).apply { setPadding(0, d(4), 0, 0) })
            c.setOnClickListener { editNotes(day) }
            col.addView(c)
        }
    }

    // ---------- 页面5：设置 ----------
    private fun pageSettings(col: LinearLayout) {
        title(col, "设置")
        val sp = Store.sp(this)
        val c5 = card("GitHub 同步（打开App时自动同步，重装后会自动恢复）")
        fun field(h: String, k: String, pw: Boolean = false) = EditText(this).apply {
            hint = h; setText(sp.getString(k, "")); textSize = 14f; setSingleLine()
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fo = field("GitHub 用户名", "owner"); val fr = field("仓库名（需已存在）", "repo"); val ft = field("Token", "token", true)
        c5.addView(fo); c5.addView(fr); c5.addView(ft)
        c5.addView(button("保存并立即同步（会先从云端恢复数据）") {
            sp.edit().putString("owner", fo.text.toString().trim()).putString("repo", fr.text.toString().trim()).putString("token", ft.text.toString().trim()).apply()
            Toast.makeText(this, "同步中，结果见「首页」顶部", Toast.LENGTH_SHORT).show()
            tab = 0; build(); autoSync()
        })
        col.addView(c5)

        val c7 = card("选项管理（一行一个，可增删改）")
        val ets = LinkedHashMap<String, EditText>()
        Store.OPTION_TITLES.forEach { (k, ttl) ->
            c7.addView(tv(ttl, 14f, when (k) { "pos" -> BLUE; "neg" -> ORANGE; else -> "#1F2D3D" }, true).apply { setPadding(0, d(10), 0, 0) })
            val et = EditText(this).apply {
                setText(Store.options(this@MainActivity, k).joinToString("\n")); textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = 3
            }
            ets[k] = et; c7.addView(et)
        }
        c7.addView(button("保存选项") {
            ets.forEach { (k, e) -> Store.setOptions(this, k, e.text.toString()) }
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show(); bgSync()
        })
        c7.addView(button("恢复默认选项", "#7F8FA3") { Store.resetOptions(this); bgSync(); build() })
        col.addView(c7)
        col.addView(tv("奇的放大镜 v2.0", 12f, GRAY).apply { setPadding(d(4), d(4), 0, 0) })
    }
}
