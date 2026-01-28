package com.example.selectiveproxy

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.ArrayList

class SelectiveProxyActivity : AppCompatActivity() {
    companion object {
        const val REQUEST_VPN = 1001
    }

    private lateinit var appRecyclerView: RecyclerView
    private lateinit var hostEditText: EditText
    private lateinit var portEditText: EditText
    private lateinit var userEditText: EditText
    private lateinit var passEditText: EditText
    private val selectedApps = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        hostEditText = findViewById(R.id.hostInput)
        portEditText = findViewById(R.id.portInput)
        userEditText = findViewById(R.id.userInput)
        passEditText = findViewById(R.id.passInput)
        appRecyclerView = findViewById(R.id.appRecyclerView)

        appRecyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.startButton).setOnClickListener {
            startProxy()
        }

        requestNecessaryPermissions()
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                it.enabled && (
                    packageManager.getLaunchIntentForPackage(it.packageName) != null ||
                    (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                )
            }
            .sortedBy { it.loadLabel(packageManager).toString() }

        appRecyclerView.adapter = AppListAdapter(apps, selectedApps, packageManager)
    }

    private fun requestNecessaryPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    private fun startProxy() {
        val host = hostEditText.text.toString().trim()
        val port = portEditText.text.toString().toIntOrNull() ?: 8080
        val user = userEditText.text.toString().trim().takeIf { it.isNotEmpty() }
        val pass = passEditText.text.toString().trim().takeIf { it.isNotEmpty() }?.toCharArray()

        if (host.isEmpty() || selectedApps.isEmpty()) {
            Toast.makeText(
                this,
                "Пожалуйста, введите хост прокси и выберите хотя бы одно приложение",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val config = SecureProxyConfig.create(
            host = host,
            port = port,
            username = user,
            password = pass,
            context = this
        )

        pass?.fill('\u0000')

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityForResult(prepareIntent, REQUEST_VPN)
        } else {
            startVpnService(config, selectedApps.toList())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            val host = hostEditText.text.toString().trim()
            val port = portEditText.text.toString().toIntOrNull() ?: 8080
            val user = userEditText.text.toString().trim().takeIf { it.isNotEmpty() }
            val pass = passEditText.text.toString().trim().takeIf { it.isNotEmpty() }?.toCharArray()

            val config = SecureProxyConfig.create(host, port, user, pass, this)
            pass?.fill('\u0000')

            startVpnService(config, selectedApps.toList())
        }
    }

    private fun startVpnService(config: SecureProxyConfig, apps: List<String>) {
        val intent = Intent(this, SelectiveProxyVpnService::class.java).apply {
            putExtra("CONFIG", config)
            putStringArrayListExtra("ALLOWED_APPS", ArrayList(apps))
        }

        ContextCompat.startForegroundService(this, intent)

        Toast.makeText(
            this,
            "Прокси запущен для ${apps.size} приложений",
            Toast.LENGTH_SHORT
        ).show()
    }
}
