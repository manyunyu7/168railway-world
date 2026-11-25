// SPDX-License-Identifier: AGPL-3.0-only
package world.nativegame

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.Authenticator
import okhttp3.Cache
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.util.concurrent.TimeUnit

/** Local executable assets and public, versioned data are the only routes of this origin. */
internal class StudioClockResources(private val context: Context, private val origin: Uri) {
    companion object {
        private const val PACKS = "/clock/packs/"
        private val safePath = Regex("[A-Za-z0-9_./-]+")
        private val rangeSpec = Regex("([0-9]+)-([0-9]+)")
        private val contentRange = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
        private val headers = mapOf(
            "Content-Security-Policy" to "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self' blob:; connect-src 'self' data: blob:; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; frame-src 'none'; object-src 'none'; base-uri 'none'",
            "Cross-Origin-Opener-Policy" to "same-origin",
            "Cross-Origin-Embedder-Policy" to "require-corp",
            "Cross-Origin-Resource-Policy" to "same-origin",
            "X-Content-Type-Options" to "nosniff",
        )

        @Volatile private var sharedClient: OkHttpClient? = null
        private fun publicClient(context: Context): OkHttpClient = sharedClient ?: synchronized(this) {
            sharedClient ?: OkHttpClient.Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .authenticator(Authenticator.NONE)
                .proxyAuthenticator(Authenticator.NONE)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .cache(Cache(File(context.cacheDir, "studio-clock-packs"), 128L * 1024 * 1024))
                .build().also { sharedClient = it }
        }
    }

    fun get(request: WebResourceRequest): WebResourceResponse {
        val uri = request.url
        val encoded = uri.encodedPath.orEmpty()
        val glyph = uri.path?.takeIf { Regex("/clock/fonts/Zen Maru Gothic Regular,Noto Sans Regular/[0-9]+-[0-9]+\\.pbf").matches(it) }
        val path = glyph ?: encoded
        if (request.method != "GET" || uri.scheme != origin.scheme || uri.encodedAuthority != origin.encodedAuthority ||
            !path.startsWith("/clock/") || (glyph == null && !safePath.matches(path)) ||
            path.split('/').any { it == "." || it == ".." }
        ) return error(403, "Forbidden")

        return try {
            if (path.startsWith("/clock/elevation/")) {
                val relative = path.removePrefix("/clock/")
                if (!Regex("elevation/[0-9]{1,2}/[0-9]+/[0-9]+\\.png").matches(relative)) return error(400,"Invalid tile")
                val tile = File(context.filesDir,"world-data/${CityPackStore.RELEASE}/reference/$relative")
                if (!tile.isFile) return error(404,"Elevation not cached")
                WebResourceResponse("image/png",null,200,"OK",headers,tile.inputStream())
            } else if (path.startsWith("/clock/roads/")) {
                val parts = path.removePrefix("/clock/roads/").removeSuffix(".pbf").split('/')
                val z = parts.getOrNull(0)?.toIntOrNull()
                val x = parts.getOrNull(1)?.toIntOrNull()
                val y = parts.getOrNull(2)?.toIntOrNull()
                if(parts.size!=3 || !path.endsWith(".pbf") || z==null || z !in 0..14 || x==null || y==null ||
                    x !in 0 until (1 shl z) || y !in 0 until (1 shl z)) return error(400,"Invalid tile")
                val tile=MapTile(z,x,y)
                val bytes=StudioMapTiles.local(context,tile) ?: runCatching { StudioMapTiles.fetch(context,tile) }.getOrNull()
                    ?: return error(404,"Basemap tile unavailable")
                WebResourceResponse("application/x-protobuf",null,200,"OK",headers,ByteArrayInputStream(bytes))
            } else if (path.startsWith(PACKS)) {
                // Chromium applies a real Range header again to intercepted WebResourceResponse streams.
                // The bundled worker uses a query so these bytes reach JavaScript exactly once.
                val query = uri.getQueryParameters("studioRange")
                val wire = request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value
                if (query.size > 1 || wire != null) return error(400, "Use studioRange")
                val range = query.firstOrNull()?.let { parseRange(it) ?: return error(400, "Invalid range") }
                data(path.removePrefix(PACKS), range)
            }
            else {
                val stream = context.assets.open("world-native" + path)
                WebResourceResponse(mime(path), null, 200, "OK", headers, stream)
            }
        } catch (_: FileNotFoundException) {
            error(404, "Not Found")
        } catch (_: Exception) {
            error(503, "Unavailable")
        }
    }

    private data class Range(val first: Long, val last: Long)

    private fun parseRange(spec: String): Range? {
        val fields = rangeSpec.matchEntire(spec)?.groupValues ?: return null
        val first = fields[1].toLongOrNull() ?: return null
        val last = fields[2].toLongOrNull() ?: return null
        if (last < first || last == Long.MAX_VALUE) return null
        return Range(first, last)
    }

