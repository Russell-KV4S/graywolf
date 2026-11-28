package com.nw5w.graywolf.platformsvc

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

class BluetoothFacadeTest {
    @Test fun fakeFacade_returnsConfiguredBondedDevices() = runTest {
        val fake = FakeBluetoothFacade(bonded = listOf(
            BondedDevice("AA:BB:CC:00:00:01", "Mobilinkd TNC4"),
            BondedDevice("AA:BB:CC:00:00:02", "TH-D75"),
        ))
        val devices = fake.bondedDevices()
        assertEquals(2, devices.size)
        assertEquals("Mobilinkd TNC4", devices[0].name)
    }

    @Test fun systemFacade_isBondedMatchesCaseInsensitively() {
        val dev = mock<BluetoothDevice>()
        whenever(dev.address).thenReturn("AA:BB:CC:11:22:33")
        val adapter = mock<BluetoothAdapter>()
        whenever(adapter.bondedDevices).thenReturn(setOf(dev))

        val facade = SystemBluetoothFacade(adapter)
        assertTrue(facade.isBonded("aa:bb:cc:11:22:33"))
        assertTrue(facade.isBonded("AA:BB:CC:11:22:33"))
        assertFalse(facade.isBonded("AA:BB:CC:11:22:44"))
    }

    @Test fun connectWithFallback_returnsSecureSocketWhenConnectSucceeds() {
        val secure = mock<BluetoothSocket>()
        val insecure = mock<BluetoothSocket>()
        var insecureCreated = false

        val result = SystemBluetoothFacade.connectWithFallback(
            createSecure = { secure },
            createInsecure = {
                insecureCreated = true
                insecure
            },
        )

        assertSame(secure, result)
        assertFalse(insecureCreated)
        verify(secure).connect()
        verify(secure, never()).close()
    }

    @Test fun connectWithFallback_closesSecureAndRetriesInsecureOnIOException() {
        val secure = mock<BluetoothSocket>()
        doThrow(IOException("read failed, socket might closed or timeout")).whenever(secure).connect()
        val insecure = mock<BluetoothSocket>()

        val result = SystemBluetoothFacade.connectWithFallback(
            createSecure = { secure },
            createInsecure = { insecure },
        )

        assertSame(insecure, result)
        verify(secure).close()
        verify(insecure).connect()
        verify(insecure, never()).close()
    }

    @Test(expected = IOException::class)
    fun connectWithFallback_closesInsecureAndRethrowsWhenBothFail() {
        val secure = mock<BluetoothSocket>()
        doThrow(IOException("secure failed")).whenever(secure).connect()
        val insecure = mock<BluetoothSocket>()
        doThrow(IOException("insecure failed")).whenever(insecure).connect()

        try {
            SystemBluetoothFacade.connectWithFallback(
                createSecure = { secure },
                createInsecure = { insecure },
            )
        } finally {
            verify(secure).close()
            verify(insecure).close()
        }
    }
}
