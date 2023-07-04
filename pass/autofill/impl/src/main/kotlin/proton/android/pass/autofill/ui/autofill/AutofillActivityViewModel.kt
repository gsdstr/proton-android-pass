/*
 * Copyright (c) 2023 Proton AG
 * This file is part of Proton AG and Proton Pass.
 *
 * Proton Pass is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Pass is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Pass.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.pass.autofill.ui.autofill

import android.view.autofill.AutofillId
import androidx.activity.ComponentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import proton.android.pass.account.api.AccountOrchestrators
import proton.android.pass.account.api.Orchestrator
import proton.android.pass.autofill.entities.AndroidAutofillFieldId
import proton.android.pass.autofill.entities.AutofillAppState
import proton.android.pass.autofill.entities.AutofillItem
import proton.android.pass.autofill.entities.FieldType
import proton.android.pass.autofill.entities.isValid
import proton.android.pass.autofill.extensions.deserializeParcelable
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_APP_NAME
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_AUTOFILL_IDS
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_AUTOFILL_IS_FOCUSED
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_AUTOFILL_PARENT_ID
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_AUTOFILL_TYPES
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_INLINE_SUGGESTION_AUTOFILL_ITEM
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_PACKAGE_NAME
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_TITLE
import proton.android.pass.autofill.ui.autofill.AutofillActivity.Companion.ARG_WEB_DOMAIN
import proton.android.pass.autofill.ui.autofill.AutofillUiState.NotValidAutofillUiState
import proton.android.pass.autofill.ui.autofill.AutofillUiState.StartAutofillUiState
import proton.android.pass.autofill.ui.autofill.AutofillUiState.UninitialisedAutofillUiState
import proton.android.pass.biometry.NeedsBiometricAuth
import proton.android.pass.common.api.Option
import proton.android.pass.common.api.toOption
import proton.android.pass.commonuimodels.api.PackageInfoUi
import proton.android.pass.preferences.HasAuthenticated
import proton.android.pass.preferences.ThemePreference
import proton.android.pass.preferences.UserPreferencesRepository
import proton.android.pass.preferences.value
import javax.inject.Inject

@HiltViewModel
class AutofillActivityViewModel @Inject constructor(
    private val accountOrchestrators: AccountOrchestrators,
    private val preferenceRepository: UserPreferencesRepository,
    needsBiometricAuth: NeedsBiometricAuth,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val packageInfo = savedStateHandle.get<String>(ARG_PACKAGE_NAME)
        .toOption()
        .map { packageName ->
            PackageInfoUi(
                packageName = packageName,
                appName = savedStateHandle.get<String>(ARG_APP_NAME) ?: packageName
            )
        }
    private val webDomain = savedStateHandle.get<String>(ARG_WEB_DOMAIN)
        .toOption()
    private val title = savedStateHandle.get<String>(ARG_TITLE)
        .toOption()
    private val types = savedStateHandle.get<List<String>>(ARG_AUTOFILL_TYPES)
        .toOption()
        .map { list -> list.map(FieldType.Companion::from) }
    private val ids = savedStateHandle.get<List<AutofillId>>(ARG_AUTOFILL_IDS)
        .toOption()
        .map { list -> list.map { AndroidAutofillFieldId(it) } }
    private val fieldIsFocusedList = savedStateHandle.get<List<Boolean>>(ARG_AUTOFILL_IS_FOCUSED)
        .toOption()
    private val parentIdList = savedStateHandle.get<List<AutofillId>>(ARG_AUTOFILL_PARENT_ID)
        .toOption()
        .map { list -> list.map { AndroidAutofillFieldId(it) } }

    private val autofillAppState: MutableStateFlow<AutofillAppState> =
        MutableStateFlow(
            AutofillAppState(
                packageInfoUi = packageInfo.value(),
                androidAutofillIds = ids.value() ?: emptyList(),
                fieldTypes = types.value() ?: emptyList(),
                fieldIsFocusedList = fieldIsFocusedList.value() ?: emptyList(),
                parentIdList = parentIdList.value() ?: emptyList(),
                webDomain = webDomain,
                title = title.value() ?: ""
            )
        )

    private val selectedAutofillItemState: MutableStateFlow<Option<AutofillItem>> =
        MutableStateFlow(
            savedStateHandle.get<ByteArray>(ARG_INLINE_SUGGESTION_AUTOFILL_ITEM)
                ?.deserializeParcelable<AutofillItem>()
                .toOption()
        )

    private val copyTotpToClipboardPreferenceState = preferenceRepository
        .getCopyTotpToClipboardEnabled()
        .distinctUntilChanged()

    private val themePreferenceState: Flow<ThemePreference> = preferenceRepository
        .getThemePreference()
        .distinctUntilChanged()

    val state: StateFlow<AutofillUiState> = combine(
        themePreferenceState,
        needsBiometricAuth(),
        autofillAppState,
        selectedAutofillItemState,
        copyTotpToClipboardPreferenceState
    ) { themePreference, needsAuth, autofillAppState, selectedAutofillItem, copyTotpToClipboard ->
        when {
            autofillAppState.isValid() -> NotValidAutofillUiState
            else -> StartAutofillUiState(
                themePreference = themePreference.value(),
                needsAuth = needsAuth,
                autofillAppState = autofillAppState,
                copyTotpToClipboardPreference = copyTotpToClipboard.value(),
                selectedAutofillItem = selectedAutofillItem
            )
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UninitialisedAutofillUiState
        )

    fun register(context: ComponentActivity) {
        accountOrchestrators.register(context, listOf(Orchestrator.PlansOrchestrator))
    }

    fun upgrade() = viewModelScope.launch {
        accountOrchestrators.start(Orchestrator.PlansOrchestrator)
    }

    fun onStop() = viewModelScope.launch {
        runBlocking {
            preferenceRepository.setHasAuthenticated(HasAuthenticated.NotAuthenticated)
        }
    }
}
