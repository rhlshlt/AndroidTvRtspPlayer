package io.github.rhlshlt.tvrtsp.core.rtsp

/** Which lens a Tapo camera streams from. Dual-lens models expose the telephoto lens on stream6/7. */
enum class TapoLens(val mainPath: String, val subPath: String) {
    STANDARD("/stream1", "/stream2"),
    TELEPHOTO("/stream6", "/stream7"),
}

/** The two RTSP URLs of one Tapo camera: main (high quality) and sub (standard quality). */
class TapoStreams(val main: RtspUrl, val sub: RtspUrl)

/** A problem with a Tapo Camera Account, as entered in the add-camera form. */
data class TapoAccountIssue(val field: Field, val severity: Severity, val message: String) {
    enum class Field { USERNAME, PASSWORD }
    enum class Severity { ERROR, WARNING }
}

/**
 * TP-Link Tapo specifics: fixed stream paths on port 554 and a per-camera "Camera Account"
 * (Tapo app → Device Settings → Advanced Settings → Camera Account) of 6–32 characters.
 */
object Tapo {
    const val RTSP_PORT = 554
    const val ACCOUNT_MIN_LENGTH = 6
    const val ACCOUNT_MAX_LENGTH = 32

    /** Builds both stream URLs from an IP address or host name and the camera account. */
    fun streams(
        host: String,
        username: String? = null,
        password: String? = null,
        lens: TapoLens = TapoLens.STANDARD,
        port: Int = RTSP_PORT,
    ): TapoStreams = TapoStreams(
        main = RtspUrl.of(host, lens.mainPath, port, username, password),
        sub = RtspUrl.of(host, lens.subPath, port, username, password),
    )

    /**
     * Checks the camera account against the Tapo app's rules. Lengths outside 6–32 are errors;
     * characters other than letters and digits are a warning only, because TP-Link's troubleshooting
     * suggests removing special characters when a stream won't open. Phase 0 tests whether that
     * matters on the C320WS.
     */
    fun validateAccount(username: String, password: String): List<TapoAccountIssue> {
        val issues = mutableListOf<TapoAccountIssue>()
        fun length(field: TapoAccountIssue.Field, value: String, label: String) {
            if (value.length !in ACCOUNT_MIN_LENGTH..ACCOUNT_MAX_LENGTH) {
                issues += TapoAccountIssue(field, TapoAccountIssue.Severity.ERROR,
                    "$label must be $ACCOUNT_MIN_LENGTH–$ACCOUNT_MAX_LENGTH characters (it has ${value.length})")
            }
        }
        fun special(field: TapoAccountIssue.Field, value: String, label: String) {
            if (value.any { !(it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9') }) {
                issues += TapoAccountIssue(field, TapoAccountIssue.Severity.WARNING,
                    "$label has special characters; if the stream won't open, try letters and digits only")
            }
        }
        length(TapoAccountIssue.Field.USERNAME, username, "User name")
        length(TapoAccountIssue.Field.PASSWORD, password, "Password")
        special(TapoAccountIssue.Field.USERNAME, username, "User name")
        special(TapoAccountIssue.Field.PASSWORD, password, "Password")
        return issues
    }
}
