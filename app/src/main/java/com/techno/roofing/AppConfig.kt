package com.techno.roofing

import android.net.Uri

/**
 * Single place for everything deployment-specific about the wrapped web app.
 *
 * Point the wrapper at a different environment by changing [BASE_URL] and
 * [INTERNAL_HOSTS]; nothing else in the app needs to know the URL.
 */
object AppConfig {

    /** Page loaded when the app starts. */
    const val BASE_URL = "https://techno-roofing.pages.dev/"

    /**
     * Hosts that belong to the web app, including its backend. Subdomains are
     * matched, so "supabase.co" also covers "<project>.supabase.co".
     *
     * These mirror the site's own Content-Security-Policy, whose connect-src permits
     * 'self' plus *.supabase.co over https and wss. Treating Supabase as internal
     * keeps auth handoffs and password-reset links that pass through it inside the
     * app, instead of ejecting the user mid-flow into a browser that cannot
     * redirect back.
     */
    private val INTERNAL_HOSTS = setOf(
        "techno-roofing.pages.dev",
        "supabase.co"
    )

    /** Schemes the WebView is able to load at all. */
    private val WEB_SCHEMES = setOf("http", "https")

    /** True when [url] is a plain web address the WebView could load itself. */
    fun isWebScheme(url: Uri): Boolean = url.scheme?.lowercase() in WEB_SCHEMES

    /** True when [url] belongs to the web app or its backend and should load in-app. */
    fun isInternalUrl(url: Uri): Boolean {
        if (!isWebScheme(url)) return false
        val host = url.host?.lowercase() ?: return false
        return INTERNAL_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
