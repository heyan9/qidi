package com.qidi.magnifier
import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** 持有系统截屏授权（MediaProjection），悬浮窗按下"截屏"时取最新一帧 */
class CaptureService : Service() {
    companion object {
        var inst: CaptureService? = null
        val ready: Boolean get() = inst?.reader != null
        fun grab(): Bitmap? = inst?.grabBitmap()
    }
    private var mp: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    var reader: ImageReader? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null || !i.hasExtra("data")) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("qi_cap", "截屏", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "qi_cap").setContentTitle("奇的放大镜：截屏已就绪")
            .setSmallIcon(android.R.drawable.ic_menu_camera).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(2, n)
        cleanup()
        val data = i.getParcelableExtra<Intent>("data")!!
        val m = getSystemService(MediaProjectionManager::class.java).getMediaProjection(i.getIntExtra("code", -1), data)
        mp = m
        m.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (mp === m) { cleanup(); stopSelf() } }
        }, Handler(Looper.getMainLooper()))
        val dm = resources.displayMetrics
        val r = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = m.createVirtualDisplay("qi", dm.widthPixels, dm.heightPixels, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null)
        inst = this
        return START_NOT_STICKY
    }

    private fun cleanup() {
        val m = mp; mp = null
        vd?.release(); vd = null
        reader?.close(); reader = null
        if (inst === this) inst = null
        try { m?.stop() } catch (_: Exception) {}
    }

    fun grabBitmap(): Bitmap? {
        val img = (try { reader?.acquireLatestImage() } catch (e: Exception) { null }) ?: return null
        return try {
            val pl = img.planes[0]
            val rowPad = pl.rowStride - pl.pixelStride * img.width
            val w = img.width + rowPad / pl.pixelStride
            val b = Bitmap.createBitmap(w, img.height, Bitmap.Config.ARGB_8888)
            b.copyPixelsFromBuffer(pl.buffer)
            if (w == img.width) b else Bitmap.createBitmap(b, 0, 0, img.width, img.height)
        } finally { img.close() }
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
}
