package pl.mgsupport.mglycemia

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Skróty do systemowych ekranów ustawień, których użytkownik potrzebuje przy problemach z BLE.
 *
 * Powód istnienia: producenci (Samsung, Xiaomi, Redmi) chowają te ekrany pod różnymi nazwami, a
 * instrukcja słowna „wejdź w Ustawienia → Aplikacje → pokaż systemowe” rozjeżdża się między
 * nakładkami. Intent otwiera właściwy ekran bez zgadywania ścieżki.
 *
 * Czego tu celowo nie ma: nie istnieje publiczny intent do wyczyszczenia pamięci podręcznej
 * systemowej aplikacji Bluetooth ani do wyłączenia losowego adresu MAC. Zamiast udawać, że jest,
 * [all] prowadzi do listy aplikacji i do ustawień Bluetooth, gdzie użytkownik robi to ręcznie.
 */
object SystemShortcuts {

    /** Jeden wiersz listy: etykieta, wyjaśnienie i gotowy intent. */
    data class Shortcut(val label: String, val detail: String, val intent: Intent)

    /** Wszystkie skróty dostępne na tym urządzeniu, w kolejności przydatności. */
    fun all(context: Context): List<Shortcut> {
        val shortcuts = mutableListOf<Shortcut>()

        if (!isBluetoothEnabled(context)) {
            shortcuts +=
                Shortcut(
                    "Bluetooth jest wyłączony — otwórz ustawienia",
                    "Bez włączonego adaptera parowanie nie wystartuje.",
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                )
        }

        shortcuts +=
            Shortcut(
                "Ustawienia Bluetooth",
                "Tutaj usuwa się stare wpisy pompy i sprawdza stan sparowania.",
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            )

        shortcuts +=
            Shortcut(
                "Wszystkie aplikacje (pokaż systemowe)",
                "Aby wyczyścić dane aplikacji Bluetooth: menu ⋮ → Pokaż aplikacje systemowe → Bluetooth → Pamięć → Wyczyść.",
                Intent(Settings.ACTION_APPLICATION_SETTINGS),
            )

        shortcuts +=
            Shortcut(
                "Uprawnienia i dane tej aplikacji",
                "Powiadomienia, uprawnienia Bluetooth, wymuszone zatrzymanie.",
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )

        shortcuts +=
            Shortcut(
                "Oszczędzanie baterii",
                "Wyłącz optymalizację dla tej aplikacji, inaczej Doze usypia odczyty w tle.",
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            shortcuts +=
                Shortcut(
                    "Powiadomienia tej aplikacji",
                    "Kanał statusu pompy i alerty glikemii.",
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )

            shortcuts +=
                Shortcut(
                    "Ikona powiadomienia zamiast ikony aplikacji",
                    "Samsung: Powiadomienia → Ustawienia zaawansowane → wyłącz „Pokaż ikonę aplikacji w powiadomieniach”. Wtedy w pasku statusu pojawi się ikona z aktualnym cukrem zamiast logo apki.",
                    Intent("android.settings.NOTIFICATION_SETTINGS"),
                )
        }

        shortcuts +=
            Shortcut(
                "Reset ustawień sieciowych",
                "Otwiera Ustawienia. Dalej: Ogólne zarządzanie → Reset → Reset ustawień sieciowych. Usuwa wszystkie parowania Bluetooth.",
                Intent(Settings.ACTION_SETTINGS),
            )

        return shortcuts
    }

    private fun isBluetoothEnabled(context: Context): Boolean =
        try {
            val manager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            manager?.adapter?.isEnabled == true
        } catch (e: RuntimeException) {
            false
        }
}
