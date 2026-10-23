package pl.mgsupport.mglycemia.core.ble.connection

import android.Manifest
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.TimeZone
import pl.mgsupport.mglycemia.BuildConfig

/**
 * Snapshot of the phone + Bluetooth environment, emitted into the technical log when a session starts.
 *
 * Pairing failures in the peripheral (phone-advertises) topology are almost always stack- or
 * chipset-specific, so a log without the phone model, Android build, BLE capability flags and Doze
 * state is not actionable. Everything here is non-identifying: no Bluetooth address, no serial, no
 * account data. MAC addresses never reach this file.
 */
object DeviceDiagnostics {

    private val staticLock = Any()
    private var cachedStatic: List<String>? = null

    /** Static OS/hardware facts plus live Bluetooth/Doze state. */
    fun report(context: Context): List<String> {
        val app = context.applicationContext
        return staticFacts() + liveFacts(app)
    }

    /** Static OS/hardware facts; cached for the process lifetime (they cannot change at runtime). */
    fun staticFacts(): List<String> =
        synchronized(staticLock) {
            cachedStatic ?: buildStatic().also { cachedStatic = it }
        }

    private fun buildStatic(): List<String> =
        listOf(
            "app version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "device manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} " +
                "device=${Build.DEVICE} product=${Build.PRODUCT} board=${Build.BOARD} " +
                "hardware=${Build.HARDWARE}",
            "device soc=${socLabel()} abis=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                "abi64=${Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()} " +
                "abi32=${Build.SUPPORTED_32_BIT_ABIS.isNotEmpty()}",
            "android release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                "codename=${Build.VERSION.CODENAME} incremental=${Build.VERSION.INCREMENTAL} " +
                "securityPatch=${Build.VERSION.SECURITY_PATCH}",
            "build id=${Build.ID} type=${Build.TYPE} tags=${Build.TAGS} display=${Build.DISPLAY}",
            "build fingerprint=${Build.FINGERPRINT}",
            "runtime kernel=${System.getProperty("os.version") ?: "?"} " +
                "vm=${System.getProperty("java.vm.version") ?: "?"}",
            "locale=${Locale.getDefault().toLanguageTag()} timezone=${TimeZone.getDefault().id}",
        )

    /** Bluetooth adapter, permission, Doze, memory and clock facts: re-read on every session. */
    fun liveFacts(context: Context): List<String> {
        val lines = ArrayList<String>(8)
        lines += bluetoothLine(context)
        lines += profileLine(context)
        lines += permissionsLine(context)
        lines += powerLine(context)
        lines += phoneLine(context)
        lines += memoryLine(context)
        lines += clockLine()
        return lines
    }

    private fun bluetoothLine(context: Context): String {
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) return "bluetooth adapter=unavailable"
        val fields = StringBuilder("bluetooth")
        fields.append(" name=").append(adapter.name ?: "?")
        fields.append(" enabled=").append(adapter.isEnabled)
        fields.append(" state=").append(adapterStateLabel(adapter.state))
        fields.append(" multiAdv=").append(adapter.isMultipleAdvertisementSupported)
        fields.append(" advertiser=").append(adapter.bluetoothLeAdvertiser != null)
        fields.append(" offloadFilter=").append(adapter.isOffloadedFilteringSupported)
        fields.append(" offloadBatch=").append(adapter.isOffloadedScanBatchingSupported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            fields.append(" le2m=").append(adapter.isLe2MPhySupported)
            fields.append(" leCoded=").append(adapter.isLeCodedPhySupported)
            fields.append(" extAdv=").append(adapter.isLeExtendedAdvertisingSupported)
            fields.append(" periodicAdv=").append(adapter.isLePeriodicAdvertisingSupported)
        }
        fields.append(" localHash=").append(localAddressHash(adapter))
        return fields.toString()
    }

    /**
     * Short hash of the local Bluetooth address. The address itself is identifying, but the hash
     * tells us whether it changes between sessions. If it stays the same, a stale bond on the pump
     * can still match this phone; if it changes, stale keys cannot be the cause.
     */
    private fun localAddressHash(adapter: BluetoothAdapter): String {
        val address =
            try {
                adapter.address
            } catch (e: SecurityException) {
                null
            }
        if (address.isNullOrBlank() || address == "02:00:00:00:00:00") return "unavailable"
        return try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(address.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unavailable"
        }
    }

    private fun profileLine(context: Context): String {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            ?: return "bluetooth profiles=unavailable"
        val server = connectedCount(manager, BluetoothProfile.GATT_SERVER)
        val client = connectedCount(manager, BluetoothProfile.GATT)
        val a2dp = connectedCount(manager, BluetoothProfile.A2DP)
        return "bluetooth profiles gattServer=$server gattClient=$client a2dp=$a2dp"
    }

    private fun connectedCount(manager: BluetoothManager, profile: Int): String =
        try {
            manager.getConnectedDevices(profile).size.toString()
        } catch (e: SecurityException) {
            "no-permission"
        } catch (e: RuntimeException) {
            // Some stacks throw for profiles they do not expose; that is diagnostic information too.
            "unsupported"
        }

    private fun permissionsLine(context: Context): String {
        fun granted(permission: String): String =
            if (ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                "yes"
            } else {
                "no"
            }

        val builder = StringBuilder("permissions ble=")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.append(granted(Manifest.permission.BLUETOOTH_CONNECT))
            builder.append("/adv=").append(granted(Manifest.permission.BLUETOOTH_ADVERTISE))
            builder.append("/scan=").append(granted(Manifest.permission.BLUETOOTH_SCAN))
        } else {
            builder.append("pre-S")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.append("/notif=").append(granted(Manifest.permission.POST_NOTIFICATIONS))
        }
        return builder.toString()
    }

    private fun powerLine(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "power=unavailable"
        val ignoring =
            try {
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: RuntimeException) {
                false
            }
        return "power ignoringBatteryOptimizations=$ignoring deviceIdle=${pm.isDeviceIdleMode} " +
            "powerSave=${pm.isPowerSaveMode}"
    }

    /**
     * Screen, lock, battery, ringer and standby state. Those decide whether a background BLE
     * session and an alert are actually delivered, so they belong in every report.
     */
    private fun phoneLine(context: Context): String {
        val fields = StringBuilder("phone")
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            fields.append(" screen=").append(if (pm.isInteractive) "on" else "off")
        }
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km != null) {
            fields.append(" keyguard=").append(if (km.isKeyguardLocked) "locked" else "unlocked")
        }
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (battery != null) {
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent in 0..100) fields.append(" battery=").append(percent).append('%')
            fields.append(" charging=").append(battery.isCharging)
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audio != null) {
            fields.append(" ringer=").append(ringerLabel(audio.ringerMode))
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm != null) {
            fields.append(" dnd=").append(dndLabel(nm.currentInterruptionFilter))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                fields.append(" standby=").append(standbyLabel(usm.appStandbyBucket))
            }
        }
        fields.append(" uptimeMin=").append(SystemClock.elapsedRealtime() / 60_000L)
        return fields.toString()
    }

    private fun ringerLabel(mode: Int): String =
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            AudioManager.RINGER_MODE_NORMAL -> "normal"
            else -> "unknown($mode)"
        }

    private fun dndLabel(filter: Int): String =
        when (filter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> "off"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
            NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
            else -> "unknown($filter)"
        }

    private fun standbyLabel(bucket: Int): String =
        when (bucket) {
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "active"
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "working"
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "frequent"
            UsageStatsManager.STANDBY_BUCKET_RARE -> "rare"
            UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "restricted"
            else -> bucket.toString()
        }

    private fun memoryLine(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return "memory=unavailable"
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return "memory classMb=${am.memoryClass} largeClassMb=${am.largeMemoryClass} " +
            "totalRamMb=${info.totalMem / (1024 * 1024)} availRamMb=${info.availMem / (1024 * 1024)} " +
            "lowRam=${info.lowMemory}"
    }

    /**
     * Wall clock vs monotonic clock in a readable form. History timestamps are anchored on pump
     * time, so a wrong wall clock explains "stale data" reports that are actually a clock mismatch.
     */
    private fun clockLine(): String {
        val wall = System.currentTimeMillis()
        val monotonic = SystemClock.elapsedRealtime()
        val zone = TimeZone.getDefault()
        val local =
            try {
                java.time.Instant.ofEpochMilli(wall)
                    .atZone(zone.toZoneId())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
            } catch (e: RuntimeException) {
                "?"
            }
        return "clock local=$local zone=${zone.id} wallMs=$wall elapsedMs=$monotonic " +
            "offsetMin=${zone.getOffset(wall) / 60_000}"
    }

    private fun socLabel(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim().ifEmpty { Build.HARDWARE }
        } else {
            Build.HARDWARE
        }

    private fun adapterStateLabel(state: Int): String =
        when (state) {
            BluetoothAdapter.STATE_OFF -> "off"
            BluetoothAdapter.STATE_TURNING_ON -> "turning-on"
            BluetoothAdapter.STATE_ON -> "on"
            BluetoothAdapter.STATE_TURNING_OFF -> "turning-off"
            else -> "unknown($state)"
        }
}
