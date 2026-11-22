package se.pontus.glimt

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

const val USER_AGENT = "Glimt/1.0"

object Net {
    /** Öppnar en anslutning och följer omdirigeringar, även http <-> https. */
    fun open(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        readTimeoutMs: Int = 30_000,
    ): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null) throw IOException("Omdirigering utan adress")
                current = URL(current, location)
                return@repeat
            }
            if (code !in 200..299) {
                val err = c.errorStream?.use { it.readBytes().decodeToString().take(300) }.orEmpty()
                c.disconnect()
                throw IOException("HTTP $code från ${current.host} $err".trim())
            }
            return c
        }
        throw IOException("För många omdirigeringar")
    }

    fun bytes(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
    ): ByteArray {
        val c = open(url, method, headers, body)
        try {
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
