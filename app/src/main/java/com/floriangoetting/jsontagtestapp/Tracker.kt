package com.floriangoetting.jsontagtestapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.common.net.InternetDomainName
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URI

class Tracker(
    private val context: Context,
    private val endpoint: String,
    private val path: String,
    private var gtmServerPreviewHeader: String? = null,
    private var gtmServerPreviewHeaderWebviewCookieName: String? = null,
    private var deviceIdCookieName: String = "fp_device_id",
    private var sessionIdCookieName: String = "fp_session_id",
    private var webviewUrl: String? = null,
    private val sessionTimeoutInMinutes: Int = 30
) {

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

        isInitialized = true
        Log.d("Tracker", "✅ Tracker initialized!")

        // Process event queue if events are in the queue
        processQueuedEvents()
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

    fun getGtmServerPreviewHeader(): String? {
        return gtmServerPreviewHeader
    }

    fun setGtmServerPreviewHeaderWebviewCookieName(value: String?) {
        gtmServerPreviewHeaderWebviewCookieName = value
    }

    fun getGtmServerPreviewHeaderWebviewCookieName(): String? {
        return gtmServerPreviewHeaderWebviewCookieName
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

            // Optional: only add if not empty
            if (deviceId.isNotEmpty()) {
                put("client_id", deviceId)
            }
            if (sessionId.isNotEmpty()) {
                put("session_id", sessionId)
            }
        }

        Log.d("Tracker", "Request json: ${json}")

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

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