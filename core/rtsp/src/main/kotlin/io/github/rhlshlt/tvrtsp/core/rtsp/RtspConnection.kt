package io.github.rhlshlt.tvrtsp.core.rtsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ProtocolException
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** RTSP headers in arrival order, looked up without regard to case. */
class RtspHeaders {
    private val entries = mutableListOf<Pair<String, String>>()

    fun add(name: String, value: String) {
        entries += name to value
    }

    /** The first value for [name], or null. */
    operator fun get(name: String): String? = entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    fun all(name: String): List<String> = entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    fun asList(): List<Pair<String, String>> = entries.toList()

    internal fun appendToLast(continuation: String) {
        if (entries.isEmpty()) return
        val (n, v) = entries.removeAt(entries.size - 1)
        entries += n to "$v ${continuation.trim()}"
    }
}

class RtspResponse(val statusCode: Int, val reason: String, val headers: RtspHeaders, val body: ByteArray) {
    val bodyText: String get() = String(body, Charsets.UTF_8)
    val isSuccess: Boolean get() = statusCode in 200..299
    override fun toString(): String = "RTSP/1.0 $statusCode $reason"
}

/** One `$`-framed packet from an interleaved (RTP over TCP) stream. */
class InterleavedFrame(val channel: Int, val data: ByteArray)

/** Resolves host names with a time limit; the JVM's lookup itself can't be interrupted. */
fun interface HostResolver {
    fun resolve(host: String, timeoutMs: Long): List<InetAddress>

    companion object {
        private val executor = Executors.newCachedThreadPool(ThreadFactory { r ->
            Thread(r, "rtsp-dns").apply { isDaemon = true }
        })

        val DEFAULT = HostResolver { host, timeoutMs ->
            val future = executor.submit<List<InetAddress>> { InetAddress.getAllByName(host).toList() }
            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)
                throw DnsTimeoutException(host, timeoutMs)
            } catch (e: ExecutionException) {
                throw (e.cause as? UnknownHostException) ?: UnknownHostException(host)
            }
        }
    }
}

class DnsTimeoutException(host: String, timeoutMs: Long) : UnknownHostException("DNS lookup for $host took longer than $timeoutMs ms")

/**
 * A blocking RTSP/1.0 control connection: sends requests with CSeq, User-Agent, Session and
 * Authorization headers, answers 401 challenges, and reads responses while skipping or delivering
 * interleaved RTP/RTCP frames. Not thread-safe; use it from one thread.
 */
