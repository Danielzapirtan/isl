package dev.datacap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class LimiterVpnService : VpnService() {
    @Volatile
    private var tunnel: ParcelFileDescriptor? = null
    private var socksServer: LocalSocksServer? = null
    private var rateLimiter: DownloadRateLimiter? = null
    private var monitorThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_UPDATE_LIMIT -> {
                val limit = intent.getLongExtra(EXTRA_LIMIT_BPS, 0L)
                if (limit > 0L) {
                    val limiter = rateLimiter
                    if (limiter == null) {
                        startTunnel(limit)
                    } else {
                        limiter.setLimitBps(limit)
                        updateNotification(limit)
                    }
                } else {
                    stopWithError("The download limit must be greater than zero.")
                }
            }
            ACTION_START -> {
                val limit = intent.getLongExtra(EXTRA_LIMIT_BPS, 0L)
                if (limit > 0L) startTunnel(limit) else stopWithError("Invalid download limit.")
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(limitBps: Long) {
        if (tunnel != null) {
            rateLimiter?.setLimitBps(limitBps)
            updateNotification(limitBps)
            return
        }
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(limitBps))

        try {
            val limiter = DownloadRateLimiter(limitBps)
            val proxy = LocalSocksServer(limiter)
            rateLimiter = limiter
            socksServer = proxy
            proxy.start()

            val config = """
                tunnel:
                  name: datacap
                  mtu: 1500
                  ipv4: 198.18.0.1
                  ipv6: 'fc00::1'
                  icmp: 'off'
                socks5:
                  address: 127.0.0.1
                  port: 10808
                  udp: 'udp'
                misc:
                  log-level: error
            """.trimIndent()
            val configFile = File(cacheDir, "hev-tunnel.yml").apply { writeText(config) }

            val builder = Builder()
                .setSession("DataCap")
                .setBlocking(false)
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("2606:4700:4700::1111")

            try {
                // This package's sockets must bypass the VPN; the native tunnel and
                // local SOCKS proxy both run under this UID.
                builder.addDisallowedApplication(packageName)
            } catch (error: android.content.pm.PackageManager.NameNotFoundException) {
                throw IOException("Could not exclude the app's own upstream sockets", error)
            }

            val descriptor = builder.establish() ?: throw IOException("Android did not establish the VPN")
            tunnel = descriptor
            if (!TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
                throw IOException("The userspace tunnel failed to start")
            }

            setRunning(this, true)
            updateNotification(limitBps)
            monitorTunnel()
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start VPN", error)
            stopWithError(error.message ?: "Could not start the VPN.")
        }
    }

    private fun monitorTunnel() {
        monitorThread?.interrupt()
        monitorThread = thread(name = "datacap-tunnel-monitor") {
            while (!Thread.currentThread().isInterrupted && tunnel != null) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return@thread
                }
                if (!TProxyService.TProxyIsRunning()) {
                    Log.e(TAG, "The userspace tunnel stopped unexpectedly")
                    stopWithError("The VPN tunnel stopped unexpectedly.")
                    return@thread
                }
            }
        }
    }

    private fun stopWithError(message: String) {
        stopTunnel()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(0L, message))
        stopSelf()
    }

    private fun stopTunnel() {
        monitorThread?.interrupt()
        monitorThread = null

        if (TProxyService.TProxyIsRunning()) {
            TProxyService.TProxyStopService()
        }
        socksServer?.close()
        socksServer = null
        try {
            tunnel?.close()
        } catch (error: IOException) {
            Log.w(TAG, "Failed to close VPN descriptor", error)
        }
        tunnel = null
        rateLimiter = null
        setRunning(this, false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(limitBps: Long) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(limitBps))
    }

    private fun buildNotification(limitBps: Long, message: String? = null): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, LimiterVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DataCap")
            .setContentText(message ?: "Download limit: ${limitBps} bps")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(message == null)
            .addAction(
                Notification.Action.Builder(null, "Stop", stopIntent).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DataCap VPN",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "dev.datacap.START"
        const val ACTION_STOP = "dev.datacap.STOP"
        const val ACTION_UPDATE_LIMIT = "dev.datacap.UPDATE_LIMIT"
        const val EXTRA_LIMIT_BPS = "limit_bps"

        private const val TAG = "DataCap"
        private const val CHANNEL_ID = "datacap_vpn"
        private const val NOTIFICATION_ID = 1
        private const val MONITOR_INTERVAL_MILLIS = 1_000L
        private const val PREFS = "datacap_service"
        private const val PREF_RUNNING = "running"

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_RUNNING, false)

        private fun setRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_RUNNING, running)
                .apply()
        }
    }
}
