package com.connor.pendant.ble

import java.util.UUID

/**
 * GATT UUIDs scraped from BasedHardware/omi firmware source.
 *
 * Source files:
 *   firmware/omi/src/lib/core/transport.c    — audio + settings + features + time sync
 *   firmware/omi/src/lib/core/storage.c       — flash ring buffer (offline sync)
 *   firmware/omi/src/lib/core/button.c        — single+double press events
 *   firmware/omi/src/lib/core/haptic.c        — vibration trigger
 *   firmware/omi/src/lib/core/accel.c         — accelerometer data
 *   firmware/scripts/devkit/client.py:13-14   — confirmed audio service/char pair
 *
 * IMPORTANT: validate these against live nRF Connect scan before trusting.
 * See docs/omi-pendant-nrf-inspection.md.
 */
object OmiUuids {

    // Advertised device name. Old firmware: "Friend". Newer (3.0.x+): "Omi".
    val ADVERTISED_NAMES = setOf("Omi", "Friend")

    // --- Audio service (the only one M1 cares about) ---
    val AUDIO_SERVICE: UUID = UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
    val AUDIO_DATA_CHAR: UUID = UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")  // READ + NOTIFY
    val AUDIO_CODEC_CHAR: UUID = UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214") // READ
    val AUDIO_SPEAKER_CHAR: UUID = UUID.fromString("19B10003-E8F2-537E-4F6C-D104768A1214") // WRITE + NOTIFY

    // Client Characteristic Configuration descriptor — write 0x0100 to enable notifications
    val CCC_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // --- Settings service (mic-gain knob is the future "boost capture at distance" lever) ---
    val SETTINGS_SERVICE: UUID = UUID.fromString("19B10010-E8F2-537E-4F6C-D104768A1214")
    val SETTINGS_DIM_RATIO_CHAR: UUID = UUID.fromString("19B10011-E8F2-537E-4F6C-D104768A1214")
    val SETTINGS_MIC_GAIN_CHAR: UUID = UUID.fromString("19B10012-E8F2-537E-4F6C-D104768A1214")
    val SETTINGS_CHARGING_CHAR: UUID = UUID.fromString("19B10013-E8F2-537E-4F6C-D104768A1214")
    val SETTINGS_TELEMETRY_CHAR: UUID = UUID.fromString("19B10014-E8F2-537E-4F6C-D104768A1214")

    // --- Features service (read once at connect to know what the device supports) ---
    val FEATURES_SERVICE: UUID = UUID.fromString("19B10020-E8F2-537E-4F6C-D104768A1214")
    val FEATURES_CHAR: UUID = UUID.fromString("19B10021-E8F2-537E-4F6C-D104768A1214")

    // --- Time sync (uint32 epoch_s, little-endian) ---
    val TIME_SYNC_SERVICE: UUID = UUID.fromString("19B10030-E8F2-537E-4F6C-D104768A1214")
    val TIME_WRITE_CHAR: UUID = UUID.fromString("19B10031-E8F2-537E-4F6C-D104768A1214")
    val TIME_READ_CHAR: UUID = UUID.fromString("19B10032-E8F2-537E-4F6C-D104768A1214")

    // --- Storage (flash ring buffer for offline-captured audio; CV1+ only) ---
    val STORAGE_SERVICE: UUID = UUID.fromString("30295780-4301-EABD-2904-2849ADFEAE43")
    val STORAGE_READ_CHAR: UUID = UUID.fromString("30295782-4301-EABD-2904-2849ADFEAE43")
    val STORAGE_WRITE_CHAR: UUID = UUID.fromString("30295781-4301-EABD-2904-2849ADFEAE43")

    // --- Button (single press, double press, long press events as notify) ---
    val BUTTON_SERVICE: UUID = UUID.fromString("23BA7924-0000-1000-7450-346EAC492E92")
    val BUTTON_STATE_CHAR: UUID = UUID.fromString("23BA7925-0000-1000-7450-346EAC492E92")

    // --- Haptic (vibrate trigger by writing byte values 1, 2, or 3) ---
    val HAPTIC_SERVICE: UUID = UUID.fromString("CAB1AB95-2EA5-4F4D-BB56-874B72CFC984")
    val HAPTIC_CHAR: UUID = UUID.fromString("CAB1AB96-2EA5-4F4D-BB56-874B72CFC984")

    // --- Standard BLE Battery Service (0x180F) — single uint8 % at 0x2A19 ---
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
    val BATTERY_LEVEL_CHAR: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

    // Audio data char framing: first 3 bytes are header (seq counter + frame idx),
    // payload starts at byte 3. See firmware/scripts/devkit/client.py:129.
    const val FRAME_HEADER_BYTES = 3

    /**
     * Codec byte returned by AUDIO_CODEC_CHAR. Decode per firmware enum.
     * If your firmware adds a value, extend this when discovered.
     */
    enum class Codec(val id: Int) {
        PCM_8K(0),
        PCM_16K(1),
        MULAW_8K(10),
        MULAW_16K(11),
        OPUS_16K(20),   // Default on CV1 fw 3.0.x
        OPUS_24K(21),   // Observed in the wild on fw 3.0.19 (some pendants)
        UNKNOWN(-1);

        companion object {
            fun fromByte(b: Byte): Codec =
                values().firstOrNull { it.id == (b.toInt() and 0xFF) } ?: UNKNOWN
        }
    }
}
