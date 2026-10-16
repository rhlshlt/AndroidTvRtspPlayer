package io.github.rhlshlt.tvrtsp

import android.app.Application
import android.os.StrictMode

class TvRtspApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            // Flag network or disk work on the main thread and leaked sockets during development.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().detectDiskWrites().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())
        }
    }
}
