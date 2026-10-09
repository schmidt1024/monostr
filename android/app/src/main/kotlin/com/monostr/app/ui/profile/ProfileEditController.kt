package com.monostr.app.ui.profile

import com.monostr.app.R
import com.monostr.app.media.ImageTarget
import com.monostr.app.media.MediaUploader
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.uiText
import com.monostr.app.ui.common.uploadMessage
import com.monostr.nostr.model.ProfileJson
import com.monostr.nostr.repo.ProfileRepository
import com.monostr.nostr.repo.PublishRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The six editable kind 0 fields as the form shows them. */
data class ProfileForm(
    val banner: String = "",
    val picture: String = "",
    val displayName: String = "",
    val name: String = "",
    val about: String = "",
    val nip05: String = "",
) {
    fun toFields(): Map<String, String> = mapOf(
        "banner" to banner, "picture" to picture, "display_name" to displayName,
        "name" to name, "about" to about, "nip05" to nip05,
    )

    companion object {
        fun from(fields: Map<String, String>) = ProfileForm(
            banner = fields["banner"].orEmpty(), picture = fields["picture"].orEmpty(), displayName = fields["display_name"].orEmpty(),
            name = fields["name"].orEmpty(), about = fields["about"].orEmpty(), nip05 = fields["nip05"].orEmpty(),
        )
    }
}

/** The two picture fields of the form that can be filled by an upload (Plan 10e). */
enum class PictureField { BANNER, PICTURE }

data class ProfileEditUiState(
    val form: ProfileForm = ProfileForm(),
    val original: ProfileForm = ProfileForm(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val done: Boolean = false,
    /** The load found no kind 0 for the own key: saving publishes a new profile (a notice says so). */
    val noProfileFound: Boolean = false,
    val message: UiText? = null,
    /** The field a picture is being uploaded for; saving waits for it. */
    val uploading: PictureField? = null,
    /** The latest failed upload and why. */
    val uploadError: Pair<PictureField, UiText>? = null,
) {
    /** Spec 8 (10c backlog): only a field the user changed is validated; an old value another client wrote stays as it is. */
    val bannerInvalid: Boolean get() = form.banner.trim() != original.banner.trim() && !ProfileEditController.urlOk(form.banner)
    val pictureInvalid: Boolean get() = form.picture.trim() != original.picture.trim() && !ProfileEditController.urlOk(form.picture)
    /** Spec 5: something changed, nothing is invalid, and no picture is on its way. */
    val canSave: Boolean get() = !loading && !saving && uploading == null && form != original && !bannerInvalid && !pictureInvalid
}

/**
 * Spec 5: edits the own kind 0. Saving loads the stored JSON again, replaces only the six editable
 * keys and publishes; success is "a relay accepted it", then the profile cache is refreshed. On
 * failure the form stays as typed and a snackbar reports it.
 */
class ProfileEditController(
    private val self: String,
    private val profiles: ProfileRepository,
    private val publish: PublishRepository,
    private val scope: CoroutineScope,
    private val uploader: MediaUploader? = null,
    /** Spec 8: asked to delete the previous pictures from the media server once the new profile is published; it decides what may go. */
    private val removePictures: suspend (List<String>) -> Unit = {},
    /** Outlives the screen, which closes after saving. */
    private val background: CoroutineScope = scope,
) {
    private val _state = MutableStateFlow(ProfileEditUiState())
    val state: StateFlow<ProfileEditUiState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            val raw = guarded { profiles.rawMetadata(self) }
            val form = ProfileForm.from(ProfileJson.fields(raw))
            _state.update { it.copy(form = form, original = form, loading = false, noProfileFound = raw == null) }
        }
    }

    fun update(transform: (ProfileForm) -> ProfileForm) = _state.update { it.copy(form = transform(it.form)) }

    /** Spec 8: empties [field]; it takes effect with "Save" (kind 0 without the key). */
    fun remove(field: PictureField) = update { if (field == PictureField.BANNER) it.copy(banner = "") else it.copy(picture = "") }

    /** Spec 5.3 (Plan 10e): uploads the picked picture and writes its URL into [field]; one upload at a time. */
    fun upload(field: PictureField, source: String) {
        val up = uploader ?: return
        if (_state.value.uploading != null) return
        _state.update { it.copy(uploading = field, uploadError = null) }
        scope.launch {
            try {
                val media = up.upload(source, if (field == PictureField.BANNER) ImageTarget.BANNER else ImageTarget.AVATAR)
                _state.update { s ->
                    s.copy(uploading = null, form = if (field == PictureField.BANNER) s.form.copy(banner = media.url) else s.form.copy(picture = media.url))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(uploading = null, uploadError = field to e.uploadMessage()) }
            }
        }
    }

    fun save() {
        val s = _state.value
        if (!s.canSave) return
        _state.update { it.copy(saving = true, message = null) }
        scope.launch {
            val ok = try {
                // merge onto the stored kind 0 as it is now, and only the fields the user changed: untouched fields
                // (also ones the load missed or another client changed meanwhile) and foreign keys stay as stored
                val before = s.original.toFields()
                val changed = s.form.toFields().filter { (k, v) -> v.trim() != before[k].orEmpty().trim() }
                // spec 8: the newest kind 0 from the relays (3 s), the database as fallback
                val stored = profiles.freshMetadata(self)
                if (stored != null) _state.update { it.copy(noProfileFound = false) }
                val json = ProfileJson.merge(stored, changed)
                publish.profile(json).sentToAny
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (ok) {
                guarded { profiles.invalidate(self) }
                // whether emptied or replaced: what the new profile no longer names in either field may leave the server
                val now = setOf(s.form.banner.trim(), s.form.picture.trim())
                val previous = listOf(s.original.banner.trim(), s.original.picture.trim()).filter { it.isNotEmpty() && it !in now }
                if (previous.isNotEmpty()) {
                    background.launch {
                        try {
                            removePictures(previous)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // the picture then stays on the server (spec 12)
                        }
                    }
                }
                _state.update { it.copy(saving = false, done = true, original = it.form) }
            } else {
                _state.update { it.copy(saving = false, message = uiText(R.string.profile_save_failed)) }
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val HTTPS = "https://"

        /** Spec 5: empty, or an https URL without whitespace. */
        fun urlOk(value: String): Boolean {
            val v = value.trim()
            return v.isEmpty() || (v.startsWith(HTTPS) && v.length > HTTPS.length && v.none { it.isWhitespace() })
        }
    }
}
