package pl.mgsupport.mglycemia

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Read-only budowanie paczki diagnostycznej (bez zapisów do pompy, bez usuwania danych).
 *
 * Zawartość: pełny log aplikacyjny + `logcat` (odczyt) + wyciągnięte kluczowe linie BLE
 * (`bond=`, `descriptor read/write 2902`, `read 2a50 len=`, `GATT link down`). Przed zapisem
 * każda linia przechodzi przez [redact] (adresy MAC, IP, e-maile, wartości sekretów).
 *
 * Pliki wynikowe (txt/zip) NIE SĄ SZYFROWANE — tak samo jak eksport CSV. Informacja o tym jest
 * dopisywana na początku każdego raportu ([UNENCRYPTED_NOTICE]).
 */
object LogExport {
    const val UNENCRYPTED_NOTICE =
        "Uwaga: ten plik NIE JEST SZYFROWANY (jak eksport CSV). " +
            "Nie wysyłaj go publicznie; przekaż wyłącznie osobie wspierającej diagnostykę."

    private val MAC = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")
    private val IPV4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val SECRET_VALUE = Regex("(?i)\\b(token|password|passwd|secret|api[_-]?key|pairing[_-]?code)\\s*[:=]\\s*\\S+")

    /** Kluczowe linie diagnostyczne parowania/BLE — sprawdzane case-insensitive. */
    fun isKeyLine(line: String): Boolean {
        val l = line.lowercase(Locale.ROOT)
        if ("bond=" in l || "bond state" in l || "pairing failed" in l) return true
        if (("descriptor read" in l || "descriptor write" in l) && "2902" in l) return true
        if ("read 2a50" in l) return true
        if ("gatt link down" in l || "gatt link up" in l || "gatt disconnect" in l) return true
        return false
    }

    /** Redakcja danych wrażliwych w jednej linii logu (MAC, IP, e-mail, sekrety typu token=…). */
    fun redact(line: String): String {
        var s = MAC.replace(line, "[REDACTED-MAC]")
        s = IPV4.replace(s, "[REDACTED-IP]")
        s = EMAIL.replace(s, "[REDACTED-EMAIL]")
        s = SECRET_VALUE.replace(s) { m ->
            val key = m.value.substringBefore('=').substringBefore(':')
            "$key=[REDACTED]"
        }
        return s
    }

    /** Podzbiór [lines] z kluczowymi liniami, już po redakcji. */
    fun keyLines(lines: List<String>): List<String> =
        lines.filter(::isKeyLine).map(::redact)

    /** Pełny raport txt: nagłówek + kluczowe linie + log aplikacyjny + logcat (wszystko po redakcji). */
    fun buildReport(appLog: String, logcatLines: List<String>, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(nowMs))
        val appLines = appLog.lineSequence().map(::redact).toList()
        val redactedLogcat = logcatLines.map(::redact)
        val keys = (appLines + redactedLogcat).filter(::isKeyLine)
        return buildString {
            appendLine("MGlikemia — diagnostyka ($stamp)")
            appendLine(UNENCRYPTED_NOTICE)
            appendLine()
            appendLine("== Kluczowe linie (bond / 2902 / 2a50 / GATT) ==")
            if (keys.isEmpty()) appendLine("(brak)")
            else keys.forEach { appendLine(it) }
            appendLine()
            appendLine("== Log aplikacyjny ==")
            if (appLines.isEmpty()) appendLine("(pusty)")
            else appLines.forEach { appendLine(it) }
            appendLine()
            appendLine("== logcat ==")
            if (redactedLogcat.isEmpty()) appendLine("(niedostępny lub pusty)")
            else redactedLogcat.forEach { appendLine(it) }
        }
    }

    /** Odczyt logcat (tylko do odczytu); przy braku dostępu zwraca pustą listę, nigdy nie rzuca. */
    fun collectLogcat(maxLines: Int = 2000): List<String> =
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "*:D"))
            proc.inputStream.bufferedReader().use { r ->
                val all = r.readLines()
                if (all.size <= maxLines) all else all.takeLast(maxLines)
            }
        } catch (_: Exception) {
            emptyList()
        }

    /**
     * Zapisuje `diagnostyka-<stamp>.txt` w [dir] i zwraca plik. Nie usuwa ani nie nadpisuje
     * danych użytkownika poza własnym nowym plikiem.
     */
    fun writeTxt(dir: File, appLog: String, logcatLines: List<String>): File {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create diagnostics directory" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(System.currentTimeMillis()))
        return File(dir, "diagnostyka-$stamp.txt").also { it.writeText(buildReport(appLog, logcatLines), Charsets.UTF_8) }
    }

    /** Zapisuje zip z raportem txt + samym wyciągiem kluczowych linii. Zwraca plik zip. */
    fun writeZip(dir: File, appLog: String, logcatLines: List<String>): File {
        check(dir.isDirectory || dir.mkdirs()) { "Cannot create diagnostics directory" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(System.currentTimeMillis()))
        val report = buildReport(appLog, logcatLines)
        val keys = (appLog.lineSequence().map(::redact).toList() + logcatLines.map(::redact))
            .filter(::isKeyLine).joinToString("\n")
        val zip = File(dir, "diagnostyka-$stamp.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("diagnostyka.txt"))
            z.write(report.toByteArray(Charsets.UTF_8))
            z.closeEntry()
            z.putNextEntry(ZipEntry("kluczowe-linie.txt"))
            val keyBody = UNENCRYPTED_NOTICE + "\n\n" + if (keys.isBlank()) "(brak kluczowych linii)\n" else keys + "\n"
            z.write(keyBody.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return zip
    }
}
