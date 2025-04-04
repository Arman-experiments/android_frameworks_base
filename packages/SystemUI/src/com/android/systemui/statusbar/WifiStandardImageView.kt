/*
 * Copyright (C) 2023 The risingOS Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */
package com.android.systemui.statusbar

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.UserHandle
import android.provider.Settings
import android.provider.Settings.Secure.SHOW_WIFI_STANDARD_ICON
import android.util.AttributeSet
import android.util.Log
import android.widget.ImageView
import com.android.systemui.res.R

class WifiStandardImageView @JvmOverloads constructor(
    context: Context, 
    attrs: AttributeSet? = null, 
    defStyleAttr: Int = 0
) : ImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "WifiStandardImageView"
    }

    private val connectivityManager: ConnectivityManager by lazy { 
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager 
    }
    private val wifiManager: WifiManager by lazy { 
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager 
    }
    
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var contentObserver: ContentObserver? = null
    private var wifiStandardEnabled = false
    private var isRegistered = false

    init {
        setupContentObserver()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        showWifiStandard()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterNetworkCallback()
        contentObserver?.let {
            context.contentResolver.unregisterContentObserver(it)
            contentObserver = null
        }
    }

    private fun setupContentObserver() {
        val showWifiStandardIcon = Settings.Secure.getUriFor(SHOW_WIFI_STANDARD_ICON)
        contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                wifiStandardEnabled = Settings.Secure.getIntForUser(
                    context.contentResolver,
                    SHOW_WIFI_STANDARD_ICON,
                    0,
                    UserHandle.USER_CURRENT
                ) == 1
                if (wifiStandardEnabled) {
                    showWifiStandard()
                } else {
                    unregisterNetworkCallback()
                }
            }
        }
        contentObserver?.let {
            context.contentResolver.registerContentObserver(
                showWifiStandardIcon,
                false,
                it,
                UserHandle.USER_CURRENT
            )
        }
        contentObserver?.onChange(true)
    }

    private fun showWifiStandard() {
        if (!wifiStandardEnabled || isRegistered) return
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateWifiStandard(network)
            }

            override fun onUnavailable() {
                updateWifiStandard(null)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    updateWifiStandard(network)
                }
            }
        }
        registerNetworkCallback()
    }

    private fun updateWifiStandard(network: Network?) {
        val wifiStandard = if (network != null) getWifiStandard(network) else -1
        updateIcon(wifiStandard)
    }

    private fun getWifiStandard(network: Network): Int {
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return -1
        
        return if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            wifiManager.connectionInfo?.wifiStandard ?: -1
        } else {
            -1
        }
    }

    private fun updateIcon(wifiStandard: Int) {
        val drawableId = getDrawableForWifiStandard(wifiStandard)
        if (drawableId == 0) {
            post { if (isAttachedToWindow) visibility = GONE }
            return
        }
        post {
            if (isAttachedToWindow) {
                setImageResource(drawableId)
                visibility = VISIBLE
            }
        }
    }

    private fun getDrawableForWifiStandard(wifiStandard: Int): Int {
        return when (wifiStandard) {
            4 -> R.drawable.ic_wifi_standard_4
            5 -> R.drawable.ic_wifi_standard_5
            6 -> R.drawable.ic_wifi_standard_6
            7 -> R.drawable.ic_wifi_standard_7
            else -> 0
        }
    }

    private fun registerNetworkCallback() {
        if (isRegistered || networkCallback == null) return
        
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            networkCallback?.let {
                connectivityManager.registerNetworkCallback(networkRequest, it)
                isRegistered = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        if (!isRegistered || networkCallback == null) return
        
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            post { if (isAttachedToWindow) visibility = GONE }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to unregister network callback", e)
        } finally {
            networkCallback = null
            isRegistered = false
        }
    }
}
