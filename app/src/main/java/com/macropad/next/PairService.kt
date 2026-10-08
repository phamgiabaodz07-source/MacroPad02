package com.macropad.next

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors

/** Dịch vụ nền: hiện thông báo có ô nhập mã 6 số, ghép đôi và kết nối */
class PairService : Service() {

    companion object {
        const val ACTION_START = "macropad.pair.start"
        const val ACTION_REPLY = "macropad.pair.reply"
        const val ACTION_CONNECT = "macropad.pair.connect"
        const val ACTION_STOP = "macropad.pair.stop"
        const val KEY_CODE = "code"
        const val CH = "pair"
        const val NID = 21
    }

    private var pairing: Mdns? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val nm by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm.createNotificationChannel(NotificationChannel(CH, "Ghép đôi", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                fg(notif("Đang chờ mã ghép đôi", "Mở 'Ghép đôi bằng mã', rồi bấm Nhập mã ở đây.", true))
                pairing?.stop()
                pairing = Mdns(this, "_adb-tls-pairing._tcp").also { it.start() }
                UiLog.add("Đang chờ mã. Mở 'Ghép đôi thiết bị bằng mã ghép đôi'.")
            }
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
                fg(notif("Đang ghép đôi...", "", false))
                worker.execute { doPair(code) }
            }
            ACTION_CONNECT -> {
                fg(notif("Đang kết nối...", "", false))
                worker.execute { doConnect() }
            }
            ACTION_STOP -> stopClean()
        }
        return START_NOT_STICKY
    }

    private fun doPair(code: String) {
        try {
            if (code.length != 6) {
                UiLog.add("Mã phải đủ 6 số (bạn nhập: '$code')")
                update("Mã chưa đúng", "Bấm Nhập mã và gõ lại 6 số.", true)
                return
            }
            val port = pairing?.waitPort(8000) ?: -1
            if (port < 0) {
                UiLog.add("Không thấy cổng ghép đôi. Hãy mở màn 'Ghép đôi bằng mã' rồi nhập lại.")
                update("Chưa thấy cổng ghép đôi", "Mở 'Ghép đôi bằng mã' rồi nhập lại.", true)
                return
            }
            UiLog.add("Ghép đôi qua cổng $port...")
            Adb.pair(this, port, code)
            UiLog.add("Ghép đôi thành công ✓")
            getSharedPreferences("s", 0).edit().putBoolean("paired", true).apply()
            pairing?.stop()
            doConnect()
        } catch (e: Throwable) {
            UiLog.add("Lỗi ghép đôi: ${e.javaClass.simpleName}: ${e.message}")
            update("Ghép đôi lỗi", "Bấm Nhập mã để thử lại.", true)
        }
    }

    private fun doConnect() {
        if (Adb.ensureConnected(this)) {
            val out = try { Adb.shell(this, "id").trim() } catch (e: Throwable) { "(không chạy được lệnh)" }
            UiLog.add(out)
            val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("MacroPad 2: đã kết nối ✓").setContentText(out).setAutoCancel(true).build()
            stopClean()
            nm.notify(22, n)
        } else {
            update("Chưa kết nối được", "Xem nhật ký trong app.", true)
        }
    }

    private fun fg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NID, n)
    }

    private fun update(title: String, text: String, withInput: Boolean) = nm.notify(NID, notif(title, text, withInput))

    private fun notif(title: String, text: String, withInput: Boolean): Notification {
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (withInput) {
            val ri = RemoteInput.Builder(KEY_CODE).setLabel("Mã 6 số").build()
            val pi = PendingIntent.getService(
                this, 1, Intent(this, PairService::class.java).setAction(ACTION_REPLY),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            val act = Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "Nhập mã", pi
            ).addRemoteInput(ri).build()
            b.addAction(act)
        }
        return b.build()
    }

    private fun stopClean() {
        pairing?.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pairing?.stop()
        worker.shutdownNow()
        super.onDestroy()
    }
}
