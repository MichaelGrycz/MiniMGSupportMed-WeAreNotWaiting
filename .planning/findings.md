# Findings

- Android app AGP9, compile/target36, min24, coroutines1.10.2, JUnit4.13.2, JavaSake vendored. JBR21 at /c/Program Files/Android/Android Studio/jbr/bin/java.exe. Use explicit JVM GradleWrapperMain; system java is 8.
- Primary BLE: core/ble/connection/AndroidMedtronicPeripheral.kt and MedtronicBleConnectionManager.kt. Legacy BlePeripheralDevice.java is not wired to PumpController.
- BLE defects: duplicate start resets live session and leaks advertiser callback; uncancelled delayed advertisement stop/retry can act on replacement link; first-pair timeout labels bound elsewhere after real link; no timeout before CCCD subscription; callbacks forward before ATT write response; CCCD value global/stale; GATT addService false ignored.
- User log: connect -> read2902 -> disconnect, before SAKE. Cause cannot be proven without bond transition/timing/HCI trace. OS bonded != authenticated.
- PumpController unpair clears credential store on caller thread before manager reads address; scheduleRetry can reset in-progress auth.
- ReadingStore schema1 stores events(seq,ts_ms,type_id,text); no raw history or app-alert persistence. PumpController stamps fetched records with now and seeds cursor at latest, losing historical chart correctness.
- MedtronicHistoryParser already decodes raw records and time references. AUTO_BASAL_DELIVERY must not be labeled autocorrection. Event timestamp resolution must not use download time.
- Backup currently under getExternalFilesDir (removed at uninstall); SAF document tree is appropriate on min24+ without broad storage permissions. Reinstall revokes URI grant; explicit folder selection/restore is required.
