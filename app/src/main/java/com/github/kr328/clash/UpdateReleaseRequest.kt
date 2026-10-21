package com.github.kr328.clash

import java.io.InputStream
import java.net.HttpURLConnection

internal object UpdateReleaseRequest {
    fun <T> read(connection: HttpURLConnection, decode: (InputStream) -> T): T {
        try {
            val status = connection.responseCode
            check(status in 200..299) { "Release request failed: HTTP $status" }
            return connection.inputStream.use(decode)
        } finally {
            connection.disconnect()
        }
    }
}
