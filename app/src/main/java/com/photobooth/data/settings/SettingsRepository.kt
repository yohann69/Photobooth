package com.photobooth.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

data class AppSettings(
    val isPinConfigured: Boolean = false,
    val kioskModeEnabled: Boolean = false,
    val keepScreenOn: Boolean = true,
    val hideSystemBars: Boolean = false,
    /** Lifetime printed copies at the moment the current roll was installed. */
    val rollBaselinePrints: Int = 0,
    /** Simulate printing: jobs complete as SIMULATED without touching any printer. */
    val printDryRun: Boolean = false,
    /** How the montage is laid on the DNP raster; adjusted with the test sheets if prints come out flipped. */
    val dnpFlipVertical: Boolean = true,
    val dnpMirror: Boolean = false,
    val dnpPortraitClockwise: Boolean = true,
    val ccapiHost: String = "",
    val ccapiPort: Int = 8080,
    /** Online gallery server; uploads are skipped while either field is empty. */
    val uploadBaseUrl: String = "",
    val uploadToken: String = "",
) {
    val uploadConfigured: Boolean get() = uploadBaseUrl.isNotBlank() && uploadToken.isNotBlank()
}

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
            rollBaselinePrints = p[ROLL_BASELINE] ?: 0,
            printDryRun = p[PRINT_DRY_RUN] ?: false,
            dnpFlipVertical = p[DNP_FLIP_VERTICAL] ?: true,
            dnpMirror = p[DNP_MIRROR] ?: false,
            dnpPortraitClockwise = p[DNP_PORTRAIT_CW] ?: true,
            ccapiHost = p[CCAPI_HOST].orEmpty(),
            ccapiPort = p[CCAPI_PORT] ?: 8080,
            uploadBaseUrl = p[UPLOAD_BASE_URL].orEmpty(),
            uploadToken = p[UPLOAD_TOKEN].orEmpty(),
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

    suspend fun setDryRun(enabled: Boolean) = store.edit { it[PRINT_DRY_RUN] = enabled }

    suspend fun setDnpOrientation(flipVertical: Boolean, mirror: Boolean, portraitClockwise: Boolean) {
        store.edit {
            it[DNP_FLIP_VERTICAL] = flipVertical
            it[DNP_MIRROR] = mirror
            it[DNP_PORTRAIT_CW] = portraitClockwise
        }
    }

    suspend fun ensureDefaultPin() {
        if (store.data.first()[PIN_HASH] == null) setAdminPin(DEFAULT_PIN)
    }

    suspend fun setKioskMode(enabled: Boolean) = store.edit { it[KIOSK_MODE] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) = store.edit { it[KEEP_SCREEN_ON] = enabled }

    suspend fun setHideSystemBars(enabled: Boolean) = store.edit { it[HIDE_SYSTEM_BARS] = enabled }

    suspend fun setRollBaseline(totalPrinted: Int) = store.edit { it[ROLL_BASELINE] = totalPrinted }

    suspend fun setCcapi(host: String, port: Int = 8080) {
        store.edit {
            it[CCAPI_HOST] = host
            it[CCAPI_PORT] = port
        }
    }

    suspend fun setUpload(baseUrl: String, token: String) {
        store.edit {
            it[UPLOAD_BASE_URL] = baseUrl.trim().trimEnd('/')
            it[UPLOAD_TOKEN] = token.trim()
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
        val ROLL_BASELINE = intPreferencesKey("roll_baseline")
        val PIN_HASH = stringPreferencesKey("admin_pin_hash")
        val PRINT_DRY_RUN = booleanPreferencesKey("print_dry_run")
        val DNP_FLIP_VERTICAL = booleanPreferencesKey("dnp_flip_vertical")
        val DNP_MIRROR = booleanPreferencesKey("dnp_mirror")
        val DNP_PORTRAIT_CW = booleanPreferencesKey("dnp_portrait_cw")
        val CCAPI_HOST = stringPreferencesKey("ccapi_host")
        val CCAPI_PORT = intPreferencesKey("ccapi_port")
        val UPLOAD_BASE_URL = stringPreferencesKey("upload_base_url")
        val UPLOAD_TOKEN = stringPreferencesKey("upload_token")
    }
}
