package com.floriangoetting.jsontagtestapp

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.common.net.InternetDomainName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Tracker(
    private val context: Context,
    private val endpoint: String,
    private val path: String,
    private var gtmServerPreviewHeader: String? = null,
    private var deviceIdCookieName: String = "fp_device_id",
    private var sessionIdCookieName: String = "fp_session_id",
    private var webviewUrl: String? = null,
    private val sessionTimeoutInMinutes: Int = 30,
    private val launchTimeoutMinutes: Int = 5) : DefaultLifecycleObserver {

    private val client = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)) // HTTP/2 preferred
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .header("User-Agent", getCustomUserAgent())
                .build()
            chain.proceed(newRequest)
        }
        .build()

    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

    private val globalEventData = mutableMapOf<String, Any>()
    private var isInitialized = false
    private val eventQueue = mutableListOf<Triple<String, EventType, Map<String, Any>>>()

    fun initialize() {
        Log.d("Tracker", "🔄 Initialisation started...")

        // Check whether it is the first launch and ensure that the event is only tracked on the first launch
        trackFirstInstall {
            isInitialized = true
            Log.d("Tracker", "✅ Tracker initialized!")

            // Process event queue if events are in the queue
            processQueuedEvents()

            // Start lifecycle tracking for launch events on the main thread
            Handler(Looper.getMainLooper()).post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(this)
            }
        }

    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d("Tracker", "📲 App is in Foreground")
        // Here we check whether the app was in the background for longer than the specified timeout time
        if (!isFirstLaunch()) {
            checkAndTrackLaunch() // Only execute if the timeout has been exceeded
        }
        //disable first launch flag
        val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_first_launch", false).apply()
        //set launch time
        val currentTime = System.currentTimeMillis()
        sharedPreferences.edit().putLong("last_launch_time", currentTime).apply() // Set the new launch time
    }

    private fun hasLaunchTimeoutPassed(): Boolean {
        val lastLaunchTime = sharedPreferences.getLong("last_launch_time", 0)
        val currentTime = System.currentTimeMillis()
        val launchTimeout = launchTimeoutMinutes * 60 * 1000L

        // Check whether the app was in the background for longer than the defined timeout
        return currentTime - lastLaunchTime > launchTimeout
    }

    private fun checkAndTrackLaunch() {
        val currentTime = System.currentTimeMillis()
        //Launch counter
        val launchCounter = sharedPreferences.getInt("launch_counter", 1)
        val newLaunchCounter = launchCounter + 1
        sharedPreferences.edit().putInt("launch_counter", newLaunchCounter).apply()
        //days since first use
        val installDate = getInstallDate()
        val daysSinceFirstUse = (currentTime - installDate) / (1000 * 60 * 60 * 24)
        //days since last use
        val lastLaunchTime = sharedPreferences.getLong("last_launch_time", 0L)
        val daysSinceLastUse = if (lastLaunchTime != 0L) {
            (currentTime - lastLaunchTime) / (1000 * 60 * 60 * 24)
        } else {
            0L // If there is no last launch, set 0
        }

        // If the app was in the background for longer than the launch timeout
        if (hasLaunchTimeoutPassed()) {
            Log.d("Tracker", "🚀 Launch-Event was tracked after timeout of $launchTimeoutMinutes minutes.")
            val launchData = mapOf(
                "launch" to mapOf(
                    "number" to newLaunchCounter,
                    "days_since_first_use" to daysSinceFirstUse,
                    "days_since_last_use" to daysSinceLastUse
                )
            )
            trackEvent("launch", EventType.LIFECYCLE, launchData)
            sharedPreferences.edit().putLong("last_launch_time", currentTime).apply() // Set the new launch time
        } else {
            Log.d("Tracker", "🕒 Launch event not tracked - timeout not exceeded.")
        }
    }

    private fun processQueuedEvents() {
        eventQueue.forEach { (name, type, data) -> trackEvent(name, type, data) }
        eventQueue.clear()
    }

    // Method for setting or updating global data
    fun setGlobalEventData(data: Map<String, Any>) {
        globalEventData.clear()
        globalEventData.putAll(data)
    }

    fun setGtmServerPreviewHeader(value: String?) {
        gtmServerPreviewHeader = value
    }

    fun setDeviceIdCookieName(name: String) {
        deviceIdCookieName = name
    }

    fun getDeviceIdCookieName(): String {
        return deviceIdCookieName
    }

    fun setSessionIdCookieName(name: String) {
        sessionIdCookieName = name
    }

    fun getSessionIdCookieName(): String {
        return sessionIdCookieName
    }

    fun setWebviewUrl(url: String) {
        webviewUrl = url
    }

    fun getWebviewUrl(): String {
        return webviewUrl?: ""
    }

    fun getRootDomain(url: String): String {
        return try {
            val uri = URI(url)
            val host = uri.host ?: return ""
            val domainName = InternetDomainName.from(host)
            val topPrivateDomain = domainName.topPrivateDomain().toString()
            ".$topPrivateDomain"
        } catch (e: Exception) {
            ""
        }
    }

    fun updateGlobalEventData(updatedData: Map<String, Any?>) {
        updatedData.forEach { (key, value) ->
            if (value == null) {
                globalEventData.remove(key)
            } else {
                globalEventData[key] = value
            }
        }
        Log.d("Tracker", "🌐 GlobalEventData after Update: $globalEventData")
    }

    //set your own user agent
    private fun getCustomUserAgent(): String {
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        val osVersion = android.os.Build.VERSION.RELEASE
        val deviceModel = android.os.Build.MODEL
        return "Json Tag Test App/$appVersion (Android $osVersion; $deviceModel)"
    }

    enum class EventType(val value: String) {
        VIEW("view"),
        LIFECYCLE("lifecycle"),
        CALLBACK("callback"),
        ERROR("error"),
        GENERIC_ACTION("generic action"),
        IMPRESSION("impression"),
        NON_INTERACTION("non interaction")
    }

    fun trackEvent(eventName: String, eventType: EventType, eventData: Map<String, Any>) {
        if (!isInitialized) {
            Log.d("Tracker", "⏳ Tracker not initialized - event is placed in queue: $eventName")
            eventQueue.add(Triple(eventName, eventType, eventData))
            return
        }

        val eventDataWithType = eventData.toMutableMap().apply {
            put("event_type", eventType.value) // Save enum value as string
        }

        sendEvent(eventName, eventDataWithType) {

        }
    }

    private fun sendEvent(eventName: String, eventData: Map<String, Any>, onComplete: () -> Unit){

        val deviceId = getDeviceId() // Get device ID
        val sessionId = getSessionId() // Ensure session ID (with timeout logic)

        val url = "$endpoint$path"

        // Combine local event data with global data
        val combinedData = globalEventData + eventData

        val json = JSONObject().apply {
            put("event_name", eventName)
            put("jsontag", "android app")
            //put("timestamp", System.currentTimeMillis())
            //add event data
            for ((key, value) in combinedData) {
                put(key, mapToJsonValue(value))
            }
        }

        Log.d("Tracker", "Request json: ${json}")

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        val cookies = mutableListOf<String>()

        if (deviceId.isNotEmpty()) {
            cookies += "$deviceIdCookieName=$deviceId"
        }

        if (sessionId.isNotEmpty()) {
            cookies += "$sessionIdCookieName=$sessionId"
        }

        if (cookies.isNotEmpty()) {
            val cookieHeader = cookies.joinToString("; ")
            requestBuilder.addHeader("Cookie", cookieHeader)
        }

        gtmServerPreviewHeader?.let {
            requestBuilder.addHeader("X-Gtm-Server-Preview", it)
        }

        val request = requestBuilder.build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e("Tracker", "Error while sending: ${e.message}")
                onComplete.invoke()
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful) {
                    response.body?.string()?.let { responseBody ->
                        Log.d("Tracker", "Response received: $responseBody")
                        extractDeviceIdFromResponse(responseBody)?.let { saveDeviceId(it) }
                        extractSessionIdFromResponse(responseBody)?.let { saveSessionId(it) }
                    }
                }
                onComplete.invoke()
            }
        })
    }

    private fun mapToJsonValue(value: Any): Any {
        return when (value) {
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                mapToJson(value as Map<String, Any>) // Convert recursively
            }
            is List<*> -> {
                val jsonArray = org.json.JSONArray()
                for (item in value) {
                    jsonArray.put(mapToJsonValue(item ?: JSONObject.NULL))
                }
                jsonArray
            }
            else -> value // Adopt primitive values unchanged
        }
    }

    private fun mapToJson(map: Map<String, Any>): JSONObject {
        val jsonObject = JSONObject()
        for ((key, value) in map) {
            when (value) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST") // Ensure that it really is a Map<String, Any>
                    jsonObject.put(key, mapToJson(value as Map<String, Any>))
                }
                is List<*> -> {
                    val jsonArray = org.json.JSONArray()
                    for (item in value) {
                        when (item) {
                            is Map<*, *> -> {
                                @Suppress("UNCHECKED_CAST")
                                jsonArray.put(mapToJson(item as Map<String, Any>))
                            }
                            else -> jsonArray.put(item)
                        }
                    }
                    jsonObject.put(key, jsonArray)
                }
                else -> jsonObject.put(key, value) // Transfer primitive values directly
            }
        }
        return jsonObject
    }

    private fun getInstallDate(): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime // Time in milliseconds since January 1, 1970
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
            0L
        }
    }

    private fun formatDate(timeMillis: Long): String {
        val date = Date(timeMillis)
        val formatter = SimpleDateFormat("M/d/yyyy", Locale.getDefault())
        return formatter.format(date)
    }

    // Checks whether it is the first start of the app
    private fun isFirstLaunch(): Boolean {
        val prefs: SharedPreferences = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("is_first_launch", true)
    }

    private fun trackFirstInstall(onComplete: () -> Unit) {
        if (isFirstLaunch()) {
            val launchData = mapOf(
                "launch" to mapOf(
                    "install_date" to formatDate(getInstallDate()),
                    "number" to 1
                )
            )

            sendEvent("first_launch", launchData) {
                onComplete() // Callback after successful sending
            }
        } else {
            onComplete()
        }
    }

    /** Extracts the device_id from the response */
    private fun extractDeviceIdFromResponse(response: String): String? {
        return try {
            val jsonResponse = JSONObject(response)
            jsonResponse.optString("device_id", "not set")
        } catch (e: Exception) {
            Log.e("Tracker", "Error while extracting the device_id: ${e.message}")
            null
        }
    }

    /** Extracts the session_id from the response */
    private fun extractSessionIdFromResponse(response: String): String? {
        return try {
            val jsonResponse = JSONObject(response)
            jsonResponse.optString("session_id", "not set")
        } catch (e: Exception) {
            Log.e("Tracker", "Error while extracting the session_id: ${e.message}")
            null
        }
    }

    /** Saves the device_id in SharedPreferences */
    private fun saveDeviceId(deviceId: String) {
        sharedPreferences.edit().putString("device_id", deviceId).apply()
    }

    /** Saves the session_id in SharedPreferences */
    private fun saveSessionId(sessionId: String) {
        sharedPreferences.edit().putString("session_id", sessionId).apply()
        sharedPreferences.edit().putLong("last_activity_time", System.currentTimeMillis()).apply()  // Update session activity
    }

    /** Retrieves the stored device_id */
    fun getDeviceId(): String {
        return sharedPreferences.getString("device_id", "") ?: ""
    }

    /** Ensures that the session_id is valid or resets it */
    fun getSessionId(): String {
        val lastActivityTime = sharedPreferences.getLong("last_activity_time", 0)
        val currentTime = System.currentTimeMillis()
        val sessionTimeout = sessionTimeoutInMinutes * 60 * 1000L // 30 minutes in milliseconds by default

        return if (currentTime - lastActivityTime > sessionTimeout) {
            // Session ID expired → set new one from the response
            val newSessionId = ""  // An empty session ID can be returned here if the response does not provide it
            sharedPreferences.edit().putLong("last_activity_time", currentTime).apply() // Update timestamp
            newSessionId
        } else {
            // Return existing session ID
            sharedPreferences.getString("session_id", "") ?: ""
        }
    }
}