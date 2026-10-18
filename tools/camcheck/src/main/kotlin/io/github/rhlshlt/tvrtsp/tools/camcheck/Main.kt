package io.github.rhlshlt.tvrtsp.tools.camcheck

import io.github.rhlshlt.tvrtsp.core.rtsp.CameraCheck
import io.github.rhlshlt.tvrtsp.core.rtsp.CameraCheckOptions
import io.github.rhlshlt.tvrtsp.core.rtsp.CameraReport
import io.github.rhlshlt.tvrtsp.core.rtsp.Json
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrl
import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrlException
import io.github.rhlshlt.tvrtsp.core.rtsp.Tapo
import io.github.rhlshlt.tvrtsp.core.rtsp.TapoLens
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private const val USAGE = """
camcheck - checks RTSP cameras the way the TV app's engines will use them.

Usage:
  camcheck [options] <rtsp-url> [<rtsp-url> ...]
  camcheck [options] --tapo <ip-address> --user <camera-account>

Options:
  --tapo IP            Check a Tapo camera's main and sub stream (rtsp://IP:554/stream1 and /stream2);
                       IP:PORT uses another port
  --telephoto          With --tapo: use the telephoto lens (stream6 and stream7)
  --user NAME          Camera account user name (instead of putting it in the URL)
  --password-env VAR   Read the password from environment variable VAR (default: RTSP_PASSWORD)
  --ask-password       Prompt for the password (needs an interactive console)
  --seconds N          Receive each stream for N seconds after PLAY (default 15; 0 = stop after DESCRIBE)
  --parallel N         Open N sessions to each URL at the same time (tests the camera's viewer limit)
  --no-audio           Don't set up the audio track
  --timeout MS         Connect and read timeout in milliseconds (default 3000)
  --json FILE          Also write the reports as JSON (UTF-8) to FILE
  --sdp                Print the SDP the camera sent
  --utf8               Print typographic characters (default output is ASCII-safe)
  -h, --help           Show this help

Credentials never appear in the output. Prefer --user with RTSP_PASSWORD over credentials in the
URL, which end up in your shell history.

Exit status: 0 when every check is OK, 1 when any check failed, 2 for usage errors.
"""

private class Args(
    val urls: List<String>,
    val tapoHost: String?,
    val telephoto: Boolean,
    val user: String?,
    val passwordEnv: String,
    val askPassword: Boolean,
    val seconds: Long,
    val parallel: Int,
    val audio: Boolean,
    val timeoutMs: Int,
    val jsonFile: String?,
    val sdp: Boolean,
    val utf8: Boolean,
)

fun main(argv: Array<String>) {
    val args = try {
        parse(argv)
    } catch (e: IllegalArgumentException) {
        System.err.println("camcheck: ${e.message}")
        System.err.println("Run camcheck --help for usage.")
        exitProcess(2)
    }

    val password: String? = when {
        args.user == null -> null
        args.askPassword -> {
            val console = System.console()
            if (console == null) {
                System.err.println("camcheck: --ask-password needs an interactive console; use ${args.passwordEnv} instead")
                exitProcess(2)
            }
            String(console.readPassword("Password for %s: ", args.user) ?: CharArray(0))
        }
        else -> System.getenv(args.passwordEnv) ?: run {
            System.err.println("camcheck: set the password in environment variable ${args.passwordEnv}, or use --ask-password")
            exitProcess(2)
        }
    }

    val targets: List<Pair<String, RtspUrl>> = try {
        buildTargets(args, password)
    } catch (e: RtspUrlException) {
        System.err.println("camcheck: ${e.message}")
        exitProcess(2)
    }
    if (targets.any { (_, url) -> url.hasCredentials && args.user == null }) {
        System.err.println("Note: credentials in a URL end up in shell history; --user with ${args.passwordEnv} avoids that.\n")
    }

    val options = CameraCheckOptions(
        connectTimeoutMs = args.timeoutMs,
        readTimeoutMs = args.timeoutMs,
        analyzeMs = args.seconds * 1000,
        includeAudio = args.audio,
        userAgent = "TvRtspPlayer-camcheck/0.1",
    )
    val reports = mutableListOf<Map<String, Any?>>()
    var allOk = true

    for ((label, url) in targets) {
        if (args.parallel > 1) {
            println("== $label: ${url.redacted()}, ${args.parallel} sessions at once")
            val pool = Executors.newFixedThreadPool(args.parallel)
            val results = pool.invokeAll((1..args.parallel).map { Callable { CameraCheck().run(url, options) } }).map { it.get() }
            pool.shutdown()
            results.forEachIndexed { i, r ->
                val a = r.analysis
                val extra = if (a != null) ", ${a.frames} frames, first keyframe ${a.firstKeyframeMs ?: "-"} ms" else ""
                println("  session ${i + 1}: ${r.outcome.code} ${r.outcome.title}$extra")
                if (!r.ok) allOk = false
                reports += r.toMap() + mapOf("label" to "$label #${i + 1}")
            }
            println()
        } else {
            println("== $label")
            val r = CameraCheck().run(url, options)
            println(r.toText(ascii = !args.utf8, includeSdp = args.sdp))
            if (!r.ok) allOk = false
            reports += r.toMap() + mapOf("label" to label)
        }
    }

    args.jsonFile?.let { path ->
        val file = File(path)
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(Json.stringify(reports), Charsets.UTF_8)
        println("JSON written to $path")
    }
    exitProcess(if (allOk) 0 else 1)
}

