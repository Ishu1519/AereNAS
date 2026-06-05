package com.ishu1519.aerenas.server

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class WebDavServer(
    port: Int,
    private val rootPath: String,
    private val username: String,
    private val password: String,
    private val onTransferUpdate: (speed: Long, bytesTotal: Long) -> Unit,
    private val onConnectionEvent: (event: String) -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebDavServer"
        private val DATE_FORMAT = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        private val WEBDAV_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    private var totalBytesTransferred = 0L

    override fun serve(session: IHTTPSession): Response {
        // Basic auth check
        val authHeader = session.headers["authorization"]
        if (!isAuthorized(authHeader)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "text/plain", "Unauthorized"
            ).apply {
                addHeader("WWW-Authenticate", "Basic realm=\"AereNAS\"")
            }
        }

        val uri = session.uri.trimEnd('/')
        val file = File(rootPath + uri)
        val clientIp = session.headers["http-client-ip"] ?: session.headers["remote-addr"] ?: "unknown"

        Log.d(TAG, "${session.method} $uri from $clientIp")
        onConnectionEvent("${session.method} $uri [$clientIp]")

        return when (session.method) {
            Method.OPTIONS   -> handleOptions()
            Method.PROPFIND  -> handlePropfind(file, uri)
            Method.GET       -> handleGet(file)
            Method.PUT       -> handlePut(session, file)
            Method.DELETE    -> handleDelete(file)
            Method.MKCOL     -> handleMkcol(file)
            Method.MOVE      -> handleMove(session, file)
            Method.COPY      -> handleCopy(session, file)
            Method.HEAD      -> handleHead(file)
            Method.LOCK      -> handleLock(file)
            Method.UNLOCK    -> newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
            else             -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, "text/plain", "Not allowed")
        }
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    private fun isAuthorized(authHeader: String?): Boolean {
        if (authHeader == null || !authHeader.startsWith("Basic ")) return false
        val decoded = String(Base64.getDecoder().decode(authHeader.substring(6)))
        val parts = decoded.split(":", limit = 2)
        return parts.size == 2 && parts[0] == username && parts[1] == password
    }

    // ── OPTIONS ───────────────────────────────────────────────────────────────

    private fun handleOptions(): Response {
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "").apply {
            addHeader("Allow", "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK")
            addHeader("DAV", "1, 2")
            addHeader("MS-Author-Via", "DAV")
        }
    }

    // ── PROPFIND ──────────────────────────────────────────────────────────────

    private fun handlePropfind(file: File, uri: String): Response {
        if (!file.exists()) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        val depth = "1" // default
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        sb.append("<D:multistatus xmlns:D=\"DAV:\">")
        sb.append(buildPropEntry(file, uri))

        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                val childUri = if (uri.isEmpty() || uri == "/") "/${child.name}" else "$uri/${child.name}"
                sb.append(buildPropEntry(child, childUri))
            }
        }

        sb.append("</D:multistatus>")
        return newFixedLengthResponse(
            Response.Status.lookup(207) ?: Response.Status.OK
            "application/xml; charset=utf-8",
            sb.toString()
        ).apply {
            addHeader("DAV", "1, 2")
        }
    }

    private fun buildPropEntry(file: File, uri: String): String {
        val encodedUri = uri.replace(" ", "%20")
        val lastModified = DATE_FORMAT.format(Date(file.lastModified()))
        val creationDate = WEBDAV_DATE_FORMAT.format(Date(file.lastModified()))
        val isDir = file.isDirectory

        return buildString {
            append("<D:response>")
            append("<D:href>${encodedUri}${if (isDir && !encodedUri.endsWith("/")) "/" else ""}</D:href>")
            append("<D:propstat>")
            append("<D:prop>")
            append("<D:displayname>${file.name}</D:displayname>")
            append("<D:getlastmodified>$lastModified</D:getlastmodified>")
            append("<D:creationdate>$creationDate</D:creationdate>")
            if (isDir) {
                append("<D:resourcetype><D:collection/></D:resourcetype>")
            } else {
                append("<D:resourcetype/>")
                append("<D:getcontentlength>${file.length()}</D:getcontentlength>")
                append("<D:getcontenttype>${getMimeType(file.name)}</D:getcontenttype>")
            }
            append("<D:getetag>\"${file.lastModified()}-${file.length()}\"</D:getetag>")
            append("</D:prop>")
            append("<D:status>HTTP/1.1 200 OK</D:status>")
            append("</D:propstat>")
            append("</D:response>")
        }
    }

    // ── GET ───────────────────────────────────────────────────────────────────

    private fun handleGet(file: File): Response {
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        if (file.isDirectory) return handlePropfind(file, "")

        val startTime = System.currentTimeMillis()
        val fileSize = file.length()

        return try {
            val fis = FileInputStream(file)
            val trackingStream = object : FilterInputStream(fis) {
                var bytesRead = 0L
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        bytesRead += n
                        totalBytesTransferred += n
                        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                        val speed = bytesRead * 1000 / elapsed
                        onTransferUpdate(speed, totalBytesTransferred)
                    }
                    return n
                }
            }
            newChunkedResponse(Response.Status.OK, getMimeType(file.name), trackingStream).apply {
                addHeader("Content-Length", fileSize.toString())
                addHeader("Accept-Ranges", "bytes")
                addHeader("ETag", "\"${file.lastModified()}-${file.length()}\"")
            }
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    // ── HEAD ──────────────────────────────────────────────────────────────────

    private fun handleHead(file: File): Response {
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "")
        return newFixedLengthResponse(Response.Status.OK, getMimeType(file.name), "").apply {
            addHeader("Content-Length", file.length().toString())
            addHeader("Last-Modified", DATE_FORMAT.format(Date(file.lastModified())))
        }
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    private fun handlePut(session: IHTTPSession, file: File): Response {
        return try {
            file.parentFile?.mkdirs()
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
            val startTime = System.currentTimeMillis()

            FileOutputStream(file).use { fos ->
                val buffer = ByteArray(65536)
                var remaining = contentLength
                var bytesWritten = 0L

                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val n = session.inputStream.read(buffer, 0, toRead)
                    if (n < 0) break
                    fos.write(buffer, 0, n)
                    remaining -= n
                    bytesWritten += n
                    totalBytesTransferred += n
                    val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                    val speed = bytesWritten * 1000 / elapsed
                    onTransferUpdate(speed, totalBytesTransferred)
                }
            }
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } catch (e: IOException) {
            Log.e(TAG, "PUT failed", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    private fun handleDelete(file: File): Response {
        if (!file.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        return if (file.deleteRecursively()) {
            newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Delete failed")
        }
    }

    // ── MKCOL ─────────────────────────────────────────────────────────────────

    private fun handleMkcol(file: File): Response {
        if (file.exists()) return newFixedLengthResponse(Response.Status.lookup(405) ?: Response.Status.METHOD_NOT_ALLOWED"), "text/plain", "Already exists")
        return if (file.mkdirs()) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed to create")
        }
    }

    // ── MOVE ──────────────────────────────────────────────────────────────────

    private fun handleMove(session: IHTTPSession, source: File): Response {
        if (!source.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        val destHeader = session.headers["destination"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "text/plain", "No Destination header"
        )
        val destPath = extractPathFromDestination(destHeader)
        val dest = File(rootPath + destPath)
        dest.parentFile?.mkdirs()
        return if (source.renameTo(dest)) {
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } else {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Move failed")
        }
    }

    // ── COPY ──────────────────────────────────────────────────────────────────

    private fun handleCopy(session: IHTTPSession, source: File): Response {
        if (!source.exists()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        val destHeader = session.headers["destination"] ?: return newFixedLengthResponse(
            Response.Status.BAD_REQUEST, "text/plain", "No Destination header"
        )
        val destPath = extractPathFromDestination(destHeader)
        val dest = File(rootPath + destPath)
        dest.parentFile?.mkdirs()
        return try {
            source.copyRecursively(dest, overwrite = true)
            newFixedLengthResponse(Response.Status.CREATED, "text/plain", "")
        } catch (e: IOException) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message)
        }
    }

    // ── LOCK (stub — required for Windows WebDAV) ─────────────────────────────

    private fun handleLock(file: File): Response {
        val token = "opaquelocktoken:${UUID.randomUUID()}"
        val body = """<?xml version="1.0" encoding="utf-8"?>
<D:prop xmlns:D="DAV:">
  <D:lockdiscovery>
    <D:activelock>
      <D:locktype><D:write/></D:locktype>
      <D:lockscope><D:exclusive/></D:lockscope>
      <D:depth>0</D:depth>
      <D:timeout>Second-3600</D:timeout>
      <D:locktoken><D:href>$token</D:href></D:locktoken>
    </D:activelock>
  </D:lockdiscovery>
</D:prop>"""
        return newFixedLengthResponse(Response.Status.OK, "application/xml; charset=utf-8", body).apply {
            addHeader("Lock-Token", "<$token>")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun extractPathFromDestination(destHeader: String): String {
        return try {
            val url = java.net.URL(destHeader)
            url.path.removePrefix("/webdav").ifEmpty { "/" }
        } catch (e: Exception) {
            destHeader
        }
    }

    private fun getMimeType(filename: String): String {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "txt"  -> "text/plain"
            "html", "htm" -> "text/html"
            "css"  -> "text/css"
            "js"   -> "application/javascript"
            "json" -> "application/json"
            "xml"  -> "application/xml"
            "pdf"  -> "application/pdf"
            "zip"  -> "application/zip"
            "rar"  -> "application/x-rar-compressed"
            "jpg", "jpeg" -> "image/jpeg"
            "png"  -> "image/png"
            "gif"  -> "image/gif"
            "mp4"  -> "video/mp4"
            "mkv"  -> "video/x-matroska"
            "mp3"  -> "audio/mpeg"
            "wav"  -> "audio/wav"
            "apk"  -> "application/vnd.android.package-archive"
            else   -> "application/octet-stream"
        }
    }
}
