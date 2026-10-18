package io.github.rhlshlt.tvrtsp.core.rtsp

/**
 * A validated `rtsp://` or `rtsps://` URL with its credentials held apart from the address.
 *
 * Keeping credentials separate lets the address be shown and logged, and lets each engine receive
 * credentials the way it expects: Media3 reads them from the URI's user info, rtsp-client-android
 * takes them as separate arguments.
 *
 * [toString] never includes credentials, so an accidental log line can't leak a password.
 */
class RtspUrl private constructor(
    /** `rtsp` or `rtsps`, lower case. */
    val scheme: String,
    /** Host name or IP literal; IPv6 literals are stored without brackets. */
    val host: String,
    /** The port written in the URL, or null when the URL relies on the scheme default. */
    val explicitPort: Int?,
    /** Path plus optional query, exactly as written (already percent-encoded); at least "/". */
    val pathAndQuery: String,
    /** Decoded user name, or null when the URL carries no credentials. */
    val username: String?,
    /** Decoded password, or null when absent. */
    val password: String?,
) {
    /** The port a connection will use. */
    val port: Int get() = explicitPort ?: defaultPort(scheme)

    val hasCredentials: Boolean get() = username != null

    val isIpv6Literal: Boolean get() = host.contains(':')

    /** Host as it appears in a URL: IPv6 literals in brackets. */
    val hostForUrl: String get() = if (isIpv6Literal) "[$host]" else host

    /** The URL without credentials; this is the Request-URI and the Digest `uri` value. */
    val requestUri: String get() = build(includeCredentials = false)

    /**
     * The URL with percent-encoded credentials, for engines that read them from the URI (Media3).
     * Never log this value.
     */
    fun toUriWithCredentials(): String = build(includeCredentials = true)

    /** Display form: credentials replaced by `***`. */
    fun redacted(): String =
        if (hasCredentials) "$scheme://***@${authorityWithoutCredentials()}$pathAndQuery" else requestUri

    fun withCredentials(username: String?, password: String?): RtspUrl =
        RtspUrl(scheme, host, explicitPort, pathAndQuery, username?.takeIf { it.isNotEmpty() }, if (username.isNullOrEmpty()) null else password)

    fun withoutCredentials(): RtspUrl = RtspUrl(scheme, host, explicitPort, pathAndQuery, null, null)

    /** Same host, port and credentials with another path, for example `/stream2`. */
    fun withPath(path: String): RtspUrl {
        val p = if (path.startsWith("/")) path else "/$path"
        return parse(build(includeCredentials = false, overridePath = p)).withCredentials(username, password)
    }

    private fun authorityWithoutCredentials(): String =
        if (explicitPort != null) "$hostForUrl:$explicitPort" else hostForUrl

    private fun build(includeCredentials: Boolean, overridePath: String = pathAndQuery): String = buildString {
        append(scheme).append("://")
        if (includeCredentials && username != null) {
            // Always write "user:password": Media3 only authenticates when the user info has a ':'.
            append(PercentCodec.encode(username)).append(':').append(PercentCodec.encode(password ?: ""))
            append('@')
        }
        append(authorityWithoutCredentials())
        append(overridePath)
    }

    override fun toString(): String = redacted()

    override fun equals(other: Any?): Boolean =
        other is RtspUrl && scheme == other.scheme && host.equals(other.host, ignoreCase = true) &&
            port == other.port && pathAndQuery == other.pathAndQuery &&
            username == other.username && password == other.password

    override fun hashCode(): Int =
        listOf(scheme, host.lowercase(), port, pathAndQuery, username, password).hashCode()

    companion object {
        const val MAX_LENGTH = 2048
        const val DEFAULT_RTSP_PORT = 554
        const val DEFAULT_RTSPS_PORT = 322

        fun defaultPort(scheme: String): Int = if (scheme == "rtsps") DEFAULT_RTSPS_PORT else DEFAULT_RTSP_PORT

        /** Parses [input], throwing [RtspUrlException] with a specific [RtspUrlException.Reason]. */
        fun parse(input: String): RtspUrl {
            val text = input.trim()
            fail(text.isEmpty(), RtspUrlException.Reason.EMPTY, "Enter a URL")
            fail(text.length > MAX_LENGTH, RtspUrlException.Reason.TOO_LONG, "URL is longer than $MAX_LENGTH characters")
            fail(text.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }, RtspUrlException.Reason.WHITESPACE,
                "URL must not contain spaces or control characters")

            val schemeEnd = text.indexOf("://")
            fail(schemeEnd <= 0, RtspUrlException.Reason.SCHEME, "URL must start with rtsp:// or rtsps://")
            val scheme = text.substring(0, schemeEnd).lowercase()
            fail(scheme != "rtsp" && scheme != "rtsps", RtspUrlException.Reason.SCHEME,
                "Only rtsp:// and rtsps:// URLs are supported")

            val rest = text.substring(schemeEnd + 3)
            // Credentials end at the LAST '@', so raw '@', ':' or '/' inside a password still parse.
            // Consequence: a path containing '@' must percent-encode it (%40).
            val at = rest.lastIndexOf('@')
            val userInfo = if (at >= 0) rest.substring(0, at) else null
            val afterUserInfo = if (at >= 0) rest.substring(at + 1) else rest

            val authorityEnd = afterUserInfo.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) afterUserInfo.length else it }
            val authority = afterUserInfo.substring(0, authorityEnd)
            var pathAndQuery = afterUserInfo.substring(authorityEnd)
            fail(pathAndQuery.contains('#'), RtspUrlException.Reason.FRAGMENT,
                "URL must not contain '#'; if it is part of the password, use the password field")
            if (pathAndQuery.isEmpty()) pathAndQuery = "/"
            if (pathAndQuery.startsWith("?")) pathAndQuery = "/$pathAndQuery"

            val (host, port) = parseAuthority(authority)

            var username: String? = null
            var password: String? = null
            if (userInfo != null) {
                val colon = userInfo.indexOf(':')
                val rawUser = if (colon >= 0) userInfo.substring(0, colon) else userInfo
                val rawPass = if (colon >= 0) userInfo.substring(colon + 1) else null
                fail(rawUser.isEmpty(), RtspUrlException.Reason.USER_INFO, "User name before '@' is empty")
                username = PercentCodec.decode(rawUser)
                password = rawPass?.let { PercentCodec.decode(it) }
                fail(username.contains(':'), RtspUrlException.Reason.USER_INFO,
                    "User name must not contain ':'")
            }
            return RtspUrl(scheme, host, port, pathAndQuery, username, password)
        }

        /** Like [parse] but returns null instead of throwing. */
        fun parseOrNull(input: String): RtspUrl? = try {
            parse(input)
        } catch (_: RtspUrlException) {
            null
        }

        /**
         * Builds a URL from separate fields, as the add-camera form does. [host] may include a port,
         * as in `203.0.113.5:18554` or `[fe80::1]:8554`; a port written there wins over [port]. A bare
         * IPv6 literal such as `fe80::1` is an address without a port; add brackets to give it one.
         */
        fun of(
            host: String,
            path: String,
            port: Int? = null,
            username: String? = null,
            password: String? = null,
            scheme: String = "rtsp",
        ): RtspUrl {
            val (h, hostPort) = splitHostAndPort(host)
            val hostPart = if (h.contains(':')) "[$h]" else h
            val portPart = (hostPort ?: port)?.let { ":$it" } ?: ""
            val p = if (path.startsWith("/")) path else "/$path"
            return parse("$scheme://$hostPart$portPart$p").withCredentials(username, password)
        }

        /**
         * Splits an address typed into a form into host and optional port: `cam.lan`, `cam.lan:8554`,
         * `192.168.1.20:554`, `[2001:db8::5]:8554`, or a bare IPv6 literal (never a port). The host is
         * checked later, when the URL is parsed.
         */
        fun splitHostAndPort(input: String): Pair<String, Int?> {
            val text = input.trim()
            fail(text.isEmpty(), RtspUrlException.Reason.HOST, "Enter the camera's address")
            fail(text.contains("://"), RtspUrlException.Reason.HOST,
                "Enter only the address, for example 192.168.1.20 or 203.0.113.5:18554; full rtsp:// URLs go under Any RTSP URL")
            fail(text.any { it == '/' || it == '@' || it == '?' || it == '#' || it.isWhitespace() }, RtspUrlException.Reason.HOST,
                "Enter only the address, for example 192.168.1.20 or 203.0.113.5:18554")
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                fail(close < 0, RtspUrlException.Reason.HOST, "IPv6 address is missing ']'")
                val after = text.substring(close + 1)
                fail(after.isNotEmpty() && !after.startsWith(":"), RtspUrlException.Reason.HOST,
                    "Unexpected text after the IPv6 address")
                return text.substring(1, close) to after.takeIf { it.startsWith(":") }?.let { parsePort(it.substring(1)) }
            }
            val colon = text.indexOf(':')
            return when {
                colon < 0 -> text to null
                text.indexOf(':', colon + 1) < 0 -> text.substring(0, colon) to parsePort(text.substring(colon + 1))
                else -> text to null // two or more colons: an IPv6 literal without a port
            }
        }

        private fun parseAuthority(authority: String): Pair<String, Int?> {
            fail(authority.isEmpty(), RtspUrlException.Reason.HOST, "Host is missing")
            val host: String
            val portText: String?
            if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                fail(close < 0, RtspUrlException.Reason.HOST, "IPv6 address is missing ']'")
                host = authority.substring(1, close)
                val after = authority.substring(close + 1)
                fail(after.isNotEmpty() && !after.startsWith(":"), RtspUrlException.Reason.HOST,
                    "Unexpected text after the IPv6 address")
                portText = if (after.startsWith(":")) after.substring(1) else null
                fail(!isValidIpv6(host), RtspUrlException.Reason.HOST, "Invalid IPv6 address")
            } else {
                val colon = authority.indexOf(':')
                fail(colon >= 0 && authority.indexOf(':', colon + 1) >= 0, RtspUrlException.Reason.HOST,
                    "IPv6 addresses must be written in brackets, for example [fe80::1]")
                host = if (colon >= 0) authority.substring(0, colon) else authority
                portText = if (colon >= 0) authority.substring(colon + 1) else null
                fail(!isValidHostName(host), RtspUrlException.Reason.HOST, "Invalid host name \"$host\"")
            }
            return host to portText?.let(::parsePort)
        }

        private fun parsePort(text: String): Int {
            fail(text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }, RtspUrlException.Reason.PORT,
                "Port must be a number from 1 to 65535")
            return text.toInt().also { fail(it !in 1..65535, RtspUrlException.Reason.PORT, "Port must be from 1 to 65535") }
        }

        private fun isValidHostName(host: String): Boolean {
            if (host.isEmpty() || host.length > 253) return false
            if (host.startsWith(".") || host.startsWith("-")) return false
            return host.all { it.isLetterOrDigit() && it.code < 0x80 || it == '-' || it == '.' || it == '_' }
        }

        /**
         * RFC 4291 text form: eight groups of 1–4 hex digits, one `::` for a run of zero groups, an
         * IPv4 address in the last 32 bits, and an optional zone after `%` (RFC 6874 writes `%25`).
         */
        internal fun isValidIpv6(host: String): Boolean {
            val zoneAt = host.indexOf('%')
            if (zoneAt == host.length - 1) return false
            val address = if (zoneAt >= 0) host.substring(0, zoneAt) else host
            if (!address.contains(':')) return false
            val gap = address.indexOf("::")
            if (gap >= 0 && address.indexOf("::", gap + 1) >= 0) return false
            val halves = if (gap >= 0) listOf(address.substring(0, gap), address.substring(gap + 2)) else listOf(address)
            var groups = 0
            halves.forEachIndexed { h, half ->
                if (half.isEmpty()) return@forEachIndexed
                val parts = half.split(':')
                parts.forEachIndexed { i, part ->
                    val last = h == halves.lastIndex && i == parts.lastIndex
                    if (last && part.contains('.')) {
                        if (!isValidIpv4(part)) return false
                        groups += 2
                    } else {
                        if (part.isEmpty() || part.length > 4 || !part.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
                        groups += 1
                    }
                }
            }
            return if (gap >= 0) groups <= 7 else groups == 8
        }

        private fun isValidIpv4(text: String): Boolean {
            val parts = text.split('.')
            return parts.size == 4 && parts.all { p ->
                p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255
            }
        }

        private fun fail(condition: Boolean, reason: RtspUrlException.Reason, message: String) {
            if (condition) throw RtspUrlException(reason, message)
        }
    }
}

class RtspUrlException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { EMPTY, TOO_LONG, WHITESPACE, SCHEME, HOST, PORT, USER_INFO, FRAGMENT }
}

/**
 * RFC 3986 percent-encoding for URL user info. Everything except unreserved characters is encoded,
 * so '@', ':' and '/' in a password can't change how the URL splits.
 */
object PercentCodec {
    private const val HEX = "0123456789ABCDEF"

    fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    fun encode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x80 && isUnreserved(v.toChar())) {
                out.append(v.toChar())
            } else {
                out.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
            }
        }
        return out.toString()
    }

    /** Decodes `%XX` as UTF-8. A '%' that doesn't start a valid escape is kept as a literal '%'. */
    fun decode(value: String): String {
        if (value.indexOf('%') < 0) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            val hi = if (c == '%' && i + 2 < value.length) hexValue(value[i + 1]) else -1
            val lo = if (hi >= 0) hexValue(value[i + 2]) else -1
            if (hi >= 0 && lo >= 0) {
                out.write(hi * 16 + lo)
                i += 3
            } else {
                val cp = value.codePointAt(i)
                val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i += Character.charCount(cp)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