private fun buildTargets(args: Args, password: String?): List<Pair<String, RtspUrl>> {
    val list = mutableListOf<Pair<String, RtspUrl>>()
    args.tapoHost?.let { host ->
        val lens = if (args.telephoto) TapoLens.TELEPHOTO else TapoLens.STANDARD
        val streams = Tapo.streams(host, args.user, password, lens)
        args.user?.let { u ->
            password?.let { p ->
                Tapo.validateAccount(u, p).forEach { System.err.println("Camera account: ${it.message}") }
            }
        }
        list += "main stream" to streams.main
        list += "sub stream" to streams.sub
    }
    for (raw in args.urls) {
        val url = RtspUrl.parse(raw)
        list += raw.substringAfterLast('/').ifEmpty { "stream" } to (if (args.user != null) url.withCredentials(args.user, password) else url)
    }
    if (list.isEmpty()) throw IllegalArgumentException("give at least one rtsp:// URL or --tapo IP")
    return list
}

private fun parse(argv: Array<String>): Args {
    val urls = mutableListOf<String>()
    var tapo: String? = null
    var telephoto = false
    var user: String? = null
    var passwordEnv = "RTSP_PASSWORD"
    var ask = false
    var seconds = 15L
    var parallel = 1
    var audio = true
    var timeout = 3000
    var json: String? = null
    var sdp = false
    var utf8 = false
    var i = 0
    fun value(name: String): String {
        if (i + 1 >= argv.size) throw IllegalArgumentException("$name needs a value")
        i++
        return argv[i]
    }
    while (i < argv.size) {
        when (val a = argv[i]) {
            "-h", "--help" -> {
                println(USAGE.trim())
                exitProcess(0)
            }
            "--tapo" -> tapo = value(a)
            "--telephoto" -> telephoto = true
            "--user" -> user = value(a)
            "--password-env" -> passwordEnv = value(a)
            "--ask-password" -> ask = true
            "--seconds" -> seconds = value(a).toLongOrNull()?.takeIf { it in 0..3600 } ?: throw IllegalArgumentException("--seconds must be 0-3600")
            "--parallel" -> parallel = value(a).toIntOrNull()?.takeIf { it in 1..16 } ?: throw IllegalArgumentException("--parallel must be 1-16")
            "--no-audio" -> audio = false
            "--timeout" -> timeout = value(a).toIntOrNull()?.takeIf { it in 100..60_000 } ?: throw IllegalArgumentException("--timeout must be 100-60000 ms")
            "--json" -> json = value(a)
            "--sdp" -> sdp = true
            "--utf8" -> utf8 = true
            else -> if (a.startsWith("-")) throw IllegalArgumentException("unknown option $a") else urls += a
        }
        i++
    }
    if (tapo != null && user == null) throw IllegalArgumentException("--tapo needs --user (the Camera Account from the Tapo app)")
    return Args(urls, tapo, telephoto, user, passwordEnv, ask, seconds, parallel, audio, timeout, json, sdp, utf8)
}
