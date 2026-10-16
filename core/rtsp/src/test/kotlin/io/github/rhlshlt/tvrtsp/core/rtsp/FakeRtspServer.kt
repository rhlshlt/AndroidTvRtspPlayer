package io.github.rhlshlt.tvrtsp.core.rtsp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A scripted RTSP camera on 127.0.0.1 for tests. It checks Digest answers with its own MD5 code
 * (independent of [HttpAuth]) and can replay a captured interleaved RTP stream after PLAY.
 */
class FakeRtspServer(
    private val sdp: String,
    private val user: String? = null,
    private val password: String? = null,
    private val scheme: String = "Digest",
    private val realm: String = "TP-LINK IP-Camera",
    private val nonce: String = "5f2a91c07d1e4b38",
    /** When true, only qop=auth answers are accepted and the challenge offers qop. */
    private val qopOnly: Boolean = false,
    private val streamPath: String = "/stream1",
    private val setupStatus: Int = 200,
    /** Interleaved bytes ('$' framed) sent after the PLAY answer; the connection then closes. */
    private val stream: ByteArray? = null,
    /** Accept the connection and never answer. */
    private val silent: Boolean = false,
    /** Sessions allowed at once; later SETUPs get 453, like a camera at its viewer limit. */
    private val viewerLimit: Int = Int.MAX_VALUE,
    /** Keep the connection open this long after the replayed stream (simulates a live stream). */
    private val holdAfterStreamMs: Long = 50,
) : Closeable {
    private val server = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val baseUrl: String get() = "rtsp://127.0.0.1:$port"

    /** "METHOD uri" for every request, in order. */
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Transport headers of SETUP requests. */
    val transports: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Authorization headers received. */
    val authorizations: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile
    var failure: Throwable? = null

    @Volatile
    private var closed = false

    private val viewers = java.util.concurrent.atomic.AtomicInteger()

    private val worker = thread(isDaemon = true, name = "fake-rtsp") {
        try {
            while (!closed) {
                val socket = server.accept()
                thread(isDaemon = true, name = "fake-rtsp-conn") {
                    var viewing = false
                    try {
                        socket.use { viewing = handle(it) }
                    } catch (_: SocketException) {
                    } catch (t: Throwable) {
                        failure = t
                    } finally {
                        if (viewing) viewers.decrementAndGet()
                    }
                }
            }
        } catch (_: SocketException) {
        } catch (t: Throwable) {
            failure = t
        }
    }

    /** Serves one connection; returns true if it still holds a viewer slot. */
    private fun handle(socket: Socket): Boolean {
        var viewing = false
        if (silent) {
            var waited = 0
            while (!closed && waited < 5_000) {
                Thread.sleep(25)
                waited += 25
            }
            return false
        }
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        while (true) {
            val requestLine = readLine(input) ?: return viewing
            if (requestLine.isEmpty()) continue
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return viewing
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            val (method, uri) = requestLine.split(' ').let { it[0] to it[1] }
            requests += "$method $uri"
            val cseq = headers["cseq"] ?: "0"

            if (user != null) {
                val auth = headers["authorization"]
                if (auth != null) authorizations += auth
                if (auth == null || !verify(auth, method, uri)) {
                    val challenge = if (scheme == "Basic") "Basic realm=\"$realm\""
                    else if (qopOnly) "Digest realm=\"$realm\", nonce=\"$nonce\", qop=\"auth\""
                    else "Digest realm=\"$realm\", nonce=\"$nonce\""
                    respond(out, 401, "Unauthorized", cseq, listOf("WWW-Authenticate" to challenge))
                    continue
                }
            }
            when (method) {
                "OPTIONS" -> respond(out, 200, "OK", cseq, listOf("Public" to "OPTIONS, DESCRIBE, SETUP, TEARDOWN, PLAY, GET_PARAMETER"))
                "DESCRIBE" -> {
                    if (!uri.endsWith(streamPath)) {
                        respond(out, 404, "Not Found", cseq)
                    } else {
                        respond(out, 200, "OK", cseq, listOf(
                            "Content-Base" to "$uri/",
                            "Content-Type" to "application/sdp",
                        ), sdp.toByteArray())
                    }
                }
                "SETUP" -> {
                    transports += headers["transport"] ?: ""
                    if (!viewing && viewers.incrementAndGet() > viewerLimit) {
                        viewers.decrementAndGet()
                        respond(out, 453, "Not Enough Bandwidth", cseq)
                    } else if (setupStatus != 200) {
                        if (!viewing) viewers.decrementAndGet()
                        respond(out, setupStatus, "Not Enough Bandwidth", cseq)
                    } else {
                        viewing = true
                        respond(out, 200, "OK", cseq, listOf(
                            "Transport" to (headers["transport"] ?: "") + ";ssrc=12345678",
                            "Session" to "47112344;timeout=60",
                        ))
                    }
                }
                "PLAY" -> {
                    respond(out, 200, "OK", cseq, listOf("Session" to "47112344", "Range" to "npt=0.000-"))
                    if (stream != null) {
                        out.write(stream)
                        out.flush()
                        Thread.sleep(holdAfterStreamMs)
                        return viewing // closing ends the stream
                    }
                }
                "TEARDOWN" -> {
                    respond(out, 200, "OK", cseq)
                    return viewing
                }
                else -> respond(out, 200, "OK", cseq)
            }
        }
    }

    private fun verify(header: String, method: String, uri: String): Boolean {
        if (scheme == "Basic") {
            return header == "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray())
        }
        if (!header.startsWith("Digest ")) return false
        val p = Regex("""(\w+)=(?:"((?:[^"\\]|\\.)*)"|([^,\s]+))""").findAll(header.substring(7))
            .associate { m -> m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3]) }
        if (p["username"] != user || p["realm"] != realm || p["nonce"] != nonce || p["uri"] != uri) return false
        val ha1 = md5("$user:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = p["qop"]
        if (qopOnly && qop != "auth") return false
        val expected = if (qop == "auth") md5("$ha1:$nonce:${p["nc"]}:${p["cnonce"]}:auth:$ha2") else md5("$ha1:$nonce:$ha2")
        return p["response"] == expected
    }

    private fun respond(out: OutputStream, code: Int, reason: String, cseq: String, headers: List<Pair<String, String>> = emptyList(), body: ByteArray? = null) {
        val sb = StringBuilder("RTSP/1.0 $code $reason\r\nCSeq: $cseq\r\nServer: FakeCam/1.0\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        if (body != null) sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (body != null) out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else String(buf.toByteArray())
            if (b == '\n'.code) return String(buf.toByteArray()).trimEnd('\r')
            buf.write(b)
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun close() {
        closed = true
        server.close()
        worker.join(2_000)
    }

    companion object {
        fun resource(name: String): ByteArray =
            FakeRtspServer::class.java.getResourceAsStream("/fixtures/$name")!!.use { it.readBytes() }

        /** SDP for the captured main360-pcma.rtp fixture (H.264 Main 640×360 15 fps + PCMA). */
        val MAIN360_SDP = """
            v=0
            o=- 14665860 31787219 1 IN IP4 127.0.0.1
            s=Session streamed by FakeCam
            t=0 0
            a=range:npt=0-
            m=video 0 RTP/AVP 96
            c=IN IP4 0.0.0.0
            a=control:track1
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1; profile-level-id=4D4016; sprop-parameter-sets=Z01AFtoCgL/lwEQAAAMABAAAAwB4PFi6gA==,aO8Dksg=
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000
            a=control:track2
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        /** SDP for the captured high360-bframes.rtp fixture (H.264 High 640×360 25 fps, B-frames). */
        val HIGH360_SDP = """
            v=0
            o=- 1 1 IN IP4 127.0.0.1
            s=FakeCam
            t=0 0
            m=video 0 RTP/AVP 96
            a=control:track1
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;profile-level-id=64001E;sprop-parameter-sets=Z2QAHqzZQKAv+XARAAADAAEAAAMAMg8WLZY=,aOvg5LIs
        """.trimIndent().replace("\n", "\r\n") + "\r\n"
    }
}
