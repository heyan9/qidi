package com.qidi.magnifier
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.*

class BubbleView(c: Context) : View(c) {
    var rate = 0f; var t = 0; var ok = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val r = w / 2
        p.style = Paint.Style.FILL; p.color = Color.parseColor("#CC1B1B1F"); cv.drawCircle(r, r, r, p)
        val path = Path().apply { addCircle(r, r, r - 3, Path.Direction.CW) }
        cv.save(); cv.clipPath(path)
        p.color = Color.parseColor(when { t == 0 -> "#660084FF"; rate >= 0.6f -> "#CC0084FF"; rate >= 0.4f -> "#CC6CB4FF"; else -> "#CCD4894A" })
        cv.drawRect(0f, w - w * rate, w, w, p)   // 像电量一样从下往上充
        cv.restore()
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textAlign = Paint.Align.CENTER; p.isFakeBoldText = true
        p.textSize = w * 0.27f; cv.drawText(if (t == 0) "奇" else "${(rate * 100).toInt()}%", r, r + w * 0.03f, p)
        p.isFakeBoldText = false; p.textSize = w * 0.19f; cv.drawText("$ok/$t", r, r + w * 0.27f, p)
    }
}

class FloatingService : Service() {
    companion object { var running = false }
    private lateinit var wm: WindowManager
    private lateinit var bubble: BubbleView
    private lateinit var bp: WindowManager.LayoutParams
    private var panel: View? = null
    private var panelP: WindowManager.LayoutParams? = null
    private var mask: FrameLayout? = null
    private var maskP: WindowManager.LayoutParams? = null
    private var noteWin: View? = null
    private var reopenMask = false

