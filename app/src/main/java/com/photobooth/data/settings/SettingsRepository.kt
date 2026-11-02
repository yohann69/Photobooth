package com.photobooth.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.photobooth.data.db.entity.PrintFinish
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.inject.Inject
import javax.inject.Singleton

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "photobooth_settings")

enum class PrinterConnection { NONE, USB, NETWORK, NETWORK_RAW }

enum class PrinterModel(val label: String) {
    DNP_DS_RX1HS("DNP DS-RX1HS"),
    DNP_QW410("DNP QW410"),
    CITIZEN("Citizen CY / CX"),
    OTHER("Other"),
}

data class AppSettings(
    val isPinConfigured: Boolean = false,
    val kioskModeEnabled: Boolean = false,
    val keepScreenOn: Boolean = true,
    val hideSystemBars: Boolean = false,
    val attractSlideshow: Boolean = true,
    val printerConnection: PrinterConnection = PrinterConnection.NONE,
    val printerModel: PrinterModel = PrinterModel.DNP_DS_RX1HS,
    val rollCapacity: Int = 700,
    /** Lifetime printed copies at the moment the current roll was installed. */
    val rollBaselinePrints: Int = 0,
    val printerIp: String = "",
    val printerPort: Int = 9100,
    val printerIppPort: Int = 631,
    val printerIppPath: String = "/ipp/print",
    val printFinish: PrintFinish = PrintFinish.GLOSSY,
    /** Simulate printing: jobs complete as SIMULATED without touching any printer. */
    val printDryRun: Boolean = false,
    val printerUsbVendorId: Int = 0,
    val printerUsbProductId: Int = 0,
    val cameraUsbVendorId: Int = 0x04A9,
    val cameraUsbProductId: Int = 0,
    val useBuiltInCameraFallback: Boolean = true,
    val ccapiHost: String = "",
    val ccapiPort: Int = 8080,
)

