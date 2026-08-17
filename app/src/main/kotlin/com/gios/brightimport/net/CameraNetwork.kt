package com.gios.brightimport.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The camera's own access point, and the one thing that makes talking to it work at all.
 *
 * A camera Wi-Fi network has no internet. Android knows that, keeps mobile data as the default
 * route, and sends every socket this app opens out over LTE — where 192.168.0.1 is somebody
 * else's router or nothing at all. The symptom is a camera that pairs and then refuses to
 * transfer, and it is the single most reported bug in every app that talks to a camera.
 *
 * [bindProcessToNetwork] is the fix: after it, sockets opened by this process go to the camera
 * whatever the phone would rather do.
 *
 * Using a network *specifier* rather than picking the network out of a scan is deliberate. It
 * puts the choice in a system dialog, and it means this app never asks for location permission —
 * which is what Fuji's own app needs, and the loudest complaint about it.
 */
class CameraNetwork(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var bound: Network? = null

    /**
     * Ask the system to join a camera access point and route this process to it.
     *
     * @param ssidPrefix matched as a prefix, so the user picks their body from the dialog without
     *   us having to know its serial-numbered SSID.
     * @param passphrase WPA2 key shown on the camera, or null for an open network.
     */
    suspend fun connect(
        ssidPrefix: String,
        passphrase: String?,
        timeoutMs: Long = 60_000,
    ): Network = suspendCancellableCoroutine { cont ->
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(ssidPrefix, PatternMatcher.PATTERN_PREFIX))
            .apply { if (!passphrase.isNullOrEmpty()) setWpa2Passphrase(passphrase) }
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // Without removing this, the request waits forever for a network that can reach the
            // internet. A camera never will.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "joined camera network")
                cm.bindProcessToNetwork(network)
                bound = network
                if (cont.isActive) cont.resume(network)
            }

            override fun onUnavailable() {
                if (cont.isActive) {
                    cont.resumeWithException(
                        IllegalStateException("no camera network was joined")
                    )
                }
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "camera network lost")
                if (bound == network) {
                    cm.bindProcessToNetwork(null)
                    bound = null
                }
                onDropped()
            }
        }
        callback = cb
        cm.requestNetwork(request, cb, timeoutMs.toInt())

        cont.invokeOnCancellation { release() }
    }

    /** Called when the camera network goes away mid-session — it sleeps aggressively. */
    var onDropped: () -> Unit = {}

    /**
     * Hand the phone back to whatever it was doing.
     *
     * Not optional. A process left bound to a dead camera network has no working network at all,
     * and the effect outlives the screen the user was on.
     */
    fun release() {
        runCatching { cm.bindProcessToNetwork(null) }
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
        bound = null
    }

    companion object {
        private const val TAG = "CameraNetwork"

        /** SSID prefixes each body advertises, for the picker. */
        const val SSID_FUJI = "FUJIFILM"
        const val SSID_RICOH = "RICOH"
        const val SSID_SONY = "DIRECT-"
    }
}
