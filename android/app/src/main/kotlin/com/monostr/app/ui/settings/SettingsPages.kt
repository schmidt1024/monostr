package com.monostr.app.ui.settings

import androidx.annotation.StringRes
import com.monostr.app.R

/** Sections [SettingsOverview] can be opened at (the route's `section` argument); the values are [SettingsPage] ids, opened on top of the overview. */
object SettingsSection {
    const val TRANSLATION = "translation"
    const val PRIVACY = "privacy"
}

/** The subpages of Settings (spec 2026-11-11). [parent] is set for second-level pages. */
enum class SettingsPage(val id: String, @StringRes val title: Int, @StringRes val subtitle: Int?, val parent: SettingsPage? = null) {
    ACCOUNT("account", R.string.settings_page_account, null),
    PRIVACY("privacy", R.string.settings_page_privacy, R.string.settings_sub_privacy),
    RELAYS("relays", R.string.settings_relays, R.string.settings_sub_relays),
    DM_RELAYS("dm-relays", R.string.settings_dm_relays, null, parent = RELAYS),
    SEARCH_RELAYS("search-relays", R.string.settings_search_relays, null, parent = RELAYS),
    MONERO("monero", R.string.settings_page_monero, R.string.settings_sub_monero),
    NOTIFICATIONS("notifications", R.string.settings_page_notifications, R.string.settings_sub_notifications),
    CONTENT("content", R.string.settings_page_content, R.string.settings_sub_content),
    REACTIONS("reactions", R.string.settings_quick_reactions, null, parent = CONTENT),
    TRANSLATION("translation", R.string.settings_translate, R.string.settings_sub_translation),
    APPEARANCE("appearance", R.string.settings_appearance, R.string.settings_sub_appearance),
    HELP("help", R.string.settings_page_help, R.string.settings_sub_help),
    ;

    companion object {
        fun of(id: String?): SettingsPage? = entries.firstOrNull { it.id == id }
    }
}

/** The overview's category cards (round 2, owner decision 1), in order; the account is its own card above them. */
val OVERVIEW_GROUPS: List<List<SettingsPage>> = listOf(
    listOf(SettingsPage.PRIVACY, SettingsPage.RELAYS, SettingsPage.MONERO),
    listOf(SettingsPage.NOTIFICATIONS, SettingsPage.CONTENT, SettingsPage.TRANSLATION, SettingsPage.APPEARANCE),
    listOf(SettingsPage.HELP),
)

/** Category rows of the overview, in order. */
val OVERVIEW_PAGES: List<SettingsPage> = OVERVIEW_GROUPS.flatten()

/**
 * What decides whether a conditional control is on its page at all: the login kind, the Monero setup (none, payto
 * only, a full setup, a stored view key) and the translation mode. Search offers only controls that are composed.
 */
data class SettingsAvailability(
    val isAmber: Boolean = false,
    val hasSetup: Boolean = false,
    /** No setup on this device, but a payto address (and no announced setup): the page shows «Change» / «Remove». */
    val paytoOnly: Boolean = false,
    val hasViewKey: Boolean = false,
    val translateCustom: Boolean = false,
)

/**
 * One searchable setting. [key] is the control's test tag where it has one (otherwise a new `settings-<name>`);
 * it is what a search result scrolls to. [synonyms] are plain English/German terms the UI does not show.
 * [available] mirrors the condition under which its page composes the control.
 */
data class SettingEntry(
    val key: String,
    val page: SettingsPage,
    @StringRes val title: Int,
    @StringRes val hint: Int? = null,
    val synonyms: List<String> = emptyList(),
    val available: (SettingsAvailability) -> Boolean = { true },
)

private fun page(p: SettingsPage, vararg syn: String) = SettingEntry("page-${p.id}", p, p.title, p.subtitle, syn.toList())