    private fun data(relative: String, range: Range?): WebResourceResponse {
        if (relative.substringAfterLast('.') !in setOf("json", "geojson", "bin", "terrain", "save", "gz", "water", "rgr")) {
            return error(404, "Not Found")
        }
        val type = mime(relative)
        installed(relative)?.takeIf { it.isFile }?.let { file ->
            val size = file.length()
            if (range == null) return WebResourceResponse(type, null, 200, "OK",
                headers + ("Content-Length" to size.toString()), file.inputStream())
            if (range.last >= size) return unsatisfied(type, size)
            val source = file.inputStream()
            try {
                source.channel.position(range.first)
                return slice(type, source, range)
            } catch (e: Exception) { source.close(); throw e }
        }
        val req = Request.Builder().url(CityPackStore.ORIGIN + relative)
        if (range != null) req.header("Range", "bytes=${range.first}-${range.last}")
            .header("Accept-Encoding", "identity")
        val response = publicClient(context).newCall(req.build()).execute()
        try {
            val body = response.body
            if (response.code !in setOf(200, 206) || body == null) {
                response.close()
                return when (response.code) {
                    404 -> error(404, "Not Found")
                    416 -> error(416, "Range Not Satisfiable")
                    else -> error(503, "Unavailable")
                }
            }
            val length = body.contentLength()
            if (range == null) {
                check(response.code == 200)
                val reply = if (length >= 0) headers + ("Content-Length" to length.toString()) else headers
                return WebResourceResponse(type, null, 200, "OK", reply, body.byteStream())
            }
            if (response.code == 206) {
                val parts = contentRange.matchEntire(response.header("Content-Range").orEmpty())?.groupValues
                    ?: error("Missing upstream Content-Range")
                val start = parts[1].toLong()
                val end = parts[2].toLong()
                val size = parts[3].toLong()
                check(start == range.first && end >= start && end < size)
                check(end == range.last)
                check(length == -1L || length == end - start + 1)
                return slice(type, body.byteStream(), range)
            }
            // Some CDNs ignore Range and deliver the complete original file.
            if (length >= 0 && range.last >= length) {
                response.close()
                return unsatisfied(type, length)
            }
            body.source().skip(range.first)
            return slice(type, body.byteStream(), range)
        } catch (e: Exception) { response.close(); throw e }
    }

    private fun unsatisfied(type: String, size: Long) = WebResourceResponse(type, null, 416,
        "Range Not Satisfiable", headers + ("Content-Range" to "bytes */$size"), ByteArrayInputStream(ByteArray(0)))

    private fun slice(type: String, source: InputStream, range: Range): WebResourceResponse {
        val length = range.last - range.first + 1
        val stream = object : FilterInputStream(source) {
            private var remaining = length
            override fun read(): Int {
                if (remaining == 0L) return -1
                val result = super.read()
                if (result == -1) throw EOFException("Incomplete Studio pack range")
                remaining--
                return result
            }
            override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                if (count == 0) return 0
                if (remaining == 0L) return -1
                val n = super.read(bytes, offset, minOf(count.toLong(), remaining).toInt())
                if (n == -1) throw EOFException("Incomplete Studio pack range")
                remaining -= n
                return n
            }
            override fun skip(count: Long): Long {
                val skipped = super.skip(minOf(count, remaining))
                remaining -= skipped
                return skipped
            }
            override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
        }
        return WebResourceResponse(type, null, 200, "OK", headers + ("Content-Length" to length.toString()), stream)
    }

    private fun installed(path: String): File? {
        val root = File(context.filesDir, "world-data/" + CityPackStore.RELEASE)
        // Offline mirrors can retain Studio's original paths for global files and nearby zones.
        File(File(root, "reference"), path).takeIf { it.isFile }?.let { return it }
        if (path == "market-drives.bin") return File(root, "regional/drives.bin")
        val name = path.substringAfterLast('/')
        val city = name.substringBefore('.')
        val local = when (path) {
            "$city.json" -> "header.json"
            "$city.bin" -> "cells.bin"
            "$city.market.json" -> "market.json"
            "$city.places.json" -> "places.json"
            "terrain/$city.terrain" -> "zone.terrain"
            "real/$city.save" -> "network.save"
            else -> return null
        }
        return File(File(root, city), local)
    }

    private fun mime(path: String): String = when (path.substringAfterLast('.')) {
        "html" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "wasm" -> "application/wasm"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "woff2" -> "font/woff2"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "wav" -> "audio/wav"
        "json", "geojson" -> "application/json"
        else -> "application/octet-stream"
    }

    private fun error(code: Int, reason: String) =
        WebResourceResponse("text/plain", "utf-8", code, reason, headers, ByteArrayInputStream(ByteArray(0)))
}
