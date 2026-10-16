package io.github.rhlshlt.tvrtsp.core.rtsp

import java.util.Base64

/** `a=rtpmap:<pt> <encoding>/<clock>[/<channels>]`, or a static RTP/AVP payload type. */
data class RtpMap(val payloadType: Int, val encoding: String, val clockRate: Int, val channels: Int?)

/** One `m=` section of an SDP. */
class MediaDescription(
    /** `video`, `audio`, `application`, … */
    val type: String,
    val port: Int,
    val protocol: String,
    val formats: List<Int>,
    /** Attributes in order: name to value (value is null for flag attributes). */
    val attributes: List<Pair<String, String?>>,
    /** `b=` lines, for example `AS` to 4096. */
    val bandwidth: Map<String, Int>,
) {
    fun attribute(name: String): String? = attributes.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val control: String? get() = attribute("control")

    /** The first payload type listed; RTSP cameras send one per track. */
    val payloadType: Int? get() = formats.firstOrNull()

    val rtpMap: RtpMap?
        get() {
            val pt = payloadType ?: return null
            return rtpMaps()[pt] ?: STATIC_PAYLOAD_TYPES[pt]
        }

    /** Upper-case encoding name, for example `H264` or `PCMA`. */
    val encoding: String? get() = rtpMap?.encoding?.uppercase()

    fun rtpMaps(): Map<Int, RtpMap> {
        val result = LinkedHashMap<Int, RtpMap>()
        for ((name, value) in attributes) {
            if (!name.equals("rtpmap", ignoreCase = true) || value == null) continue
            val space = value.indexOf(' ')
            if (space <= 0) continue
            val pt = value.substring(0, space).trim().toIntOrNull() ?: continue
            val parts = value.substring(space + 1).trim().split('/')
            val clock = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            result[pt] = RtpMap(pt, parts[0].trim(), clock, parts.getOrNull(2)?.trim()?.toIntOrNull())
        }
        return result
    }

    /** `a=fmtp` parameters for [pt] with lower-case names; values split at the first '='. */
    fun fmtp(pt: Int? = payloadType): Map<String, String> {
        if (pt == null) return emptyMap()
        for ((name, value) in attributes) {
            if (!name.equals("fmtp", ignoreCase = true) || value == null) continue
            val space = value.indexOfFirst { it == ' ' || it == '\t' }
            if (space <= 0) continue
            if (value.substring(0, space).trim().toIntOrNull() != pt) continue
            return parseFmtpParameters(value.substring(space + 1))
        }
        return emptyMap()
    }

    /** `a=framerate` if the camera sends it. */
    val frameRate: Double? get() = attribute("framerate")?.trim()?.toDoubleOrNull()

    companion object {
        /** Static payload types from RFC 3551 that cameras use without an rtpmap line. */
        val STATIC_PAYLOAD_TYPES: Map<Int, RtpMap> = mapOf(
            0 to RtpMap(0, "PCMU", 8000, 1),
            8 to RtpMap(8, "PCMA", 8000, 1),
            14 to RtpMap(14, "MPA", 90000, null),
            26 to RtpMap(26, "JPEG", 90000, null),
            32 to RtpMap(32, "MPV", 90000, null),
        )

        fun parseFmtpParameters(text: String): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            for (part in text.split(';')) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val eq = p.indexOf('=')
                if (eq < 0) result[p.lowercase()] = "" else result[p.substring(0, eq).trim().lowercase()] = p.substring(eq + 1).trim()
            }
            return result
        }
    }
}

/** A parsed SDP (RFC 4566) as cameras send it in a DESCRIBE answer. */
class SessionDescription(
    val text: String,
    val sessionName: String?,
    val sessionAttributes: List<Pair<String, String?>>,
    val media: List<MediaDescription>,
) {
    fun sessionAttribute(name: String): String? =
        sessionAttributes.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    val video: MediaDescription? get() = media.firstOrNull { it.type.equals("video", ignoreCase = true) }
    val audio: MediaDescription? get() = media.firstOrNull { it.type.equals("audio", ignoreCase = true) }

    /** `a=range`, for example `npt=0-` for a live stream. */
    val range: String? get() = sessionAttribute("range")

    companion object {
        fun parse(text: String): SessionDescription {
            var sessionName: String? = null
            val sessionAttributes = mutableListOf<Pair<String, String?>>()
            val media = mutableListOf<MediaDescription>()
            var current: MutableMediaSection? = null
            var sawVersion = false

            for (raw in text.split('\n')) {
                val line = raw.trimEnd('\r').trim()
                if (line.length < 2 || line[1] != '=') continue
                val value = line.substring(2)
                when (line[0]) {
                    'v' -> sawVersion = true
                    's' -> if (current == null) sessionName = value
                    'm' -> {
                        current?.let { media += it.build() }
                        current = MutableMediaSection.parse(value)
                    }
                    'a' -> {
                        val colon = value.indexOf(':')
                        val attr = if (colon >= 0) value.substring(0, colon) to value.substring(colon + 1) else value to null
                        val section = current
                        if (section == null) sessionAttributes += attr else section.attributes += attr
                    }
                    'b' -> {
                        val section = current
                        val colon = value.indexOf(':')
                        if (section != null && colon > 0) {
                            value.substring(colon + 1).trim().toIntOrNull()?.let { section.bandwidth[value.substring(0, colon)] = it }
                        }
                    }
                }
            }
            current?.let { media += it.build() }
            if (!sawVersion && media.isEmpty()) throw IllegalArgumentException("Not an SDP: no v= or m= lines")
            return SessionDescription(text, sessionName, sessionAttributes, media)
        }
    }

    private class MutableMediaSection(
        val type: String,
        val port: Int,
        val protocol: String,
        val formats: List<Int>,
    ) {
        val attributes = mutableListOf<Pair<String, String?>>()
        val bandwidth = LinkedHashMap<String, Int>()

        fun build() = MediaDescription(type, port, protocol, formats, attributes.toList(), bandwidth.toMap())

        companion object {
            fun parse(value: String): MutableMediaSection {
                val parts = value.trim().split(Regex("\\s+"))
                val port = parts.getOrNull(1)?.substringBefore('/')?.toIntOrNull() ?: 0
                return MutableMediaSection(
                    type = parts.getOrElse(0) { "" },
                    port = port,
                    protocol = parts.getOrElse(2) { "" },
                    formats = parts.drop(3).mapNotNull { it.toIntOrNull() },
                )
            }
        }
    }
}

