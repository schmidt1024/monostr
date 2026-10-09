package com.monostr.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.monostr.app.R
import com.monostr.app.session.NostrSession
import com.monostr.app.ui.common.Avatar
import com.monostr.app.ui.common.LoadingScreen
import com.monostr.app.ui.common.UiText
import com.monostr.app.ui.common.asString
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileEditViewModel @Inject constructor(session: NostrSession) : ViewModel() {
    private val ready = session.requireReady()
    val controller = ProfileEditController(
        ready.pubkey, ready.profiles, ready.publish, viewModelScope, ready.media,
        removePictures = { ready.pictures.remove(it) }, background = ready.engine.backgroundScope,
    )
    init { controller.start() }
}

/** Spec 5: banner and picture as https URLs (with preview), display name, name, bio, NIP-05. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(onDone: () -> Unit, vm: ProfileEditViewModel = hiltViewModel()) {
    val state by vm.controller.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.asString()
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.controller.clearMessage() } }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    val imagesOnly = remember { PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly) }
    val pickBanner = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.controller.upload(PictureField.BANNER, it.toString()) }
    }
    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.controller.upload(PictureField.PICTURE, it.toString()) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_edit_title)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingScreen()
        } else {
            val f = state.form
            Column(
                Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.noProfileFound) {
                    Text(stringResource(R.string.profile_edit_no_profile), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("edit-no-profile"))
                }
                EditField(f.banner, R.string.profile_edit_banner, "edit-banner", invalid = state.bannerInvalid) { v -> vm.controller.update { it.copy(banner = v) } }
                PickRow(PictureField.BANNER, state, "edit-banner", "profile-banner-remove", R.string.profile_edit_remove_banner, f.banner.isNotBlank(), { vm.controller.remove(PictureField.BANNER) }) { pickBanner.launch(imagesOnly) }
                if (f.banner.isNotBlank() && !state.bannerInvalid) {
                    AsyncImage(
                        model = f.banner.trim(), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
                EditField(f.picture, R.string.profile_edit_picture, "edit-picture", invalid = state.pictureInvalid) { v -> vm.controller.update { it.copy(picture = v) } }
                PickRow(PictureField.PICTURE, state, "edit-picture", "profile-picture-remove", R.string.profile_edit_remove_picture, f.picture.isNotBlank(), { vm.controller.remove(PictureField.PICTURE) }) { pickPicture.launch(imagesOnly) }
                if (f.picture.isNotBlank() && !state.pictureInvalid) Avatar(f.picture.trim(), Modifier.size(64.dp))
                EditField(f.displayName, R.string.profile_edit_display_name, "edit-display-name") { v -> vm.controller.update { it.copy(displayName = v) } }
                EditField(f.name, R.string.profile_edit_name, "edit-name") { v -> vm.controller.update { it.copy(name = v) } }
                EditField(f.about, R.string.profile_edit_about, "edit-about", singleLine = false) { v -> vm.controller.update { it.copy(about = v) } }
                EditField(f.nip05, R.string.profile_edit_nip05, "edit-nip05") { v -> vm.controller.update { it.copy(nip05 = v) } }
                Button(onClick = vm.controller::save, enabled = state.canSave, modifier = Modifier.fillMaxWidth().testTag("edit-save")) {
                    Text(stringResource(if (state.saving) R.string.state_sending else R.string.profile_edit_save))
                }
            }
        }
    }
}

@Composable
private fun EditField(value: String, @StringRes label: Int, tag: String, invalid: Boolean = false, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        isError = invalid,
        supportingText = if (invalid) { { Text(stringResource(R.string.profile_url_invalid)) } } else null,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Spec 5.3 (Plan 10e): "choose picture" under a URL field, with the state of the upload beside it. */
@Composable
private fun PickRow(
    field: PictureField, state: ProfileEditUiState, tagPrefix: String,
    removeTag: String, @StringRes removeLabel: Int, filled: Boolean, onRemove: () -> Unit, onPick: () -> Unit,
) {
    val busy = state.uploading == field
    val error: UiText? = state.uploadError?.takeIf { it.first == field }?.second
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onPick, enabled = state.uploading == null && !state.saving, modifier = Modifier.testTag("$tagPrefix-pick")) {
                Text(stringResource(if (busy) R.string.profile_edit_uploading else R.string.profile_edit_pick))
            }
            if (filled) {
                TextButton(onClick = onRemove, enabled = state.uploading == null && !state.saving, modifier = Modifier.testTag(removeTag)) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(removeLabel))
                }
            }
        }
        if (error != null) {
            Text(error.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("$tagPrefix-upload-error"))
        }
    }
}
