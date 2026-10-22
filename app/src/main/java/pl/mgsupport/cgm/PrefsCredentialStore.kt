package pl.mgsupport.cgm

import android.content.Context
import pl.mgsupport.cgm.core.domain.pump.PumpCredentialProvider

/** SharedPreferences-backed pairing/credential store for the Medtronic driver. */
class PrefsCredentialStore(context: Context) : PumpCredentialProvider {

    private val prefs =
        context.applicationContext.getSharedPreferences("medtronic_pairing", Context.MODE_PRIVATE)

    override fun getPairedAddress(): String? = prefs.getString(KEY_ADDRESS, null)

    override fun getPairingCode(): String? = prefs.getString(KEY_CODE, null)

    override fun isPaired(): Boolean = getPairedAddress() != null

    override fun savePairing(address: String, pairingCode: String) {
        prefs.edit().putString(KEY_ADDRESS, address).putString(KEY_CODE, pairingCode).apply()
    }

    override fun clearPairing() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_CODE).apply()
    }

    override fun saveJpakeCredentials(derivedSecretHex: String, serverNonceHex: String) {
        prefs.edit()
            .putString(KEY_JPAKE_SECRET, derivedSecretHex)
            .putString(KEY_JPAKE_NONCE, serverNonceHex)
            .apply()
    }

    override fun getJpakeDerivedSecret(): String? = prefs.getString(KEY_JPAKE_SECRET, null)

    override fun getJpakeServerNonce(): String? = prefs.getString(KEY_JPAKE_NONCE, null)

    override fun clearJpakeCredentials() {
        prefs.edit().remove(KEY_JPAKE_SECRET).remove(KEY_JPAKE_NONCE).apply()
    }

    private companion object {
        const val KEY_ADDRESS = "paired_address"
        const val KEY_CODE = "pairing_code"
        const val KEY_JPAKE_SECRET = "jpake_secret"
        const val KEY_JPAKE_NONCE = "jpake_nonce"
    }
}
