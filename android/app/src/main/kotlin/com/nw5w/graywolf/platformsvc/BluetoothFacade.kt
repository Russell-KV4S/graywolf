package com.nw5w.graywolf.platformsvc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID

/** Simple bonded-device record passed up to Go. */
data class BondedDevice(val mac: String, val name: String)

/** Thin interface around BluetoothAdapter to allow unit testing. */
interface BluetoothFacade {
    suspend fun bondedDevices(): List<BondedDevice>
    /** Throws on connect failure. MUST be called from a worker thread. */
    suspend fun connectRfcomm(mac: String): BluetoothSocket
    fun isBonded(mac: String): Boolean
}

/** Production implementation backed by the system BluetoothAdapter. */
class SystemBluetoothFacade(
    private val adapter: BluetoothAdapter?,
) : BluetoothFacade {
    companion object {
        private const val TAG = "SystemBluetoothFacade"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        internal fun connectWithFallback(
            createSecure: () -> BluetoothSocket,
            createInsecure: () -> BluetoothSocket,
            onFallback: (IOException) -> Unit = {},
        ): BluetoothSocket {
            val secure = createSecure()
            try {
                secure.connect() // blocking; caller MUST be on a worker thread
                return secure
            } catch (secureErr: IOException) {
                try { secure.close() } catch (_: Throwable) {}
                onFallback(secureErr)
                val insecure = createInsecure()
                try {
                    insecure.connect()
                    return insecure
                } catch (insecureErr: IOException) {
                    try { insecure.close() } catch (_: Throwable) {}
                    throw insecureErr
                }
            }
        }
    }

    override suspend fun bondedDevices(): List<BondedDevice> {
        val a = adapter ?: return emptyList()
        return a.bondedDevices.orEmpty().map {
            BondedDevice(mac = it.address, name = it.name ?: it.address)
        }
    }

    override fun isBonded(mac: String): Boolean {
        val a = adapter ?: return false
        return a.bondedDevices.orEmpty().any { it.address.equals(mac, ignoreCase = true) }
    }

    override suspend fun connectRfcomm(mac: String): BluetoothSocket {
        val a = adapter ?: error("Bluetooth not available on this device")
        val device: BluetoothDevice = a.getRemoteDevice(mac)
        return connectWithFallback(
            createSecure = { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            createInsecure = { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            onFallback = { err ->
                Log.i(TAG, "secure RFCOMM connect failed for $mac (${err.message}); retrying insecure SPP")
            },
        )
    }
}

/** Test double. */
class FakeBluetoothFacade(
    private val bonded: List<BondedDevice>,
) : BluetoothFacade {
    override suspend fun bondedDevices(): List<BondedDevice> = bonded
    override fun isBonded(mac: String): Boolean = bonded.any { it.mac == mac }
    override suspend fun connectRfcomm(mac: String): BluetoothSocket =
        error("FakeBluetoothFacade does not support real RFCOMM; override for integration tests")
}