class RtspConnection(
    private val socket: Socket,
    val url: RtspUrl,
    private val nanoTime: () -> Long = System::nanoTime,
) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream(), 64 * 1024)
    private val output = BufferedOutputStream(socket.getOutputStream(), 8 * 1024)
    private var cseq = 0

    var userAgent: String = DEFAULT_USER_AGENT
    var credentials: Credentials? = url.username?.let { Credentials(it, url.password ?: "") }

    /** Try `qop=auth` when the engine-style (RFC 2069) answer is refused and the camera offers qop. */
    var allowQopFallback: Boolean = true

    /** Called for every interleaved frame read while waiting for a response or in [readFrame]. */
    var interleavedSink: ((InterleavedFrame) -> Unit)? = null

    var sessionId: String? = null

    var authSession: AuthSession? = null
        private set

    /** `WWW-Authenticate` values from the first 401, kept for the engine compatibility check. */
    var firstChallengeHeaders: List<String> = emptyList()
        private set

    /** Which attempt authenticated, and how; empty when no authentication happened. */
    val authTrace: MutableList<String> = mutableListOf()

    /** True when the camera accepted the RFC 2069-style answer both engines send. */
    var acceptedLegacyDigest: Boolean? = null
        private set

    /** Time the last request was fully written, from [nanoTime]. */
    var lastRequestSentNanos: Long = 0L
        private set

    var readTimeoutMs: Int
        get() = socket.soTimeout
        set(value) {
            socket.soTimeout = value
        }

    fun request(
        method: String,
        uri: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
    ): RtspResponse {
        var response = send(method, uri, headers, body)
        var rounds = 0
        while (response.statusCode == 401 && rounds < 3) {
            val creds = credentials ?: return response
            rounds++
            val values = response.headers.all("WWW-Authenticate")
            if (firstChallengeHeaders.isEmpty()) firstChallengeHeaders = values
            val chosen = AuthSession.choose(WwwAuthenticate.parse(values)) ?: return response
            val current = authSession
            when {
                current == null -> {
                    authSession = AuthSession(chosen, creds, DigestStyle.LEGACY)
                    authTrace += describe(chosen, DigestStyle.LEGACY, "attempt 1")
                }
                chosen.stale -> {
                    authSession = AuthSession(chosen, creds, current.style)
                    authTrace += "Camera marked the nonce stale; answering the new nonce"
                }
                current.style == DigestStyle.LEGACY && chosen.isDigest && allowQopFallback && "auth" in chosen.qopOptions -> {
                    authSession = AuthSession(chosen, creds, DigestStyle.QOP_AUTH)
                    acceptedLegacyDigest = false
                    authTrace += describe(chosen, DigestStyle.QOP_AUTH, "attempt 2")
                }
                else -> {
                    if (current.challenge.isDigest && current.style == DigestStyle.LEGACY) acceptedLegacyDigest = false
                    return response
                }
            }
            response = send(method, uri, headers, body)
        }
        val session = authSession
        if (response.statusCode != 401 && session != null && session.challenge.isDigest && acceptedLegacyDigest == null) {
            acceptedLegacyDigest = session.style == DigestStyle.LEGACY
        }
        return response
    }

    private fun describe(c: AuthChallenge, style: DigestStyle, attempt: String): String =
        if (c.isDigest) {
            val alg = c.algorithm ?: "MD5"
            val qop = if (c.qopOptions.isEmpty()) "no qop offered" else "qop offered: ${c.qopOptions.joinToString(",")}"
            val how = if (style == DigestStyle.LEGACY) "RFC 2069 style, as both engines send" else "qop=auth"
            "$attempt: Digest $alg, realm \"${c.realm}\", $qop; answered $how"
        } else {
            "$attempt: Basic, realm \"${c.realm}\" (password is sent base64-encoded on every request)"
        }

    private fun send(method: String, uri: String, headers: List<Pair<String, String>>, body: ByteArray?): RtspResponse {
        cseq++
        val sb = StringBuilder(256)
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        sb.append("User-Agent: ").append(userAgent).append("\r\n")
        authSession?.let { sb.append("Authorization: ").append(it.authorization(method, uri)).append("\r\n") }
        sessionId?.let { sb.append("Session: ").append(it).append("\r\n") }
        for ((name, value) in headers) sb.append(name).append(": ").append(value).append("\r\n")
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body != null) output.write(body)
        output.flush()
        lastRequestSentNanos = nanoTime()
        return readResponse(cseq)
    }

    private fun readResponse(expectedCseq: Int): RtspResponse {
        while (true) {
            input.mark(1)
            val first = input.read()
            if (first < 0) throw EOFException("Camera closed the connection")
            if (first == '$'.code) {
                val frame = readFrameBody()
                interleavedSink?.invoke(frame)
                continue
            }
            input.reset()
            val startLine = readLine(input)
            if (startLine.isEmpty()) continue
            val headers = readHeaders(input)
            val body = readBody(input, headers)
            if (!startLine.startsWith("RTSP/")) continue // a request from the server; ignored
            val parts = startLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.toIntOrNull() ?: throw ProtocolException("Bad status line: $startLine")
            val responseCseq = headers["CSeq"]?.trim()?.toIntOrNull()
            if (responseCseq != null && responseCseq != expectedCseq) continue // late answer to an older request
            return RtspResponse(code, parts.getOrElse(2) { "" }, headers, body)
        }
    }

    /**
     * Reads the next interleaved frame. RTSP messages arriving in between (for example a keep-alive
     * answer) are consumed and dropped. Returns null at end of stream.
     */
    fun readFrame(): InterleavedFrame? {
        while (true) {
            input.mark(1)
            val first = input.read()
            if (first < 0) return null
            if (first == '$'.code) return readFrameBody().also { f -> interleavedSink?.invoke(f) }
            input.reset()
            val startLine = readLine(input)
            if (startLine.isEmpty()) continue
            val headers = readHeaders(input)
            readBody(input, headers)
        }
    }

    /** Sends a request without waiting for its answer (used for keep-alives while streaming). */
    fun sendWithoutWaiting(method: String, uri: String) {
        cseq++
        val sb = StringBuilder(160)
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        sb.append("CSeq: ").append(cseq).append("\r\n")
        sb.append("User-Agent: ").append(userAgent).append("\r\n")
        authSession?.let { sb.append("Authorization: ").append(it.authorization(method, uri)).append("\r\n") }
        sessionId?.let { sb.append("Session: ").append(it).append("\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun readFrameBody(): InterleavedFrame {
        val channel = input.read()
        val hi = input.read()
        val lo = input.read()
        if (channel < 0 || hi < 0 || lo < 0) throw EOFException("Camera closed the connection")
        val length = (hi shl 8) or lo
        return InterleavedFrame(channel, readExactly(input, length))
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "TvRtspPlayer-probe/0.1"
        private const val MAX_LINE = 16 * 1024
        private const val MAX_BODY = 1024 * 1024

        /**
         * Opens a TCP (or TLS for rtsps) connection to [url], trying each resolved address in turn.
         * Returns the connection and the address used.
         */
        fun connect(
            url: RtspUrl,
            addresses: List<InetAddress>,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
        ): Pair<Socket, InetAddress> {
            var last: Exception? = null
            for (address in addresses) {
                val socket = socketFactory.createSocket()
                try {
                    socket.connect(InetSocketAddress(address, url.port), connectTimeoutMs)
                    socket.tcpNoDelay = true
                    socket.soTimeout = readTimeoutMs
                    if (url.scheme == "rtsps") {
                        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                            .createSocket(socket, url.host, url.port, true) as SSLSocket
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.soTimeout = readTimeoutMs
                        tls.startHandshake()
                        return tls to address
                    }
                    return socket to address
                } catch (e: Exception) {
                    last = e
                    try {
                        socket.close()
                    } catch (_: Exception) {
                    }
                }
            }
            throw last ?: UnknownHostException(url.host)
        }

        internal fun readLine(input: InputStream): String {
            val buffer = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b < 0) {
                    if (buffer.size() == 0) throw EOFException("Camera closed the connection")
                    break
                }
                if (b == '\n'.code) break
                buffer.write(b)
                if (buffer.size() > MAX_LINE) throw ProtocolException("RTSP line longer than $MAX_LINE bytes")
            }
            return String(buffer.toByteArray(), Charsets.UTF_8).trimEnd('\r')
        }

        internal fun readHeaders(input: InputStream): RtspHeaders {
            val headers = RtspHeaders()
            var count = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) return headers
                if (++count > 200) throw ProtocolException("Too many RTSP headers")
                if (line[0] == ' ' || line[0] == '\t') {
                    headers.appendToLast(line)
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                headers.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
        }

        internal fun readBody(input: InputStream, headers: RtspHeaders): ByteArray {
            val length = headers["Content-Length"]?.trim()?.toIntOrNull() ?: return ByteArray(0)
            if (length < 0 || length > MAX_BODY) throw ProtocolException("Unexpected Content-Length $length")
            return readExactly(input, length)
        }

        internal fun readExactly(input: InputStream, length: Int): ByteArray {
            val data = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(data, read, length - read)
                if (n < 0) throw EOFException("Camera closed the connection")
                read += n
            }
            return data
        }

        /** Session id without parameters: `12345678;timeout=60` → `12345678`. */
        fun sessionIdOf(sessionHeader: String): String = sessionHeader.substringBefore(';').trim()

        /** The `timeout=` value of a Session header in seconds, or null. */
        fun sessionTimeoutOf(sessionHeader: String): Int? =
            sessionHeader.split(';').drop(1).map { it.trim() }
                .firstOrNull { it.startsWith("timeout=", ignoreCase = true) }
                ?.substringAfter('=')?.trim()?.toIntOrNull()
    }
}
