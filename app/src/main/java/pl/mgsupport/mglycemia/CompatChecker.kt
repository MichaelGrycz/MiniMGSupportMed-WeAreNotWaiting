package pl.mgsupport.mglycemia

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Result of checking whether this phone can act as a Medtronic BLE peripheral. */
data class DeviceCompatibility(
    val androidVersion: Int,
    val androidOk: Boolean,
    val bleSupported: Boolean,
    val peripheralSupported: Boolean,
    val verdict: Verdict,
) {
    enum class Verdict { SUPPORTED, LIMITED, UNSUPPORTED }
}

/**
 * Honest capability check. The pump is a BLE central, so the phone must advertise and run a GATT
 * server (peripheral mode): not every Android device exposes that. This is the real compatibility
 * gate, alongside the pump family (only 700-series BLE/SAKE pumps are supported).
 */
object CompatChecker {

    fun checkDevice(context: Context): DeviceCompatibility {
        val pm = context.packageManager
        val ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        // isMultipleAdvertisementSupported is the closest public signal that the controller can
        // advertise as a peripheral. Some devices support single advertising only: treated as
        // "limited" rather than a hard no.
        val peripheral =
            try {
                val bm =
                    context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                bm?.adapter?.isMultipleAdvertisementSupported == true
            } catch (_: Throwable) {
                false
            }

        val androidOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        val verdict =
            when {
                !androidOk || !ble -> DeviceCompatibility.Verdict.UNSUPPORTED
                !peripheral -> DeviceCompatibility.Verdict.LIMITED
                else -> DeviceCompatibility.Verdict.SUPPORTED
            }
        return DeviceCompatibility(Build.VERSION.SDK_INT, androidOk, ble, peripheral, verdict)
    }

    /** Human label for a Medtronic model number read from the pump's Device Information Service. */
    fun pumpLabel(model: String): String =
        when {
            model.contains("1886", true) -> "780G"
            model.contains("1780", true) || model.contains("1781", true) -> "770G/680G"
            model.contains("MMT-1", true) -> "700-series"
            else -> model.ifBlank { "unknown" }
        }
}
