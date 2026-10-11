package com.monostr.app.data

import java.math.BigDecimal
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.monostr.monero.MoneroUri
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** The user's Monero receiving setup as registered at a watcher (spec 5.8 "Watcher-Registrierungsstatus"). */
data class MoneroSetup(
    val address: String,
    val watcherUrl: String,
    val watcherPubkey: String,
    /** `mainnet` or `stagenet`, as reported by the watcher. */
    val network: String,
    val registeredAt: Long,
)

/** How amounts read and are typed (Settings → Monero); inside, everything stays piconero. */
enum class AmountUnit(val label: String, val decimals: Int) {
    XMR("XMR", 12),
    /** Milli-monero, as Stasher shows tips: 0.001 XMR = 1 mXMR. */
    MXMR("mXMR", 9),
}

/** Tip presets (spec 6.6) and amount formatting for the UI. All amounts in piconero. */
object Presets {
    /** Powers of ten: the amounts users convert in their heads fastest (user decision 2026-09-24). */
    val DEFAULT: List<Long> = listOf(100_000_000L, 1_000_000_000L, 10_000_000_000L)
    const val DEFAULT_WATCHER = "https://watcher.monostr.com"
    const val MAX = 6

    /** Parses decimal strings in [unit] (comma allowed); blanks are dropped, duplicates merged, input order kept. Null when any value is invalid, none is left or more than [MAX]. */
    fun parseAll(inputs: List<String>, unit: AmountUnit = AmountUnit.XMR): List<Long>? {
        val values = ArrayList<Long>()
        for (raw in inputs) {
            if (raw.isBlank()) continue
            values += parse(raw, unit) ?: return null
        }
        val distinct = values.distinct()
        if (distinct.isEmpty() || distinct.size > MAX) return null
        return distinct
    }

    /** Piconero of a positive decimal amount typed in [unit] (comma allowed); null for anything else, also below one piconero. */
    fun parse(raw: String, unit: AmountUnit = AmountUnit.XMR): Long? {
        val t = raw.trim().replace(',', '.')
        if (!DECIMAL.matches(t)) return null
        val v = runCatching { BigDecimal(t).movePointRight(unit.decimals) }.getOrNull() ?: return null
        if (v.signum() <= 0 || v.stripTrailingZeros().scale() > 0) return null
        return runCatching { v.longValueExact() }.getOrNull()
    }

    /** The number alone, in [unit]: "0.001" (XMR) or "1" (mXMR). */
    fun format(piconero: Long, unit: AmountUnit = AmountUnit.XMR): String =
        BigDecimal.valueOf(piconero).movePointLeft(unit.decimals).stripTrailingZeros().toPlainString()

    /** The number with its label: "1 mXMR". */
    fun withUnit(piconero: Long, unit: AmountUnit): String = "${format(piconero, unit)} ${unit.label}"

    private val DECIMAL = Regex("^[0-9]+(\\.[0-9]+)?$") // regex
}

interface TipSettingsStore {
    val presets: Flow<List<Long>>
    val setup: Flow<MoneroSetup?>
    val onboardingSeen: Flow<Boolean>
    /** Notifications created after this instant count as unread. */
    val notificationsReadAt: Flow<Long>
    /** Whether the tip sheet starts with "tip anonymously" on; on until the user switches it off. */
    val anonymous: Flow<Boolean>
    /** XMR (default) or mXMR for every amount shown or typed. */
    val amountUnit: Flow<AmountUnit>
    suspend fun setAmountUnit(unit: AmountUnit)
    suspend fun setPresets(piconero: List<Long>)
    suspend fun setSetup(setup: MoneroSetup?)
    suspend fun setOnboardingSeen()
    suspend fun setAnonymous(value: Boolean)
    suspend fun setNotificationsReadAt(at: Long)
    /** Watermark of the background worker: tips up to this instant were already notified. */
    suspend fun lastTipNotifiedAt(): Long
    suspend fun setLastTipNotifiedAt(at: Long)
    /** Receipt ids the worker already notified (spec 5.7; same-second receipts are refetched, see TipChecker). */
    suspend fun notifiedReceiptIds(): Set<String>
    suspend fun setNotifiedReceiptIds(ids: Set<String>)
}

