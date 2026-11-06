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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid

// Strażnik w tle: ciągły skan BLE z filtrami (działa też przy zgaszonym ekranie),
// odcisk miejsca z Wi-Fi, reguły z TrackerCore, alarm = powiadomienie (+ przycisk „To moje”) + głos.
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
        private const val BATTERY_MS = 30 * 60_000L
        private const val ACT_RESCAN = "rescan"

        @Volatile var running = false
            private set
        @Volatile var error = ""
            private set
        @Volatile private var placeKnown = true
        @Volatile var cloudError = ""
        /** Ostatni widziany tracker i ostatni z alarmem: (adres, nazwa). Do komendy „to mój”. */
        @Volatile var latest: Pair<String, String>? = null
        @Volatile var latestAlert: Pair<String, String>? = null
        private val watch = TrackerCore.Watch()

        fun status(): Status {
            val n = synchronized(watch) { watch.nearby(System.currentTimeMillis()) }
            return Status(running, n, error, placeKnown)
        }

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, GuardService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, GuardService::class.java))

        /** Po zmianie trybu skanu albo „skanuj teraz”. */
        fun rescan(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, GuardService::class.java).setAction(ACT_RESCAN))
        }

        /** Oznacz tracker jako mój: koniec alarmów i trasy dla niego. */
        fun markMine(ctx: Context, addr: String, tag: String) {
            Store.addMine(ctx, addr, tag)
            synchronized(watch) { watch.seen.remove(addr); watch.alerted.remove(addr) }
            if (latestAlert?.first == addr) latestAlert = null
        }

        private fun uuid(short: String) = ParcelUuid.fromString("0000$short-0000-1000-8000-00805f9b34fb")
    }

    private var scanner: BluetoothLeScanner? = null
    private var lastWifiScan = 0L
    private var alertId = 100
    private lateinit var trail: Trail
    private val main = Handler(Looper.getMainLooper())
    private val battery = object : Runnable {
        override fun run() { Diag.batterySample(this@GuardService); main.postDelayed(this, BATTERY_MS) }
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
        override fun onScanFailed(errorCode: Int) { error = "błąd skanu Bluetooth ($errorCode)" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Diag.install(this)
        Voice.init(this)
        channels()
        trail = Store.trail(this)
        val n = notification(CH_GUARD, "Czuj pilnuje", "Szukam trackerów, które jadą z tobą.", false, null)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_GUARD, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(ID_GUARD, n)
        running = true
        Diag.count(this, "starty")
        startScan()
        main.post(battery)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_RESCAN -> { stopScan(); lastWifiScan = 0; startScan() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(battery)
        stopScan()
        running = false
        super.onDestroy()
    }

    private fun stopScan() { try { scanner?.stopScan(callback) } catch (e: Exception) { } }

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
        val mode = when (Store.scanMode(this)) {
            2 -> ScanSettings.SCAN_MODE_LOW_LATENCY
            1 -> ScanSettings.SCAN_MODE_BALANCED
            else -> ScanSettings.SCAN_MODE_LOW_POWER
        }
        try {
            scanner?.startScan(filters, ScanSettings.Builder().setScanMode(mode).build(), callback)
            error = ""
        } catch (e: SecurityException) {
            error = "brak zgody na Bluetooth"
        } catch (e: Exception) {
            error = "skan Bluetooth: ${e.message}"
            Diag.error(this, "skan", e)
        }
    }

    private fun handle(r: ScanResult) {
        try {
            val rec = r.scanRecord ?: return
            val mfr = HashMap<Int, ByteArray>()
            rec.manufacturerSpecificData?.let { for (i in 0 until it.size()) mfr[it.keyAt(i)] = it.valueAt(i) }
            val uuids = ArrayList<String>()
            rec.serviceUuids?.forEach { uuids.add(it.toString()) }
            rec.serviceData?.keys?.forEach { uuids.add(it.toString()) }
            val tag = TrackerCore.classify(mfr, uuids)
            if (tag.isEmpty()) return
            val addr = r.device.address
            if (Store.isMine(this, addr)) return
            latest = addr to tag
            val now = System.currentTimeMillis()
            val top = wifiTop6(now)
            placeKnown = top.isNotEmpty()
            val (alert, n) = synchronized(watch) { watch.sighting(addr, tag, top, now) to (watch.seen[addr]?.n ?: 0) }
            Diag.count(this, "obserwacje")
            if (n >= 2) recordTrail(addr, tag, now)
            if (alert != null) onAlert(alert, addr, tag)
        } catch (e: Exception) {
            Diag.error(this, "obsługa skanu", e)
        }
    }

    /** Trasa: tylko w telefonie, punkt co 5 min, dopiero gdy tracker widziany co najmniej 2 razy. */
    private fun recordTrail(addr: String, tag: String, now: Long) {
        if (!synchronized(trail) { trail.due(addr, now) }) return
        Where.locate(this) { loc ->
            if (loc == null) return@locate
            synchronized(trail) {
                if (trail.add(addr, tag, loc.latitude, loc.longitude, now)) Store.saveTrail(this, trail)
            }
        }
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

    private fun onAlert(text: String, addr: String, tag: String) {
        Diag.count(this, "alarmy")
        Store.addAlertId(this, addr)
        latestAlert = addr to tag
        if (Store.muted(this)) {
            Store.addAlert(this, "$text (wyciszone)")
        } else {
            Store.addAlert(this, text)
            val nid = alertId++
            getSystemService(NotificationManager::class.java)
                ?.notify(nid, notification(CH_ALERT, "Czuj: możliwy tracker", text, true, mineAction(addr, tag, nid)))
            if (Store.voiceOn(this)) Voice.say(text)
        }
        if (Cloud.configured(this) && Store.share(this)) {
            Where.locate(this) { loc ->
                if (loc != null) Thread {
                    try { Cloud.report(this, tag, loc.latitude, loc.longitude); cloudError = "" }
                    catch (e: Exception) { cloudError = "${e.message}"; Diag.error(this, "mapa", e) }
                }.start()
            }
        }
    }

    private fun mineAction(addr: String, tag: String, nid: Int): Notification.Action {
        val i = Intent(this, MineReceiver::class.java)
            .putExtra("addr", addr).putExtra("tag", tag).putExtra("nid", nid)
        val pi = PendingIntent.getBroadcast(this, nid, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, "To moje", pi).build()
    }

    private fun channels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Strażnik w tle", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Alarmy o trackerach", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun notification(channel: String, title: String, text: String, alert: Boolean, action: Notification.Action?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(!alert)
            .setAutoCancel(alert)
        if (action != null) b.addAction(action)
        return b.build()
    }
}
