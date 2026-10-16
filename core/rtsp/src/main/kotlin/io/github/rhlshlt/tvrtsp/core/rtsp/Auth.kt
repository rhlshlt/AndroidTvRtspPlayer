package io.github.rhlshlt.tvrtsp.core.rtsp

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A camera account. [toString] hides both values. */
class Credentials(val username: String, val password: String) {
    override fun toString(): String = "Credentials(***)"
}

/** One challenge from a `WWW-Authenticate` header, for example `Digest realm="x", nonce="y"`. */
class AuthChallenge(
    val scheme: String,
    /** Parameters with lower-case names and unquoted values. */
    val params: Map<String, String>,
    /** The challenge text as received. */
    val raw: String,
) {
    val isDigest: Boolean get() = scheme.equals("Digest", ignoreCase = true)
    val isBasic: Boolean get() = scheme.equals("Basic", ignoreCase = true)
    val realm: String? get() = params["realm"]
    val nonce: String? get() = params["nonce"]
    val opaque: String? get() = params["opaque"]
    val algorithm: String? get() = params["algorithm"]
    val stale: Boolean get() = params["stale"].equals("true", ignoreCase = true)

    /** The `qop` options the server offers, lower case; empty when it offers none (RFC 2069 style). */
    val qopOptions: List<String>
        get() = params["qop"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }.orEmpty()

    override fun toString(): String = raw
}

/** Parses `WWW-Authenticate` header values (RFC 7235), including several challenges in one value. */
object WwwAuthenticate {

    fun parse(headerValues: List<String>): List<AuthChallenge> = headerValues.flatMap { parse(it) }

    fun parse(value: String): List<AuthChallenge> {
        val challenges = mutableListOf<AuthChallenge>()
        var scheme: String? = null
        var schemeStart = 0
        var lastEnd = 0
        var params = LinkedHashMap<String, String>()
        var i = 0

        fun finish() {
            val s = scheme ?: return
            challenges += AuthChallenge(s, params, value.substring(schemeStart, lastEnd).trim().trimEnd(','))
        }

        while (i < value.length) {
            while (i < value.length && (value[i] == ' ' || value[i] == '\t' || value[i] == ',')) i++
            if (i >= value.length) break
            val tokenStart = i
            while (i < value.length && isTokenChar(value[i])) i++
            if (i == tokenStart) { i++; continue } // stray character
            val token = value.substring(tokenStart, i)
            var j = i
            while (j < value.length && (value[j] == ' ' || value[j] == '\t')) j++
            if (scheme != null && j < value.length && value[j] == '=') {
                i = j + 1
                while (i < value.length && (value[i] == ' ' || value[i] == '\t')) i++
                val paramValue: String
                if (i < value.length && value[i] == '"') {
                    val sb = StringBuilder()
                    i++
                    while (i < value.length && value[i] != '"') {
                        if (value[i] == '\\' && i + 1 < value.length) i++
                        sb.append(value[i])
                        i++
                    }
                    i++ // closing quote
                    paramValue = sb.toString()
                } else {
                    val vStart = i
                    while (i < value.length && value[i] != ',' && value[i] != ' ' && value[i] != '\t') i++
                    paramValue = value.substring(vStart, i)
                }
                params[token.lowercase()] = paramValue
                lastEnd = minOf(i, value.length)
            } else {
                finish()
                scheme = token
                schemeStart = tokenStart
                params = LinkedHashMap()
                lastEnd = i
            }
        }
        finish()
        return challenges
    }

    private fun isTokenChar(c: Char): Boolean =
        c.isLetterOrDigit() && c.code < 0x80 || c in "!#$%&'*+-.^_`|~"
}

/**
 * How a Digest response is formed.
 *
 * [LEGACY] is RFC 2069 style (no qop), which is what Media3 1.11.1 and rtsp-client-android 6.1.1 send.
 * [QOP_AUTH] is RFC 2617/7616 style with `qop=auth`, `nc` and `cnonce`.
 */
enum class DigestStyle { LEGACY, QOP_AUTH }

/** Digest (RFC 2069/2617/7616) and Basic (RFC 7617) authorization values. */
object HttpAuth {
    private val random = SecureRandom()