class PrefsTipSettingsStore(private val store: DataStore<Preferences>) : TipSettingsStore {
    /** A failed read yields the defaults instead of crashing the collectors (the official DataStore pattern). */
    private val data: Flow<Preferences> = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    override val presets: Flow<List<Long>> = data.map { p ->
        p[PRESETS]?.split(',')?.mapNotNull { it.toLongOrNull() }?.filter { it > 0 }?.sorted()?.takeIf { it.isNotEmpty() } ?: Presets.DEFAULT
    }

    override val setup: Flow<MoneroSetup?> = data.map { p ->
        val address = p[SETUP_ADDRESS] ?: return@map null
        MoneroSetup(
            address = address,
            watcherUrl = p[SETUP_WATCHER_URL] ?: return@map null,
            watcherPubkey = p[SETUP_WATCHER_PUBKEY] ?: return@map null,
            network = p[SETUP_NETWORK] ?: return@map null,
            registeredAt = p[SETUP_REGISTERED_AT] ?: 0L,
        )
    }

    override val onboardingSeen: Flow<Boolean> = data.map { it[ONBOARDING_SEEN] ?: false }
    override val notificationsReadAt: Flow<Long> = data.map { it[NOTIFICATIONS_READ_AT] ?: 0L }
    override val anonymous: Flow<Boolean> = data.map { it[ANONYMOUS] ?: true }
    override val amountUnit: Flow<AmountUnit> = data.map { p -> AmountUnit.entries.firstOrNull { it.name == p[AMOUNT_UNIT] } ?: AmountUnit.XMR }
    override suspend fun setAmountUnit(unit: AmountUnit) { store.edit { it[AMOUNT_UNIT] = unit.name } }

    override suspend fun setPresets(piconero: List<Long>) {
        store.edit { it[PRESETS] = piconero.filter { v -> v > 0 }.distinct().sorted().joinToString(",") }
    }

    override suspend fun setSetup(setup: MoneroSetup?) {
        store.edit { p ->
            if (setup == null) {
                p.remove(SETUP_ADDRESS); p.remove(SETUP_WATCHER_URL); p.remove(SETUP_WATCHER_PUBKEY); p.remove(SETUP_NETWORK); p.remove(SETUP_REGISTERED_AT)
            } else {
                p[SETUP_ADDRESS] = setup.address; p[SETUP_WATCHER_URL] = setup.watcherUrl; p[SETUP_WATCHER_PUBKEY] = setup.watcherPubkey
                p[SETUP_NETWORK] = setup.network; p[SETUP_REGISTERED_AT] = setup.registeredAt
            }
        }
    }

    override suspend fun setOnboardingSeen() { store.edit { it[ONBOARDING_SEEN] = true } }
    override suspend fun setNotificationsReadAt(at: Long) { store.edit { it[NOTIFICATIONS_READ_AT] = at } }
    override suspend fun setAnonymous(value: Boolean) { store.edit { it[ANONYMOUS] = value } }
    override suspend fun lastTipNotifiedAt(): Long = data.first()[LAST_TIP_NOTIFIED_AT] ?: 0L
    override suspend fun setLastTipNotifiedAt(at: Long) { store.edit { it[LAST_TIP_NOTIFIED_AT] = at } }
    override suspend fun notifiedReceiptIds(): Set<String> = data.first()[NOTIFIED_RECEIPTS] ?: emptySet()
    override suspend fun setNotifiedReceiptIds(ids: Set<String>) { store.edit { it[NOTIFIED_RECEIPTS] = ids } }

    private companion object {
        val PRESETS = stringPreferencesKey("tips.presets")
        val SETUP_ADDRESS = stringPreferencesKey("monero.address")
        val SETUP_WATCHER_URL = stringPreferencesKey("monero.watcher_url")
        val SETUP_WATCHER_PUBKEY = stringPreferencesKey("monero.watcher_pubkey")
        val SETUP_NETWORK = stringPreferencesKey("monero.network")
        val SETUP_REGISTERED_AT = longPreferencesKey("monero.registered_at")
        val ONBOARDING_SEEN = booleanPreferencesKey("monero.onboarding_seen")
        val NOTIFICATIONS_READ_AT = longPreferencesKey("notifications.read_at")
        val ANONYMOUS = booleanPreferencesKey("tips.anonymous")
        val AMOUNT_UNIT = stringPreferencesKey("tips.amount_unit")
        val LAST_TIP_NOTIFIED_AT = longPreferencesKey("notifications.last_tip_notified_at")
        val NOTIFIED_RECEIPTS = stringSetPreferencesKey("notifications.notified_receipts")
    }
}
