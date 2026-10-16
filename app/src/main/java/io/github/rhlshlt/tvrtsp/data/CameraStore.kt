package io.github.rhlshlt.tvrtsp.data

import io.github.rhlshlt.tvrtsp.core.rtsp.RtspUrl
import io.github.rhlshlt.tvrtsp.core.rtsp.Tapo
import io.github.rhlshlt.tvrtsp.core.rtsp.TapoLens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID

/** A camera with its main (high quality) and sub (standard quality) stream URLs. */
data class Camera(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Main stream URL including credentials; display with [RtspUrl.redacted] only. */
    val main: RtspUrl,
    val sub: RtspUrl?,
    val kind: Kind,
) {
    enum class Kind { TAPO, LAB, URL }

    fun url(useSub: Boolean): RtspUrl = if (useSub && sub != null) sub else main

    /** Host shown in lists; never includes credentials. */
    val address: String get() = main.hostForUrl + (main.explicitPort?.let { ":$it" } ?: "")

    override fun toString(): String = "Camera($name, ${main.redacted()})"
}

/**
 * Phase 0 keeps cameras in memory only: nothing is written to disk until the encrypted store of
 * Phase 1 exists, so camera passwords never touch storage. Cameras come from the add-camera form or,
 * in debug and perf builds, from adb (see docs/PHASE0_GUIDE.md).
 */
object CameraStore {
    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras

    fun add(camera: Camera) = _cameras.update { it + camera }

    fun remove(id: String) = _cameras.update { list -> list.filterNot { it.id == id } }

    fun clear() {
        _cameras.value = emptyList()
    }

    fun addTapo(name: String, host: String, user: String, password: String, lens: TapoLens = TapoLens.STANDARD): Camera {
        val streams = Tapo.streams(host, user, password, lens)
        return Camera(name = name.ifBlank { "Tapo $host" }, main = streams.main, sub = streams.sub, kind = Camera.Kind.TAPO)
            .also(::add)
    }

    fun addUrl(name: String, main: String, sub: String?, user: String?, password: String?): Camera {
        fun parse(s: String) = RtspUrl.parse(s).let { u -> if (!user.isNullOrEmpty()) u.withCredentials(user, password) else u }
        val m = parse(main)
        return Camera(name = name.ifBlank { m.host }, main = m, sub = sub?.takeIf { it.isNotBlank() }?.let(::parse), kind = Camera.Kind.URL)
            .also(::add)
    }

    /**
     * Adds [count] cameras that all read the test lab's two streams (docs/TEST_LAB.md), for
     * multi-view capacity tests: MediaMTX serves the same path to many readers.
     */
    fun addLab(host: String, port: Int, user: String?, password: String?, count: Int, path: String = "lab"): List<Camera> {
        val main = RtspUrl.of(host, "/$path/stream1", port, user, password)
        val sub = RtspUrl.of(host, "/$path/stream2", port, user, password)
        val added = (1..count.coerceIn(1, 32)).map { i ->
            Camera(name = String.format(Locale.ROOT, "Lab %02d", i), main = main, sub = sub, kind = Camera.Kind.LAB)
        }
        _cameras.update { it + added }
        return added
    }
}