    /** Digest algorithms this probe can answer; Media3 and rtsp-client-android only answer MD5. */
    fun isSupportedDigestAlgorithm(algorithm: String?): Boolean = hashName(algorithm) != null

    fun basic(credentials: Credentials): String =
        "Basic " + Base64.getEncoder().encodeToString("${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8))

    /**
     * Builds an `Authorization: Digest ...` value.
     *
     * @param nc request counter, used only with [DigestStyle.QOP_AUTH]
     * @param cnonce client nonce, used only with [DigestStyle.QOP_AUTH] (random when null)
     */
    fun digest(
        challenge: AuthChallenge,
        credentials: Credentials,
        method: String,
        uri: String,
        style: DigestStyle,
        nc: Int = 1,
        cnonce: String? = null,
    ): String {
        val realm = challenge.realm ?: ""
        val nonce = challenge.nonce ?: throw IllegalArgumentException("Digest challenge has no nonce")
        val algorithm = challenge.algorithm
        val hashName = hashName(algorithm) ?: throw IllegalArgumentException("Unsupported Digest algorithm $algorithm")
        val sess = algorithm?.endsWith("-sess", ignoreCase = true) == true
        val useQop = style == DigestStyle.QOP_AUTH
        val cn = cnonce ?: newCnonce()
        val ncText = String.format(java.util.Locale.ROOT, "%08x", nc)

        fun h(s: String): String = hex(MessageDigest.getInstance(hashName).digest(s.toByteArray(Charsets.UTF_8)))

        var ha1 = h("${credentials.username}:$realm:${credentials.password}")
        if (sess) ha1 = h("$ha1:$nonce:$cn")
        val ha2 = h("$method:$uri")
        val response = if (useQop) h("$ha1:$nonce:$ncText:$cn:auth:$ha2") else h("$ha1:$nonce:$ha2")

        return buildString {
            append("Digest username=").append(quote(credentials.username))
            append(", realm=").append(quote(realm))
            append(", nonce=").append(quote(nonce))
            append(", uri=").append(quote(uri))
            append(", response=").append(quote(response))
            if (algorithm != null) append(", algorithm=").append(algorithm)
            challenge.opaque?.let { append(", opaque=").append(quote(it)) }
            if (useQop) append(", qop=auth, nc=").append(ncText).append(", cnonce=").append(quote(cn))
        }
    }

    fun newCnonce(): String = ByteArray(8).also { random.nextBytes(it) }.let(::hex)

    private fun hashName(algorithm: String?): String? = when (algorithm?.uppercase()) {
        null, "MD5", "MD5-SESS" -> "MD5"
        "SHA-256", "SHA-256-SESS" -> "SHA-256"
        else -> null
    }

    private fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    internal fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v shr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }
}

/** Answers challenges for one RTSP session, reusing the nonce and counting `nc` like a real client. */
class AuthSession(
    val challenge: AuthChallenge,
    private val credentials: Credentials,
    style: DigestStyle,
    private val cnonce: String = HttpAuth.newCnonce(),
) {
    var style: DigestStyle = style
        private set
    private var nc = 0

    val schemeName: String get() = if (challenge.isDigest) "Digest" else "Basic"

    fun upgradeToQop(): Boolean {
        if (!challenge.isDigest || style == DigestStyle.QOP_AUTH || "auth" !in challenge.qopOptions) return false
        style = DigestStyle.QOP_AUTH
        nc = 0
        return true
    }

    fun authorization(method: String, uri: String): String =
        if (challenge.isDigest) {
            nc++
            HttpAuth.digest(challenge, credentials, method, uri, style, nc, cnonce)
        } else {
            HttpAuth.basic(credentials)
        }

    companion object {
        /** Picks the challenge to answer: Digest over Basic, as both engines do. */
        fun choose(challenges: List<AuthChallenge>): AuthChallenge? =
            challenges.firstOrNull { it.isDigest && it.nonce != null && HttpAuth.isSupportedDigestAlgorithm(it.algorithm) }
                ?: challenges.firstOrNull { it.isBasic }
    }
}

/** The player engines Phase 0 compares. */
enum class PlayerEngine(val label: String) {
    MEDIA3("Media3 1.11.1"),
    RTSP_CLIENT_ANDROID("rtsp-client-android 6.1.1"),
}