/** Every setting of the subpage table, in page order, plus one entry per page. Static: built from resources only. */
val SETTINGS_INDEX: List<SettingEntry> = listOf(
    page(SettingsPage.ACCOUNT, "key", "nsec", "npub", "amber", "konto", "schlüssel", "login"),
    SettingEntry("settings-show-key", SettingsPage.ACCOUNT, R.string.settings_show_key, null, listOf("nsec", "private key", "schlüssel", "backup")) { !it.isAmber },
    SettingEntry("settings-amber-renew", SettingsPage.ACCOUNT, R.string.settings_amber_renew, null, listOf("signer", "permission", "berechtigung")) { it.isAmber },

    page(SettingsPage.PRIVACY, "onion", "ip", "datenschutz", "privatsphäre"),
    SettingEntry("settings-tor", SettingsPage.PRIVACY, R.string.tor_connect, R.string.tor_orbot, listOf("onion", "proxy", "ip", "zwiebel", "orbot", "anonym")),
    SettingEntry("settings-ask-links", SettingsPage.PRIVACY, R.string.settings_ask_links, R.string.settings_ask_links_hint, listOf("url", "browser", "warning", "warnung")),
    SettingEntry("settings-link-previews", SettingsPage.PRIVACY, R.string.settings_link_previews, R.string.settings_link_previews_hint, listOf("preview", "vorschau", "url")),
    SettingEntry("settings-client-tag", SettingsPage.PRIVACY, R.string.settings_client_tag, R.string.settings_client_tag_hint, listOf("client", "via")),
    SettingEntry("settings-muted", SettingsPage.PRIVACY, R.string.mute_settings_title, null, listOf("mute", "block", "blocked", "stumm", "blockiert")),

    page(SettingsPage.RELAYS, "nip-65", "server"),
    SettingEntry("settings-relay-input", SettingsPage.RELAYS, R.string.settings_relay_add, R.string.settings_suggested_relays_hint, listOf("wss", "server", "add", "hinzufügen", "publish", "reset")),
    SettingEntry("settings-author-relays", SettingsPage.RELAYS, R.string.settings_author_relays, R.string.settings_author_relays_hint, listOf("outbox", "nip-65")),
    SettingEntry("settings-pow", SettingsPage.RELAYS, R.string.settings_pow, R.string.settings_pow_hint, listOf("pow", "nip-13", "mining")),

    page(SettingsPage.DM_RELAYS, "dm", "inbox", "nip-17", "nachrichten"),
    SettingEntry("settings-dm-relay-input", SettingsPage.DM_RELAYS, R.string.settings_dm_relay_add, R.string.settings_dm_relays_hint, listOf("dm", "inbox", "nip-17", "10050", "message")),

    page(SettingsPage.SEARCH_RELAYS, "nip-50", "suche"),
    SettingEntry("settings-search-relays-reset", SettingsPage.SEARCH_RELAYS, R.string.settings_search_relays_reset_entry, R.string.settings_search_relays_hint, listOf("nip-50", "default", "standard")),

    page(SettingsPage.MONERO, "xmr", "wallet", "tip", "tips", "zap", "bezahlen"),
    SettingEntry("settings-monero-setup", SettingsPage.MONERO, R.string.settings_monero_set_up, R.string.settings_monero_wallet_hint, listOf("xmr", "wallet", "watcher", "tip", "receive", "empfangen")) { !it.hasSetup && !it.paytoOnly },
    SettingEntry("settings-monero-watcher", SettingsPage.MONERO, R.string.settings_monero_switch_watcher, null, listOf("watcher", "personal", "persönlich")) { it.hasSetup },
    SettingEntry("settings-monero-amounts", SettingsPage.MONERO, R.string.settings_monero_amounts_public, R.string.settings_monero_amounts_public_hint, listOf("hidden", "amount", "betrag", "versteckt")) { it.hasSetup },
    SettingEntry("settings-amount-unit", SettingsPage.MONERO, R.string.settings_amount_unit, R.string.settings_amount_unit_hint, listOf("xmr", "mxmr", "piconero", "einheit")),
    SettingEntry("settings-presets-save", SettingsPage.MONERO, R.string.settings_tip_amounts, null, listOf("presets", "amount", "betrag", "vorgaben")),
    SettingEntry("settings-monero-payto", SettingsPage.MONERO, R.string.settings_label_payto, null, listOf("payto", "address", "adresse")) { !it.hasSetup && it.paytoOnly },
    SettingEntry("settings-monero-disable", SettingsPage.MONERO, R.string.settings_monero_disable, null, listOf("off", "aus", "remove", "entfernen")) { it.hasSetup },
    SettingEntry("settings-monero-delete-view-key", SettingsPage.MONERO, R.string.settings_monero_delete_view_key, null, listOf("view key", "delete", "löschen")) { it.hasViewKey },

    page(SettingsPage.NOTIFICATIONS, "benachrichtigung", "push"),
    SettingEntry("settings-dm-preview-name", SettingsPage.NOTIFICATIONS, R.string.settings_dm_preview_name, null, listOf("dm", "sender", "absender", "preview")),
    SettingEntry("settings-dm-preview-text", SettingsPage.NOTIFICATIONS, R.string.settings_dm_preview_text, null, listOf("dm", "message", "nachricht", "preview", "vorschau")),

    page(SettingsPage.CONTENT, "bilder", "media", "medien"),
    SettingEntry("settings-media-on-tap", SettingsPage.CONTENT, R.string.settings_media_on_tap, R.string.settings_media_on_tap_hint, listOf("data", "daten", "images", "bilder", "load")),
    SettingEntry("settings-media-blur", SettingsPage.CONTENT, R.string.settings_media_blur, R.string.settings_media_blur_hint, listOf("nsfw", "blur", "unscharf", "sensitive")),
    SettingEntry("settings-gif", SettingsPage.CONTENT, R.string.settings_gif, R.string.settings_gif_hint, listOf("animation", "animated", "autoplay")),
    SettingEntry("settings-media-server", SettingsPage.CONTENT, R.string.settings_media_server, R.string.settings_media_server_hint, listOf("blossom", "upload", "hochladen")),
    SettingEntry("settings-pow-filter", SettingsPage.CONTENT, R.string.settings_pow_filter, R.string.settings_pow_filter_hint, listOf("pow", "nip-13", "spam", "filter")),

    page(SettingsPage.REACTIONS, "emoji", "reactions", "reaktionen"),
    SettingEntry("settings-reaction-input", SettingsPage.REACTIONS, R.string.settings_reactions, R.string.settings_reactions_hint, listOf("emoji", "like", "heart", "herz")),

    page(SettingsPage.TRANSLATION, "libretranslate", "übersetzen", "translate"),
    SettingEntry("settings-translate-mode", SettingsPage.TRANSLATION, R.string.settings_translate_service, R.string.settings_translate_hint, listOf("libretranslate", "monostr", "off", "aus", "übersetzen")),
    SettingEntry("settings-translate-server", SettingsPage.TRANSLATION, R.string.settings_translate_custom, R.string.settings_translate_hint, listOf("libretranslate", "server", "url")) { it.translateCustom },

    page(SettingsPage.APPEARANCE, "dark", "dunkel", "theme", "farbe", "design", "black", "schwarz"),
    SettingEntry("settings-theme-mode", SettingsPage.APPEARANCE, R.string.settings_theme_mode, null, listOf("dark", "light", "black", "system", "hell", "dunkel", "schwarz", "amoled", "theme")),
    SettingEntry("settings-accent", SettingsPage.APPEARANCE, R.string.settings_accent, null, listOf("color", "colour", "farbe", "theme", "hue")),
    SettingEntry("appearance-language", SettingsPage.APPEARANCE, R.string.settings_language, R.string.settings_language_text, listOf("sprache", "locale")),

    page(SettingsPage.HELP, "hilfe", "info", "donate", "spenden"),
    SettingEntry("settings-support", SettingsPage.HELP, R.string.support_title, null, listOf("donate", "spenden", "help", "hilfe")),
    SettingEntry("settings-about", SettingsPage.HELP, R.string.about_title, null, listOf("version", "licence", "license", "lizenz", "source")),
)
