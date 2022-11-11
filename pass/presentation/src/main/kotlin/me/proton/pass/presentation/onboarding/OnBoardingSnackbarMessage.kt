package me.proton.pass.presentation.onboarding

import androidx.annotation.StringRes
import me.proton.android.pass.notifications.api.SnackbarMessage
import me.proton.android.pass.notifications.api.SnackbarType
import me.proton.pass.presentation.R

enum class OnBoardingSnackbarMessage(
    @StringRes override val id: Int,
    override val type: SnackbarType
) : SnackbarMessage {
    BiometryFailedToStartError(R.string.error_biometry_failed_to_start, SnackbarType.ERROR),
    BiometryFailedToAuthenticateError(R.string.error_biometry_failed_to_authenticate, SnackbarType.ERROR),
    FingerprintLockEnabled(R.string.settings_fingerprint_lock_enabled, SnackbarType.SUCCESS),
    ErrorPerformingOperation(R.string.error_performing_operation, SnackbarType.ERROR)
}
