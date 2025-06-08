package com.floriangoetting.jsontagtestapp.ui.webview

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.floriangoetting.jsontagtestapp.MainActivity
import com.floriangoetting.jsontagtestapp.base.BaseFragment
import com.floriangoetting.jsontagtestapp.databinding.FragmentWebviewBinding

class WebviewFragment : BaseFragment() {

    private var _binding: FragmentWebviewBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWebviewBinding.inflate(inflater, container, false)
        val root: View = binding.root

        // Access to MainActivity
        val mainActivity = requireActivity() as? MainActivity

        // Initialize and configure WebView
        val webView: WebView = binding.webView

        // WebView settings
        val webSettings: WebSettings = webView.settings
        webSettings.domStorageEnabled = true
        webSettings.javaScriptEnabled = true

        val tracker = mainActivity?.tracker
        val deviceId = tracker?.getDeviceId() ?: ""
        val deviceIdCookieName = tracker?.getDeviceIdCookieName()
        val sessionId = tracker?.getSessionId() ?: ""
        val sessionIdCookieName = tracker?.getSessionIdCookieName()

        val webviewUrl = tracker?.getWebviewUrl().takeIf { !it.isNullOrBlank() } ?: "https://example.com"
        val cookieDomain = tracker?.getRootDomain(webviewUrl)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        if (deviceId.isNotEmpty()) {
            val deviceIdCookie = "$deviceIdCookieName=$deviceId; path=/; domain=$cookieDomain"
            cookieManager.setCookie(webviewUrl, deviceIdCookie)
        }

        if (sessionId.isNotEmpty()) {
            val sessionIdCookie = "$sessionIdCookieName=$sessionId; path=/; domain=$cookieDomain"
            cookieManager.setCookie(webviewUrl, sessionIdCookie)
        }

        cookieManager.flush()

        webView.webViewClient = WebViewClient()
        webView.loadUrl(webviewUrl)

        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}