    private fun d(x: Int) = (x * resources.displayMetrics.density).toInt()
    private val sw get() = resources.displayMetrics.widthPixels
    private val sh get() = resources.displayMetrics.heightPixels
    private fun sp() = Store.sp(this)
    private fun params(w: Int, h: Int, focus: Boolean = false) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        if (focus) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; if (focus) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }
    private fun savePos(k: String, p: WindowManager.LayoutParams) {
        sp().edit().putInt(k + "_x", p.x).putInt(k + "_y", p.y).putInt(k + "_w", p.width).putInt(k + "_h", p.height).apply()
    }
    private fun loadP(k: String, w: Int, h: Int, x: Int, y: Int, focus: Boolean = false): WindowManager.LayoutParams {
        val s = sp()
        return params(s.getInt(k + "_w", w), s.getInt(k + "_h", h), focus).apply { this.x = s.getInt(k + "_x", x); this.y = s.getInt(k + "_y", y) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (!running) {
            running = true; startFg()
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            bubble = BubbleView(this)
            bp = loadP("bubble", d(56), d(56), d(8), d(200)).apply { width = d(56); height = d(56) }
            drag(bubble, bubble, bp, { followPanel() }, { togglePanel() }, "bubble")
            wm.addView(bubble, bp)
        }
        applyPrefs(); refreshBubble()
        return START_STICKY
    }

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("qi", "悬浮窗", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "qi").setContentTitle("奇的放大镜运行中")
            .setSmallIcon(android.R.drawable.ic_menu_search).build()
        startForeground(1, n)
    }

    /** handle 被拖动时移动 target；点击触发 onTap；拖完保存位置 */
    private fun drag(handle: View, target: View, p: WindowManager.LayoutParams, onMove: (() -> Unit)?, onTap: (() -> Unit)?, key: String?) {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) moved = true
                    if (moved) { p.x = ox + dx.toInt(); p.y = oy + dy.toInt(); wm.updateViewLayout(target, p); onMove?.invoke() }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke() else if (key != null) savePos(key, p)
            }
            true
        }
    }
    private fun resize(h: View, target: View, p: WindowManager.LayoutParams, minW: Int, minH: Int, key: String) {
        var sx = 0f; var sy = 0f; var w0 = 0; var h0 = 0
        h.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; w0 = p.width; h0 = p.height }
                MotionEvent.ACTION_MOVE -> {
                    p.width = maxOf(minW, w0 + (e.rawX - sx).toInt()); p.height = maxOf(minH, h0 + (e.rawY - sy).toInt())
                    wm.updateViewLayout(target, p)
                }
                MotionEvent.ACTION_UP -> savePos(key, p)
            }
            true
        }
    }

    private fun applyPrefs() {
        bubble.alpha = sp().getInt("bubble_alpha", 100) / 100f
        mask?.setBackgroundColor(Color.argb(sp().getInt("mask_alpha", 100) * 255 / 100, 0, 0, 0))
    }
    private fun refreshBubble() {
        val dt = Store.today()
        bubble.t = Store.total(this, dt); bubble.ok = Store.correct(this, dt); bubble.rate = Store.rate(this, dt)
        bubble.invalidate()
    }

    // ---------- 黑底 UI ----------
    private fun box() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(d(10), d(10), d(10), d(10))
        background = GradientDrawable().apply { setColor(Color.parseColor("#F0141416")); cornerRadius = d(16).toFloat(); setStroke(d(1), Color.parseColor("#33FFFFFF")) }
    }
    private fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(Color.parseColor("#DDDDDD")); setPadding(d(4), d(4), d(4), d(6)) }
    private fun btn(t: String, bg: String = "#2C2C31", click: () -> Unit) = TextView(this).apply {
        text = t; setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER; setPadding(d(4), d(10), d(4), d(10))
        background = GradientDrawable().apply { setColor(Color.parseColor(bg)); cornerRadius = d(10).toFloat() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(d(3), d(3), d(3), d(3)) }
        setOnClickListener { click() }
    }
    private fun row(vararg v: View) = LinearLayout(this).apply { v.forEach { addView(it) } }

    // ---------- 选项面板：跟随悬浮球 ----------
    private var pendingSync = false
    private fun togglePanel() {
        if (panel != null) {
            closePanel(); if (reopenMask) { reopenMask = false; openMask() }
            if (pendingSync) { pendingSync = false; val c = applicationContext; Thread { GitHub.sync(c) }.start() }
        } else showMenu()
    }
    private fun closePanel() { panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panel = null }
    private fun place(p: WindowManager.LayoutParams, h: Int) {
        p.x = minOf(maxOf(bp.x, 0), maxOf(0, sw - d(270)))
        p.y = bp.y + d(56) + d(6)
        if (p.y + h > sh) p.y = maxOf(0, bp.y - h - d(6))
    }
    private fun followPanel() {
        val v = panel ?: return; val p = panelP ?: return
        place(p, if (v.height > 0) v.height else d(240)); try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
    }
    private fun showPanel(b: View) {
        closePanel()
        val p = params(d(270), ViewGroup.LayoutParams.WRAP_CONTENT); place(p, d(240))
        wm.addView(b, p); panel = b; panelP = p
        b.post { followPanel() }
    }
    private fun grid(items: List<String>, cols: Int, mk: (String) -> View): LinearLayout {
        val g = LinearLayout(this); g.orientation = LinearLayout.VERTICAL
        items.chunked(cols).forEach { r ->
            val vs = r.map(mk).toMutableList()
            while (vs.size < cols) vs.add(View(this).also { it.layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            g.addView(row(*vs.toTypedArray()))
        }
        return g
    }
    private fun setBg(v: View, c: String) { (v.background as GradientDrawable).setColor(Color.parseColor(c)) }
    private fun chip(t: String, sel: MutableSet<String>): TextView {
        val v = btn(t) {}; v.textSize = 13f
        v.setOnClickListener { if (sel.remove(t)) setBg(v, "#2C2C31") else { sel.add(t); setBg(v, "#0084FF") } }
        return v
    }

    private fun showMenu() {
        val b = box()
        b.addView(label("选择区间"))
        b.addView(row(
            btn(if (mask != null) "关遮挡" else "黑遮挡") { closePanel(); if (mask != null) closeMask() else openMask() },
            btn("记事本") { showNotes() },
            btn("退出", "#8F5F36") { stopSelf() }))
        b.addView(grid(listOf("1", "2", "3", "4", "5", "5-8", "8-1k", "1k+", "2k+"), 3) { o -> btn(o) { pick(o) } })
        showPanel(b)
    }
    private fun pick(c: String) {
        reopenMask = mask != null
        closeMask()
        val b = box()
        b.addView(label("区间 $c ：结果是？"))
        b.addView(row(btn("✔ 对", "#0084FF") { judge(c, true) }, btn("✘ 错", "#D4894A") { judge(c, false) }))
        showPanel(b)
    }
    private fun judge(c: String, ok: Boolean) {
        Store.add(this, c, ok); refreshBubble()
        if (reopenMask) { reopenMask = false; openMask() }   // 黑色遮挡重新打开
        pendingSync = true
        showTypes(ok)
    }
    private fun finishFlow() {
        pendingSync = false; refreshBubble()
        val c = applicationContext; Thread { GitHub.sync(c) }.start()
        showMenu()
    }
    private fun showTypes(ok: Boolean) {
        val items = Store.options(this, if (ok) "ok_type" else "bad_type")
        if (items.isEmpty()) { finishFlow(); return }
        val b = box()
        b.addView(label(if (ok) "✔ 选择内容类型" else "✘ 选择内容类型"))
        b.addView(grid(items, 3) { t -> btn(t) { showTags(ok, t) } })
        b.addView(row(btn("跳过") { finishFlow() }))
        showPanel(b)
    }
    private fun showTags(ok: Boolean, type: String) {
        Store.setLastTags(this, type, emptyList())
        val items = Store.options(this, if (ok) "ok_tag" else "bad_tag")
        if (items.isEmpty()) { finishFlow(); return }
        val sel = linkedSetOf<String>()
        val b = box()
        b.addView(label("$type · 选择特征（可多选）"))
        val sv = ScrollView(this)
        sv.addView(grid(items, 2) { t -> chip(t, sel) })
        sv.layoutParams = LinearLayout.LayoutParams(-1, minOf(d(300), sh / 2 - d(60)))
        b.addView(sv)
        b.addView(row(
            btn("完成", "#0084FF") { Store.setLastTags(this@FloatingService, type, sel.toList()); finishFlow() },
            btn("跳过") { finishFlow() }))
        showPanel(b)
    }

    // ---------- 黑色遮挡（位置大小记忆） ----------
    private fun openMask() {
        if (mask != null) return
        val a = sp().getInt("mask_alpha", 100) * 255 / 100
        val f = FrameLayout(this).apply { setBackgroundColor(Color.argb(a, 0, 0, 0)) }
        val p = loadP("mask", d(260), d(110), (sw - d(260)) / 2, d(300)); maskP = p
        val x = TextView(this).apply { text = "✕"; setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER; setOnClickListener { closeMask() } }
        f.addView(x, FrameLayout.LayoutParams(d(40), d(40), Gravity.TOP or Gravity.END))
        val h = TextView(this).apply { text = "◢"; setTextColor(Color.parseColor("#AAFFFFFF")); textSize = 20f; gravity = Gravity.CENTER }
        f.addView(h, FrameLayout.LayoutParams(d(44), d(44), Gravity.BOTTOM or Gravity.END))
        resize(h, f, p, d(60), d(40), "mask")
        drag(f, f, p, null, null, "mask")
        wm.addView(f, p); mask = f
        wm.removeView(bubble); wm.addView(bubble, bp)
    }
    private fun closeMask() { mask?.let { try { wm.removeView(it) } catch (_: Exception) {} }; mask = null }

    // ---------- 记事本：黑底、可拖动、可缩放、可调透明度 ----------
    private fun showNotes() {
        closePanel(); if (noteWin != null) return
        val b = box()
        val title = label("📝 错题记事本（按住此处拖动）")
        val et = EditText(this).apply {
            hint = "为什么出错？原因分析…"; setHintTextColor(Color.parseColor("#888888")); setTextColor(Color.WHITE)
            gravity = Gravity.TOP; textSize = 14f
            background = GradientDrawable().apply { setColor(Color.parseColor("#2A2A2E")); cornerRadius = d(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val seek = SeekBar(this).apply {
            max = 70; progress = sp().getInt("note_alpha", 100) - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { b.alpha = (p + 30) / 100f; sp().edit().putInt("note_alpha", p + 30).apply() }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val handle = TextView(this).apply { text = "◢"; setTextColor(Color.parseColor("#AAFFFFFF")); textSize = 20f; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(d(44), d(44)) }
        b.addView(title); b.addView(et); b.addView(label("透明度")); b.addView(seek)
        b.addView(row(
            btn("保存", "#0084FF") {
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) {
                    Store.addNote(this@FloatingService, t)
                    val ctx = applicationContext; Thread { GitHub.sync(ctx) }.start()
                    Toast.makeText(this@FloatingService, "已保存", Toast.LENGTH_SHORT).show()
                }
                closeNotes()
            },
            btn("取消") { closeNotes() }, handle))
        val p = loadP("note", d(290), d(280), (sw - d(290)) / 2, d(90), true)
        b.alpha = sp().getInt("note_alpha", 100) / 100f
        drag(title, b, p, null, null, "note")
        resize(handle, b, p, d(200), d(190), "note")
        wm.addView(b, p); noteWin = b
    }
    private fun closeNotes() { noteWin?.let { try { wm.removeView(it) } catch (_: Exception) {} }; noteWin = null }

    override fun onDestroy() {
        running = false; closePanel(); closeMask(); closeNotes()
        try { wm.removeView(bubble) } catch (_: Exception) {}
        super.onDestroy()
    }
}