/** H.264 parameters from an SDP (RFC 6184). */
class H264SdpInfo(
    val packetizationMode: Int,
    /** `profile-level-id` as hex text, for example `4D0032`. */
    val profileLevelId: String?,
    /** NAL units from `sprop-parameter-sets`, without start codes. */
    val parameterSets: List<ByteArray>,
    val spropText: String?,
) {
    val sps: ByteArray? get() = parameterSets.firstOrNull { it.isNotEmpty() && (it[0].toInt() and 0x1F) == H264.NAL_SPS }
    val ppsList: List<ByteArray> get() = parameterSets.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == H264.NAL_PPS }
    val hasSpsAndPps: Boolean get() = sps != null && ppsList.isNotEmpty()

    /** profile_idc, constraint flags and level_idc from `profile-level-id`, if it's well-formed. */
    val profileIdc: Int? get() = profileLevelId?.takeIf { it.length == 6 }?.substring(0, 2)?.toIntOrNull(16)
    val levelIdc: Int? get() = profileLevelId?.takeIf { it.length == 6 }?.substring(4, 6)?.toIntOrNull(16)

    companion object {
        fun from(media: MediaDescription): H264SdpInfo? {
            if (media.encoding != "H264") return null
            val fmtp = media.fmtp()
            val sprop = fmtp["sprop-parameter-sets"]
            val sets = sprop?.split(',')?.mapNotNull { part ->
                val t = part.trim()
                if (t.isEmpty()) null else try {
                    Base64.getDecoder().decode(t)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }.orEmpty()
            return H264SdpInfo(
                packetizationMode = fmtp["packetization-mode"]?.toIntOrNull() ?: 0,
                profileLevelId = fmtp["profile-level-id"]?.uppercase(),
                parameterSets = sets,
                spropText = sprop,
            )
        }
    }
}

/** Resolves track control URLs the way Media3 1.11.1 does (RtspMediaTrack.extractTrackUri). */
object ControlUrl {
    fun resolve(control: String?, requestUri: String, contentBase: String?, contentLocation: String?): String {
        val c = control?.trim()
        if (c.isNullOrEmpty()) return contentBase?.takeIf { it.isNotEmpty() } ?: requestUri
        if (isAbsolute(c)) return c
        val session = contentBase?.takeIf { it.isNotEmpty() } ?: contentLocation?.takeIf { it.isNotEmpty() } ?: requestUri
        if (c == "*") return session
        return appendEncodedPath(session, c)
    }

    private fun isAbsolute(s: String): Boolean {
        val colon = s.indexOf(':')
        if (colon <= 0) return false
        val scheme = s.substring(0, colon)
        return scheme[0].isLetter() && scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' } &&
            s.indexOf('/') !in 0 until colon
    }

    /** Mirrors android.net.Uri.Builder.appendEncodedPath: one '/' between path and segment, query kept. */
    internal fun appendEncodedPath(base: String, segment: String): String {
        val queryStart = base.indexOf('?').let { if (it < 0) base.length else it }
        val beforeQuery = base.substring(0, queryStart)
        val query = base.substring(queryStart)
        val schemeEnd = beforeQuery.indexOf("://")
        val pathStart = if (schemeEnd >= 0) beforeQuery.indexOf('/', schemeEnd + 3).let { if (it < 0) beforeQuery.length else it } else 0
        val authorityPart = beforeQuery.substring(0, pathStart)
        val path = beforeQuery.substring(pathStart)
        val newPath = when {
            path.isEmpty() -> "/$segment"
            path.endsWith("/") -> path + segment
            else -> "$path/$segment"
        }
        return authorityPart + newPath + query
    }
}
