package com.photobooth.camera.ccapi

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import com.photobooth.core.PhotoboothLog.TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

data class CcapiEndpoint(val host: String, val port: Int)

/** Finds a CCAPI camera: saved host, camera-AP defaults, the Wi-Fi gateway and SSDP, probed in parallel. */
class CcapiDiscovery(private val context: Context) {

    suspend fun discover(savedHost: String?, savedPort: Int, overallTimeoutMs: Long = 3_000): CcapiEndpoint? {
        val candidates = linkedSetOf<String>()
        savedHost?.takeIf { it.isNotBlank() }?.let(candidates::add)
        gatewayAddress()?.let(candidates::add)
        candidates += DEFAULT_CAMERA_HOSTS
        val ports = linkedSetOf(savedPort, DEFAULT_PORT)

        return withTimeoutOrNull(overallTimeoutMs) {
            // Detached scope: blocking sockets cannot be interrupted, so do not wait for losers.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val found = CompletableDeferred<CcapiEndpoint?>()
                val pending = AtomicInteger(1)

                fun probeInBackground(host: String, port: Int) {
                    pending.incrementAndGet()
                    scope.launch {
                        if (probe(host, port)) found.complete(CcapiEndpoint(host, port))
                        if (pending.decrementAndGet() == 0) found.complete(null)
                    }
                }

                for (h in candidates) for (p in ports) probeInBackground(h, p)
                scope.launch {
                    for (host in ssdpSearch()) if (host !in candidates) probeInBackground(host, DEFAULT_PORT)
                    if (pending.decrementAndGet() == 0) found.complete(null)
                }
                found.await()
            } finally {
                scope.cancel()
            }
        }
    }

    private fun probe(host: String, port: Int): Boolean = try {
        CcapiClient(context, host, port).loadApiMap()
        true
    } catch (_: Exception) {
        false
    }

    private fun gatewayAddress(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val network = CcapiClient.findLocalNetwork(context) ?: return null
        val route = cm.getLinkProperties(network)?.routes?.firstOrNull { it.isDefaultRoute && it.gateway != null }
        return route?.gateway?.hostAddress
    }

    private fun ssdpSearch(timeoutMs: Int = 1_500): Set<String> {
        val hosts = linkedSetOf<String>()
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("photobooth-ssdp")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            DatagramSocket().use { socket ->
                CcapiClient.findLocalNetwork(context)?.bindSocket(socket)
                socket.soTimeout = 400
                val request = (
                    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                        "ST: urn:schemas-canon-com:service:ICPO-CameraControlAPIService:1\r\n\r\n"
                    ).toByteArray()
                socket.send(DatagramPacket(request, request.size, InetAddress.getByName("239.255.255.250"), 1900))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        packet.address.hostAddress?.let(hosts::add)
                    } catch (_: SocketTimeoutException) {
                        // keep listening until the deadline
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "CcapiDiscovery: SSDP skipped: ${e.message}")
        } finally {
            lock?.release()
        }
        return hosts
    }

    private companion object {
        const val DEFAULT_PORT = 8080
        val DEFAULT_CAMERA_HOSTS = listOf("192.168.1.2", "192.168.1.1")
    }
}