@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            isPinConfigured = p[PIN_HASH] != null,
            kioskModeEnabled = p[KIOSK_MODE] ?: false,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: true,
            hideSystemBars = p[HIDE_SYSTEM_BARS] ?: false,
            attractSlideshow = p[ATTRACT_SLIDESHOW] ?: true,
            printerModel = p[PRINTER_MODEL]?.let { runCatching { PrinterModel.valueOf(it) }.getOrNull() }
                ?: PrinterModel.DNP_DS_RX1HS,
            rollCapacity = p[ROLL_CAPACITY] ?: 700,
            rollBaselinePrints = p[ROLL_BASELINE] ?: 0,
            printerConnection = p[PRINTER_CONNECTION]?.let { runCatching { PrinterConnection.valueOf(it) }.getOrNull() }
                ?: PrinterConnection.NONE,
            printerIp = p[PRINTER_IP].orEmpty(),
            printerPort = p[PRINTER_PORT] ?: 9100,
            printerIppPort = p[PRINTER_IPP_PORT] ?: 631,
            printerIppPath = p[PRINTER_IPP_PATH] ?: "/ipp/print",
            printFinish = p[PRINT_FINISH]?.let { runCatching { PrintFinish.valueOf(it) }.getOrNull() } ?: PrintFinish.GLOSSY,
            printDryRun = p[PRINT_DRY_RUN] ?: false,
            printerUsbVendorId = p[PRINTER_USB_VID] ?: 0,
            printerUsbProductId = p[PRINTER_USB_PID] ?: 0,
            cameraUsbVendorId = p[CAMERA_USB_VID] ?: 0x04A9,
            cameraUsbProductId = p[CAMERA_USB_PID] ?: 0,
            useBuiltInCameraFallback = p[CAMERA_FALLBACK] ?: true,
            ccapiHost = p[CCAPI_HOST].orEmpty(),
            ccapiPort = p[CCAPI_PORT] ?: 8080,
        )
    }

    suspend fun setAdminPin(pin: String) {
        require(pin.length in 4..8 && pin.all(Char::isDigit)) { "PIN must be 4-8 digits" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = pbkdf2(pin, salt)
        store.edit { it[PIN_HASH] = "${b64(salt)}:${b64(hash)}" }
    }

    suspend fun verifyAdminPin(pin: String): Boolean {
        val stored = store.data.first()[PIN_HASH] ?: return false
        val (salt, expected) = stored.split(":").map { Base64.getDecoder().decode(it) }
        return java.security.MessageDigest.isEqual(pbkdf2(pin, salt), expected)
    }

    suspend fun setPrinter(connection: PrinterConnection, ip: String = "", port: Int = 9100, vendorId: Int = 0, productId: Int = 0) {
        store.edit {
            it[PRINTER_CONNECTION] = connection.name
            it[PRINTER_IP] = ip
            it[PRINTER_PORT] = port
            it[PRINTER_USB_VID] = vendorId
            it[PRINTER_USB_PID] = productId
        }
    }

    suspend fun setPrintOptions(ippPort: Int, ippPath: String, finish: PrintFinish, dryRun: Boolean) {
        store.edit {
            it[PRINTER_IPP_PORT] = ippPort.coerceIn(1, 65535)
            it[PRINTER_IPP_PATH] = if (ippPath.startsWith("/")) ippPath else "/$ippPath"
            it[PRINT_FINISH] = finish.name
            it[PRINT_DRY_RUN] = dryRun
        }
    }

    suspend fun setCamera(vendorId: Int, productId: Int, builtInFallback: Boolean) {
        store.edit {
            it[CAMERA_USB_VID] = vendorId
            it[CAMERA_USB_PID] = productId
            it[CAMERA_FALLBACK] = builtInFallback
        }
    }

    suspend fun ensureDefaultPin() {
        if (store.data.first()[PIN_HASH] == null) setAdminPin(DEFAULT_PIN)
    }

    suspend fun setKioskMode(enabled: Boolean) = store.edit { it[KIOSK_MODE] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) = store.edit { it[KEEP_SCREEN_ON] = enabled }

    suspend fun setHideSystemBars(enabled: Boolean) = store.edit { it[HIDE_SYSTEM_BARS] = enabled }

    suspend fun setAttractSlideshow(enabled: Boolean) = store.edit { it[ATTRACT_SLIDESHOW] = enabled }

    suspend fun setPrinterModel(model: PrinterModel) = store.edit { it[PRINTER_MODEL] = model.name }

    suspend fun setRollCapacity(capacity: Int) = store.edit { it[ROLL_CAPACITY] = capacity.coerceIn(1, 100_000) }

    suspend fun setRollBaseline(totalPrinted: Int) = store.edit { it[ROLL_BASELINE] = totalPrinted }

    suspend fun setCcapi(host: String, port: Int = 8080) {
        store.edit {
            it[CCAPI_HOST] = host
            it[CCAPI_PORT] = port
        }
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 120_000, 256))
            .encoded

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private companion object {
        const val DEFAULT_PIN = "0000"
        val KIOSK_MODE = booleanPreferencesKey("kiosk_mode")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val HIDE_SYSTEM_BARS = booleanPreferencesKey("hide_system_bars")
        val ATTRACT_SLIDESHOW = booleanPreferencesKey("attract_slideshow")
        val PRINTER_MODEL = stringPreferencesKey("printer_model")
        val ROLL_CAPACITY = intPreferencesKey("roll_capacity")
        val ROLL_BASELINE = intPreferencesKey("roll_baseline")
        val PIN_HASH = stringPreferencesKey("admin_pin_hash")
        val PRINTER_CONNECTION = stringPreferencesKey("printer_connection")
        val PRINTER_IP = stringPreferencesKey("printer_ip")
        val PRINTER_PORT = intPreferencesKey("printer_port")
        val PRINTER_IPP_PORT = intPreferencesKey("printer_ipp_port")
        val PRINTER_IPP_PATH = stringPreferencesKey("printer_ipp_path")
        val PRINT_FINISH = stringPreferencesKey("print_finish")
        val PRINT_DRY_RUN = booleanPreferencesKey("print_dry_run")
        val PRINTER_USB_VID = intPreferencesKey("printer_usb_vid")
        val PRINTER_USB_PID = intPreferencesKey("printer_usb_pid")
        val CAMERA_USB_VID = intPreferencesKey("camera_usb_vid")
        val CAMERA_USB_PID = intPreferencesKey("camera_usb_pid")
        val CAMERA_FALLBACK = booleanPreferencesKey("camera_builtin_fallback")
        val CCAPI_HOST = stringPreferencesKey("ccapi_host")
        val CCAPI_PORT = intPreferencesKey("ccapi_port")
    }
}
