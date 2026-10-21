package com.github.kr328.clash

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class UpdateReleaseRequestTest {
    private class FakeConnection(private val status: Int = 200, private val fails: Boolean = false) : HttpURLConnection(URL("https://example.invalid")) {
        var disconnected = false
        var inputRequested = false
        var streamClosed = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode(): Int {
            if (fails) throw IOException("network fixture")
            return status
        }
        override fun getInputStream(): InputStream {
            inputRequested = true
            return object : ByteArrayInputStream("fixture".toByteArray()) {
                override fun close() { streamClosed = true; super.close() }
            }
        }
    }
    @Test fun successReturnsDecodedValueAndClosesConnectionAndStream() {
        val connection = FakeConnection()
        assertEquals("fixture", UpdateReleaseRequest.read(connection) { it.bufferedReader().readText() })
        assertTrue(connection.disconnected)
        assertTrue(connection.streamClosed)
    }
    @Test fun httpErrorIsNotParsedAndDisconnects() {
        for (status in listOf(301, 403, 404, 429, 500)) {
            val connection = FakeConnection(status)
            assertThrows(IllegalStateException::class.java) { UpdateReleaseRequest.read(connection) { fail("must not parse an error"); "" } }
            assertFalse(connection.inputRequested)
            assertTrue(connection.disconnected)
        }
    }
    @Test fun parseFailureStillClosesStreamAndConnection() {
        val connection = FakeConnection()
        assertThrows(IllegalArgumentException::class.java) { UpdateReleaseRequest.read(connection) { throw IllegalArgumentException("bad JSON fixture") } }
        assertTrue(connection.streamClosed)
        assertTrue(connection.disconnected)
    }
    @Test fun networkFailureStillDisconnects() {
        val connection = FakeConnection(fails = true)
        assertThrows(IOException::class.java) { UpdateReleaseRequest.read(connection) { "" } }
        assertTrue(connection.disconnected)
    }
}
