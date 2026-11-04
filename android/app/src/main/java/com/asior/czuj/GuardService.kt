package com.asior.czuj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid

// Strażnik w tle: ciągły skan BLE z filtrami (działa też przy zgaszonym ekranie),
// odcisk miejsca z Wi-Fi, reguły z TrackerCore, alarm = powiadomienie + głos.
class GuardService : Service() {

    class Status(val running: Boolean, val nearby: Int, val error: String, val placeKnown: Boolean) {
        fun forModel(): String =
            if (!running) "strażnik wyłączony"
            else "strażnik włączony, trackerów w pobliżu: $nearby" +
                (if (!placeKnown) ", miejsce nieznane (włącz lokalizację)" else "") +
                (if (error.isNotEmpty()) ", problem: $error" else "")
    }

    companion object {
        private const val CH_GUARD = "guard"
        private const val CH_ALERT = "alert"
        private const val ID_GUARD = 1
        private const val WIFI_SCAN_MS = 120_000L

        @Volatile var running = false
            private set
        @Volatile var error = ""
            private set
        @Volatile private var placeKnown = true
        @Volatile var cloudError = ""
        private val watch = TrackerCore.Watch()

        fun status(): Status {
            val n = synchronized(watch) { watch.nearby(System.currentTimeMillis()) }
            return Status(running, n, error, placeKnown)
        }

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, GuardService::class.java))

        private fun uuid(short: String) = ParcelUuid.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }

    private var scanner: BluetoothLeScanner? = null
    private var lastWifiScan = 0L
    private var alertId = 100

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) { error = "błąd skanu Bluetooth ($errorCode)" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Voice.init(this)
        channels()
        val n = notification(CH_GUARD, "Czuj pilnuje", "Szukam trackerów, które jadą z tobą.", false)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_GUARD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID_GUARD, n)
        running = true
        startScan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { scanner?.stopScan(callback) } catch (e: Exception) { }
        running = false
        super.onDestroy()
    }

    private fun startScan() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) { error = "brak Bluetooth"; return }
        if (!adapter.isEnabled) { error = "Bluetooth wyłączony"; return }
        scanner = adapter.bluetoothLeScanner
        val filters = ArrayList<ScanFilter>()
        filters.add(ScanFilter.Builder().setManufacturerData(0x004C, byteArrayOf(0x12, 0x19), byteArrayOf(-1, -1)).build())
        for (s in listOf("feed", "feec", "fd5a")) {
            filters.add(ScanFilter.Builder().setServiceUuid(uuid(s)).build())
            filters.add(ScanFilter.Builder().setServiceData(uuid(s), ByteArray(0)).build())
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        try {
            scanner?.startScan(filters, settings, callback)
            error = ""
        } catch (e: SecurityException) {
            error = "brak zgody na Bluetooth"
        } catch (e: Exception) {
            error = "skan Bluetooth: ${e.message}"
        }
    }

    private fun handle(r: ScanResult) {
        val rec = r.scanRecord ?: return
        val mfr = HashMap<Int, ByteArray>()
        rec.manufacturerSpecificData?.let { for (i in 0 until it.size()) mfr[it.keyAt(i)] = it.valueAt(i) }
        val uuids = ArrayList<String>()
        rec.serviceUuids?.forEach { uuids.add(it.toString()) }
        rec.serviceData?.keys?.forEach { uuids.add(it.toString()) }
        val tag = TrackerCore.classify(mfr, uuids)
        if (tag.isEmpty()) return
        val now = System.currentTimeMillis()
        val top = wifiTop6(now)
        placeKnown = top.isNotEmpty()
        val alert = synchronized(watch) { watch.sighting(r.device.address, tag, top, now) }
        if (alert != null) onAlert(alert, tag)
    }

    private fun wifiTop6(now: Long): Set<String> {
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return emptySet()
        if (now - lastWifiScan > WIFI_SCAN_MS) {
            lastWifiScan = now
            @Suppress("DEPRECATION")
            try { wm.startScan() } catch (e: Exception) { }
        }
        return try {
            wm.scanResults.sortedByDescending { it.level }.take(6).map { it.BSSID }.toSet()
        } catch (e: SecurityException) {
            emptySet()
        }
    }

    private fun onAlert(text: String, tag: String) {
        Store.addAlert(this, text)
        getSystemService(NotificationManager::class.java)
            ?.notify(alertId++, notification(CH_ALERT, "Czuj: możliwy tracker", text, true))
        Voice.say(text)
        if (Cloud.configured && Store.share(this)) {
            Where.locate(this) { loc ->
                if (loc != null) Thread {
                    try { Cloud.report(this, tag, loc.latitude, loc.longitude) } catch (e: Exception) { cloudError = "${e.message}" }
                }.start()
            }
        }
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Strażnik w tle", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alarmy o trackerach", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(channel: String, title: String, text: String, alert: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(!alert)
            .setAutoCancel(alert)
            .build()
    }
}
