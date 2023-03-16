package proton.android.pass.featureprofile.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import proton.android.pass.autofill.api.AutofillManager
import proton.android.pass.biometry.BiometryAuthError
import proton.android.pass.biometry.BiometryManager
import proton.android.pass.biometry.BiometryResult
import proton.android.pass.biometry.BiometryStatus
import proton.android.pass.biometry.ContextHolder
import proton.android.pass.common.api.getOrNull
import proton.android.pass.commonui.api.toUiModel
import proton.android.pass.composecomponents.impl.uievents.IsButtonEnabled
import proton.android.pass.crypto.api.context.EncryptionContextProvider
import proton.android.pass.data.api.usecases.ItemTypeFilter
import proton.android.pass.data.api.usecases.ObserveActiveItems
import proton.android.pass.data.api.usecases.ObserveItemCount
import proton.android.pass.featureprofile.impl.ProfileSnackbarMessage.BiometryFailedToAuthenticateError
import proton.android.pass.featureprofile.impl.ProfileSnackbarMessage.BiometryFailedToStartError
import proton.android.pass.featureprofile.impl.ProfileSnackbarMessage.ErrorPerformingOperation
import proton.android.pass.featureprofile.impl.ProfileSnackbarMessage.FingerprintLockDisabled
import proton.android.pass.featureprofile.impl.ProfileSnackbarMessage.FingerprintLockEnabled
import proton.android.pass.log.api.PassLogger
import proton.android.pass.notifications.api.SnackbarMessageRepository
import proton.android.pass.preferences.BiometricLockState
import proton.android.pass.preferences.HasAuthenticated
import proton.android.pass.preferences.UserPreferencesRepository
import proton.pass.domain.ItemType
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val preferencesRepository: UserPreferencesRepository,
    private val biometryManager: BiometryManager,
    private val autofillManager: AutofillManager,
    private val snackbarMessageRepository: SnackbarMessageRepository,
    encryptionContextProvider: EncryptionContextProvider,
    observeItemCount: ObserveItemCount,
    observeActiveItems: ObserveActiveItems
) : ViewModel() {

    private val biometricLockState = preferencesRepository
        .getBiometricLockState()
        .distinctUntilChanged()

    private val autofillStatusFlow = autofillManager
        .getAutofillStatus()
        .distinctUntilChanged()

    private val mfaCountFlow = observeActiveItems(ItemTypeFilter.Logins)
        .map {
            encryptionContextProvider.withEncryptionContext {
                it.map { decrypt((it.toUiModel(this).itemType as ItemType.Login).primaryTotp) }
            }.count { it.isNotBlank() }
        }

    val state: StateFlow<ProfileUiState> = combine(
        biometricLockState,
        flowOf(biometryManager.getBiometryStatus()),
        autofillStatusFlow,
        observeItemCount().distinctUntilChanged(),
        mfaCountFlow
    ) { biometricLock, biometryStatus, autofillStatus, itemCountResult, mfaCount ->
        val itemSummaryUiState = itemCountResult.getOrNull()
            ?.let {
                ItemSummaryUiState(
                    loginCount = it.login.toInt(),
                    notesCount = it.note.toInt(),
                    aliasCount = it.alias.toInt(),
                    mfaCount = mfaCount,
                )
            }
            ?: ItemSummaryUiState()
        val fingerprintSection = when (biometryStatus) {
            BiometryStatus.NotEnrolled -> FingerprintSectionState.NoFingerprintRegistered
            BiometryStatus.NotAvailable -> FingerprintSectionState.NotAvailable
            BiometryStatus.CanAuthenticate -> {
                val available = when (biometricLock) {
                    BiometricLockState.Enabled -> IsButtonEnabled.Enabled
                    BiometricLockState.Disabled -> IsButtonEnabled.Disabled
                }
                FingerprintSectionState.Available(available)
            }
        }
        ProfileUiState(
            fingerprintSection = fingerprintSection,
            autofillStatus = autofillStatus,
            itemSummaryUiState = itemSummaryUiState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProfileUiState.Initial
    )

    fun onFingerprintToggle(contextHolder: ContextHolder, value: Boolean) =
        viewModelScope.launch {
            biometryManager.launch(contextHolder)
                .map { result ->
                    when (result) {
                        BiometryResult.Success -> {
                            preferencesRepository.setHasAuthenticated(HasAuthenticated.Authenticated)
                                .onFailure {
                                    val message = "Could not save HasAuthenticated preference"
                                    PassLogger.e(TAG, it, message)
                                }
                            val (lockState, message) = when (!value) {
                                true -> BiometricLockState.Enabled to FingerprintLockEnabled
                                false -> BiometricLockState.Disabled to FingerprintLockDisabled
                            }

                            PassLogger.d(TAG, "Changing BiometricLock to $lockState")
                            preferencesRepository.setBiometricLockState(lockState)
                                .onSuccess { snackbarMessageRepository.emitSnackbarMessage(message) }
                                .onFailure {
                                    PassLogger.e(TAG, it, "Error setting BiometricLockState")
                                    snackbarMessageRepository
                                        .emitSnackbarMessage(ErrorPerformingOperation)
                                }
                        }
                        is BiometryResult.Error -> {
                            when (result.cause) {
                                // If the user has cancelled it, do nothing
                                BiometryAuthError.Canceled -> {}
                                BiometryAuthError.UserCanceled -> {}
                                else ->
                                    snackbarMessageRepository
                                        .emitSnackbarMessage(BiometryFailedToAuthenticateError)
                            }
                        }

                        // User can retry
                        BiometryResult.Failed -> {}
                        is BiometryResult.FailedToStart ->
                            snackbarMessageRepository
                                .emitSnackbarMessage(BiometryFailedToStartError)
                    }
                    PassLogger.i(TAG, "Biometry result: $result")
                }
                .collect { }
        }

    fun onToggleAutofill(value: Boolean) {
        if (!value) {
            autofillManager.openAutofillSelector()
        } else {
            autofillManager.disableAutofill()
        }
    }

    companion object {
        private const val TAG = "ProfileViewModel"
    }
}
