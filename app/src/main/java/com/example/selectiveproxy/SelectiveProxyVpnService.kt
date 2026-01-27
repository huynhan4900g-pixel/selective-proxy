package com.example.selectiveproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class SelectiveProxyVpnService : VpnService() {
    companion object {
        private const val TAG = "SelectiveProxyVpnService"
        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "proxy_service"
        const val ACTION_STOP_SERVICE = "com.example.selectiveproxy.STOP_SERVICE"
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var protocolAdapter: ProtocolAdapter? = null
    private var tun2socks: Tun2SocksWrapper? = null
    private val isRunning = AtomicBoolean(false)

    private val stopPendingIntent: PendingIntent by lazy {
        val stopIntent = Intent(this, SelectiveProxyVpnService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Android 14 (API 34) requires specifying the foreground service type.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopInternal()
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent == null) return START_NOT_STICKY

        return try {
            val config = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("CONFIG", SecureProxyConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("CONFIG")
            } ?: return START_NOT_STICKY

            val apps = intent.getStringArrayListExtra("ALLOWED_APPS") ?: emptyList()

            stopInternal()

            if (setupVpn(config, apps)) {
                isRunning.set(true)
                START_STICKY
            } else {
                stopSelf()
                START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start command failed", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun setupVpn(config: SecureProxyConfig, apps: List<String>): Boolean {
        return try {
            protocolAdapter = ProtocolAdapter(config, this, ConnectionTracker(), this).apply {
                if (start() <= 0) throw IOException("Adapter failed to start")
            }

            val builder = Builder().apply {
                setSession("Selective Proxy")
                setConfigureIntent(PendingIntent.getActivity(
                    this@SelectiveProxyVpnService,
                    0,
                    Intent(this@SelectiveProxyVpnService, SelectiveProxyActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                ))

                addAddress("10.0.0.2", 24)
                addDnsServer("8.8.8.8")
                addDnsServer("1.1.1.1")
                addRoute("0.0.0.0", 0)
                setMtu(1300)

                if (config.blockAllDoh) {
                    listOf(
                        "1.1.1.1", "1.0.0.1",
                        "8.8.8.8", "8.8.4.4",
                        "9.9.9.9", "149.112.112.112",
                        "185.228.168.168", "185.228.169.168",
                        "208.67.222.222", "208.67.220.220"
                    ).forEach { addRoute(it, 32) }
                }

                apps.forEach { pkg ->
                    try {
                        addAllowedApplication(pkg)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.w(TAG, "App not found: $pkg")
                    }
                }

                setBlocking(false)
            }

            vpnInterface = builder.establish() ?: throw IOException("VPN establishment failed")

            tun2socks = Tun2SocksWrapper(this, vpnInterface!!, protocolAdapter!!.getLocalPort()).apply {
                start()
            }

            Log.i(TAG, "VPN setup complete for ${apps.size} apps")
            updateNotification("Active for ${apps.size} apps")
            true

        } catch (e: Exception) {
            Log.e(TAG, "VPN setup failed", e)
            stopInternal()
            false
        }
    }

    private fun stopInternal() {
        isRunning.set(false)

        try {
            tun2socks?.stop()
            Thread.sleep(100)
            protocolAdapter?.stop()
            Thread.sleep(100)
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        } finally {
            vpnInterface = null
            tun2socks = null
            protocolAdapter = null
            updateNotification("Stopped")
        }
    }

    override fun onDestroy() {
        stopInternal()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Selective proxy service"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SelectiveProxyActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Selective Proxy")
            .setContentText("Мониторинг сетевого трафика")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopPendingIntent
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification().apply {
            // Re-creating builder to update text, or separate builder.
            // NotificationCompat.Builder properties are not mutable after build() usually
            // but we can just rebuild it.
        }
        // Simplified update logic:
        val updatedNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Selective Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, SelectiveProxyActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ))
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopPendingIntent
            )
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, updatedNotification)
    }
}