/** Whether an engine can answer a camera's authentication challenge. */
data class EngineAuthVerdict(val engine: PlayerEngine, val level: Level, val scheme: String?, val detail: String) {
    enum class Level { OK, RISK, FAILS }
}

/**
 * Re-creates how each engine reads `WWW-Authenticate`, so the probe can predict authentication
 * failures before playback. Both engines answer Digest with MD5 in RFC 2069 style (no qop).
 */
object EngineAuthCompat {
    // Copied from Media3 1.11.1 RtspMessageUtil: Digest uses find(), Basic uses matches().
    private val MEDIA3_DIGEST = Regex(
        """Digest realm="([^"\x00-\x08\x0A-\x1f\x7f]+)",\s?(?:domain="(.+)",\s?)?nonce="([^"\x00-\x08\x0A-\x1f\x7f]+)"(?:,\s?opaque="([^"\x00-\x08\x0A-\x1f\x7f]+)")?""",
    )
    private val MEDIA3_BASIC = Regex("""Basic realm="([^"\x00-\x08\x0A-\x1f\x7f]+)"""")

    fun evaluate(headerValues: List<String>): List<EngineAuthVerdict> =
        listOf(media3(headerValues), rtspClientAndroid(headerValues))

    fun media3(headerValues: List<String>): EngineAuthVerdict {
        val engine = PlayerEngine.MEDIA3
        if (headerValues.isEmpty()) return EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, null,
            "401 without a WWW-Authenticate header")
        var basicSeen = false
        for (value in headerValues) {
            if (MEDIA3_DIGEST.containsMatchIn(value)) {
                return digestVerdict(engine, value, echoesOpaque = true)
            }
            if (MEDIA3_BASIC.matches(value)) {
                basicSeen = true
                continue
            }
            return EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, null,
                "Media3 can't parse this WWW-Authenticate header (it expects Digest realm=\"…\", nonce=\"…\" in that order): $value")
        }
        return if (basicSeen) {
            EngineAuthVerdict(engine, EngineAuthVerdict.Level.OK, "Basic", "Basic authentication")
        } else {
            EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, null, "No usable challenge")
        }
    }

    fun rtspClientAndroid(headerValues: List<String>): EngineAuthVerdict {
        val engine = PlayerEngine.RTSP_CLIENT_ANDROID
        val digest = headerValues.firstOrNull { it.lowercase().startsWith("digest") }
        if (digest != null) {
            val v = digest.substring(minOf(7, digest.length)).trim()
            if (!v.contains("realm=\"") || !v.contains("nonce=\"")) {
                return EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, "Digest",
                    "rtsp-client-android looks for lower-case realm=\"…\" and nonce=\"…\" and finds none here")
            }
            return digestVerdict(engine, digest, echoesOpaque = false)
        }
        if (headerValues.any { it.lowercase().startsWith("basic") }) {
            return EngineAuthVerdict(engine, EngineAuthVerdict.Level.OK, "Basic", "Basic authentication")
        }
        return EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, null, "No Digest or Basic challenge")
    }

    private fun digestVerdict(engine: PlayerEngine, header: String, echoesOpaque: Boolean): EngineAuthVerdict {
        val challenge = WwwAuthenticate.parse(header).firstOrNull { it.isDigest }
        val algorithm = challenge?.algorithm
        if (algorithm != null && !algorithm.equals("MD5", ignoreCase = true)) {
            return EngineAuthVerdict(engine, EngineAuthVerdict.Level.FAILS, "Digest",
                "Camera asks for Digest algorithm $algorithm; ${engine.label} only answers MD5")
        }
        val risks = mutableListOf<String>()
        if (challenge != null && challenge.qopOptions.isNotEmpty()) {
            risks += "camera offers qop=${challenge.qopOptions.joinToString(",")} but ${engine.label} answers without qop " +
                "(works only if the camera accepts RFC 2069 responses; the probe's first attempt shows this)"
        }
        if (!echoesOpaque && challenge?.opaque != null) {
            risks += "${engine.label} doesn't send back the opaque value"
        }
        return if (risks.isEmpty()) {
            EngineAuthVerdict(engine, EngineAuthVerdict.Level.OK, "Digest", "Digest MD5")
        } else {
            EngineAuthVerdict(engine, EngineAuthVerdict.Level.RISK, "Digest", risks.joinToString("; "))
        }
    }
}
