package pl.mgsupport.mglycemia

import android.app.Application
import timber.log.Timber

/**
 * Application entry point. Owns the single [PumpController] (shared by the dashboard and the
 * foreground service), plants Timber so the Medtronic driver logs are visible, and auto-reconnects
 * to an already-paired pump on process start.
 */
class PumpApp : Application() {

    val controller: PumpController by lazy { PumpController(this) }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // If a pump was paired previously, start advertising/reconnecting right away.
        if (controller.isPaired()) {
            controller.reconnect()
        }
    }
}
