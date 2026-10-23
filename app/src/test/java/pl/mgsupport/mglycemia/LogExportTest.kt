package pl.mgsupport.mglycemia

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class LogExportTest {

    @Test
    fun detectsKeyBleLines() {
        assertTrue(LogExport.isKeyLine("GATT link up, status=0, bond=bonded"))
        assertTrue(LogExport.isKeyLine("GATT link down, status=8"))
        assertTrue(LogExport.isKeyLine("descriptor write 2902 len=2"))
        assertTrue(LogExport.isKeyLine("descriptor read 2902"))
        assertTrue(LogExport.isKeyLine("read 2a50 len=7"))
        assertTrue(LogExport.isKeyLine("bond state bonding -> bonded reason=0"))
        assertTrue(LogExport.isKeyLine("pairing request variant=CONSENT passkey=none bond=none"))
        assertTrue(LogExport.isKeyLine("bondedDevices(link-up) count=1 list=none"))
        assertTrue(LogExport.isKeyLine("sendResponse rejected reqId=7 status=0 (success) accepted=false"))
        assertTrue(LogExport.isKeyLine("phy update tx=2 rx=2 status=0 (success) bond=bonded"))
        assertFalse(LogExport.isKeyLine("History: +12 events (up to seq 1234)"))
        assertFalse(LogExport.isKeyLine("Sensor: 120 mg/dL"))
    }

    @Test
    fun redactsSensitiveData() {
        val line = "device AA:BB:CC:DD:EE:FF ip 192.168.1.10 mail jan@example.com token=sekret123"
        val redacted = LogExport.redact(line)
        assertFalse(redacted.contains("AA:BB:CC:DD:EE:FF"))
        assertFalse(redacted.contains("192.168.1.10"))
        assertFalse(redacted.contains("jan@example.com"))
        assertFalse(redacted.contains("sekret123"))
        assertTrue(redacted.contains("[REDACTED-MAC]"))
        assertTrue(redacted.contains("[REDACTED-IP]"))
        assertTrue(redacted.contains("[REDACTED-EMAIL]"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun keyLinesFilterAndRedact() {
        val lines = listOf(
            "GATT link down, status=8 device AA:BB:CC:DD:EE:FF",
            "zwykły log bez znaczenia",
            "read 2a50 len=7",
        )
        val keys = LogExport.keyLines(lines)
        assertEquals(2, keys.size)
        assertFalse(keys.any { it.contains("AA:BB") })
        assertTrue(keys.any { it.contains("GATT link down") })
    }

    @Test
    fun reportWarnsAboutNoEncryption() {
        val report = LogExport.buildReport("bond=bonded\nok", listOf("read 2a50 len=7"))
        assertTrue(report.contains(LogExport.UNENCRYPTED_NOTICE))
        assertTrue(report.contains("Kluczowe linie"))
        assertTrue(report.contains("Log aplikacyjny"))
        assertTrue(report.contains("logcat"))
    }

    @Test
    fun txtAndZipAreWrittenWithoutTouchingOtherFiles() {
        val dir = File(System.getProperty("java.io.tmpdir"), "logexport-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            val appLog = "GATT link down, status=8\nok"
            val txt = LogExport.writeTxt(dir, appLog, listOf("read 2a50 len=7"))
            assertTrue(txt.exists() && txt.length() > 0)
            assertTrue(txt.readText().contains(LogExport.UNENCRYPTED_NOTICE))
            val zip = LogExport.writeZip(dir, appLog, emptyList())
            assertTrue(zip.exists() && zip.length() > 0)
            ZipFile(zip).use { z ->
                assertNotNull(z.getEntry("diagnostyka.txt"))
                assertNotNull(z.getEntry("kluczowe-linie.txt"))
            }
            // Tylko dwa nowe pliki, nic poza tym.
            assertEquals(2, dir.listFiles()!!.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
