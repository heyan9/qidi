package com.qidi.magnifier
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val BLUE = "#0084FF"
    private fun d(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
    override fun onResume() { super.onResume(); build() }

    private fun tv(t: String, sz: Float = 14f, col: String = "#1F2D3D", bold: Boolean = false) =
        TextView(this).apply { text = t; textSize = sz; setTextColor(Color.parseColor(col)); if (bold) paint.isFakeBoldText = true }
    private fun blob(size: Int, g: Int, mx: Int, my: Int): View = View(this).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = size / 2f
            colors = intArrayOf(Color.parseColor("#660084FF"), Color.parseColor("#000084FF"))
        }
        layoutParams = FrameLayout.LayoutParams(size, size, g).apply { setMargins(mx, my, mx, my) }
    }
    private fun card(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(d(16), d(14), d(16), d(14))
        background = GradientDrawable().apply { setColor(Color.parseColor("#99D6EBFF")); cornerRadius = d(20).toFloat(); setStroke(d(1), Color.parseColor("#400084FF")) }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, d(12)) }
        addView(tv(title, 13f, "#5A6B7F"))
    }
    private fun button(t: String, bg: String = BLUE, click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER; setPadding(d(8), d(12), d(8), d(12))
        background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = d(12).toFloat() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = d(8) }
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

    private fun build() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        frame.addView(blob(d(300), Gravity.TOP or Gravity.END, 0, 0))
        frame.addView(blob(d(320), Gravity.BOTTOM or Gravity.START, 0, 0))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(d(16), d(20), d(16), d(30)) }
        frame.addView(ScrollView(this).apply { addView(col) })
        setContentView(frame)

        col.addView(tv("奇的放大镜", 28f, BLUE, true).apply { setPadding(d(4), 0, 0, d(14)) })

        // 7日胜率
        val today = Store.today()
        val c1 = card("近7日平均胜率")
        val a7 = Store.avgRate7(this)
        c1.addView(tv(if (a7 == null) "--" else "%.1f%%".format(a7 * 100), 40f, BLUE, true))
        c1.addView(tv("今日：${Store.correct(this, today)}/${Store.total(this, today)}  准确率 ${"%.0f".format(Store.rate(this, today) * 100)}%", 14f))
        col.addView(c1)

        // 评分
        val c2 = card("今日评分（1-10）")
        val r = LinearLayout(this)
        val cur = Store.score(this, today)
        for (i in 1..10) r.addView(TextView(this).apply {
            text = "$i"; gravity = Gravity.CENTER; textSize = 14f; setPadding(0, d(10), 0, d(10))
            setTextColor(Color.parseColor(if (i == cur) "#FFFFFF" else BLUE))
            background = GradientDrawable().apply { setColor(Color.parseColor(if (i == cur) BLUE else "#22FFFFFF")); cornerRadius = d(8).toFloat(); setStroke(d(1), Color.parseColor("#550084FF")) }
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(d(2), d(6), d(2), 0) }
            setOnClickListener { Store.setScore(this@MainActivity, today, i); val x = applicationContext; Thread { GitHub.sync(x) }.start(); build() }
        })
        c2.addView(r)
        val a15 = Store.avgScore15(this)
        c2.addView(tv(if (a15 == null) "近15日均分：暂无" else "近15日均分：%.1f".format(a15), 14f).apply { setPadding(0, d(10), 0, 0) })
        if (a15 != null && a15 < 5f) c2.addView(tv("⚠ 内容创意存在严重不足，需要提高胜率以及内容敏感度", 14f, "#E74C3C", true).apply { setPadding(0, d(6), 0, 0) })
        col.addView(c2)

        // 悬浮窗
        val c3 = card("悬浮窗")
        c3.addView(button(if (FloatingService.running) "关闭悬浮窗" else "开启悬浮窗", if (FloatingService.running) "#E74C3C" else BLUE) {
            if (FloatingService.running) { stopService(Intent(this, FloatingService::class.java)); build() }
            else if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            else { startForegroundService(Intent(this, FloatingService::class.java)); build() }
        })
        slider("小圆标透明度", "bubble_alpha", 100, c3)
        slider("黑色遮挡透明度", "mask_alpha", 100, c3)
        col.addView(c3)

        // 排行榜
        val c4 = card("准确率排行榜（本机，GitHub上有完整版 LEADERBOARD.md）")
        val top = Store.dates(this).filter { Store.total(this, it) > 0 }.sortedWith(compareByDescending<String> { Store.rate(this, it) }.thenByDescending { Store.total(this, it) }).take(7)
        if (top.isEmpty()) c4.addView(tv("暂无数据"))
        top.forEachIndexed { i, dt -> c4.addView(tv("${i + 1}. $dt   ${"%.1f".format(Store.rate(this, dt) * 100)}%  (${Store.correct(this, dt)}/${Store.total(this, dt)})")) }
        col.addView(c4)

        // GitHub
        val c5 = card("GitHub 同步")
        val sp = Store.sp(this)
        fun field(h: String, k: String, pw: Boolean = false) = EditText(this).apply {
            hint = h; setText(sp.getString(k, "")); textSize = 14f; setSingleLine()
            if (pw) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val fo = field("GitHub 用户名", "owner"); val fr = field("仓库名（需已存在）", "repo"); val ft = field("Token（仅需该仓库 Contents 读写）", "token", true)
        c5.addView(fo); c5.addView(fr); c5.addView(ft)
        val status = tv("", 13f, "#5A6B7F")
        c5.addView(button("保存并全量同步") {
            sp.edit().putString("owner", fo.text.toString().trim()).putString("repo", fr.text.toString().trim()).putString("token", ft.text.toString().trim()).apply()
            status.text = "同步中…"; val x = applicationContext
            Thread { val m = GitHub.sync(x, true); runOnUiThread { status.text = m } }.start()
        })
        c5.addView(status)
        col.addView(c5)
    }
}
