package com.floriangoetting.jsontagtestapp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.floriangoetting.jsontagtestapp.databinding.ActivityMainBinding
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var tracker: Tracker

    private fun getScreenSize(context: Context): String {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        return "${width}x${height}"
    }

    private fun getAppVersion(context: Context): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }
    }

    fun isPortrait(context: Context): Boolean {
        return context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    fun getBatteryState(context: Context): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
    }

    fun getBatteryLevel(context: Context): Int {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        return if (level != -1 && scale != -1) {
            (level * 100 / scale.toFloat()).toInt()
        } else {
            -1 // error case
        }
    }

    fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)

        return when {
            capabilities == null -> "none"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        //global event data
        val appData = mapOf(
            "environment" to "dev",
            "platform" to "app",
            "id" to this.packageName,
            "version" to getAppVersion(this),
            "store" to "google",
        )

        val settingsData = mapOf(
            "currency" to "EUR",
            "locale" to "de-DE",
            "language" to "de",
            "country" to "DE"
        )

        val deviceData = mapOf(
            "os_name" to "Android",
            "os_version" to android.os.Build.VERSION.RELEASE,
            "model" to android.os.Build.MODEL,
            "manufacturer" to android.os.Build.MANUFACTURER,
            "screen_size" to getScreenSize(this),
            "language" to Locale.getDefault().language,
            "is_portrait" to isPortrait(this),
            "battery_state" to getBatteryState(this),
            "battery_level" to getBatteryLevel(this),
            "network_type" to getNetworkType(this)
        )

        val consentData = mapOf(
            "idservice" to true,
            "klaro" to true,
            "matomo" to true,
            "amplitude" to true
        )

        // tracker config
        val sstEndpoint = "https://sst.floriangoetting.de"

        tracker = Tracker(this, sstEndpoint, "/data", sessionTimeoutInMinutes = 30)
        val globalEventData = mapOf(
            "app" to appData,
            "device" to deviceData,
            "settings" to settingsData,
            "consent" to consentData
        )
        tracker.setGlobalEventData(globalEventData)
        tracker.setDeviceIdCookieName("fgId")
        tracker.setSessionIdCookieName("web_session_id")
        //tracker.setGtmServerPreviewHeader("ZW52LTN8ZkNTSWNWLUttdzUwTGtzSVg1UlZBZ3wxOTcyYzI3NmMxOTNmNzIyM2M1YWY=")
        tracker.setWebviewUrl("https://www.floriangoetting.de/en/blogposts/")
        tracker.initialize()
        // end of tracker config

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_webview
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }
}