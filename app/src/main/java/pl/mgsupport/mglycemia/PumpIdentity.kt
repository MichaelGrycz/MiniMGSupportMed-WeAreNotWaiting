package pl.mgsupport.mglycemia

import android.content.Context
import java.security.SecureRandom

/**
 * Stable per-install BLE identity advertised to the pump as a `Mobile NNNNNN` name.
 *
 * A single fixed name (`Mobile 000001`) makes every installation look like the same mobile device.
 * When a pump still holds an old entry with that name and different link keys, Android 16 disconnects
 * the link as soon as authentication fails. A random, persisted suffix makes each installation a
 * distinct mobile device, and [rotate] gives the user a clean identity when a stale entry must be
 * abandoned.
 */
object PumpIdentity {

    private const val PREFS = "pump_identity"
    private const val KEY_NAME = "local_name"
    private const val PREFIX = "Mobile "
    private const val DIGITS = 6
    private const val LEGACY_NAME = "Mobile 000001"

    /** The persisted identity, created on first use. */
    fun localName(context: Context, paired: Boolean): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_NAME, null)
        if (existing != null && isValid(existing)) return existing
        // Existing installations were paired under the legacy name; keeping it avoids breaking a
        // working pump entry on upgrade. A fresh installation gets a unique identity instead.
        return persist(prefs, if (paired) LEGACY_NAME else generate())
    }

    /** Replace the identity with a fresh unused suffix and return it. */
    fun rotate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return persist(prefs, generate(prefs.getString(KEY_NAME, null)))
    }

    private fun persist(prefs: android.content.SharedPreferences, name: String): String {
        prefs.edit().putString(KEY_NAME, name).apply()
        return name
    }

    /** True for the only shape the pump accepts: `Mobile ` plus up to seven characters. */
    internal fun isValid(name: String): Boolean = name.matches(Regex("Mobile \\d{6}"))

    /** Random six-digit suffix, never equal to [excluding] or the legacy shared name. */
    internal fun generate(excluding: String? = null): String {
        val random = SecureRandom()
        while (true) {
            val name = PREFIX + (random.nextInt(999_999) + 1).toString().padStart(DIGITS, '0')
            if (name != excluding && name != LEGACY_NAME) return name
        }
    }
}
