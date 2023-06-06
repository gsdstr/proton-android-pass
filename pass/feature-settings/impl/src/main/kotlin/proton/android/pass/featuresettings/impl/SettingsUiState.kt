package proton.android.pass.featuresettings.impl

import androidx.compose.runtime.Stable
import proton.android.pass.common.api.None
import proton.android.pass.common.api.Option
import proton.android.pass.composecomponents.impl.uievents.IsLoadingState
import proton.android.pass.preferences.AllowScreenshotsPreference
import proton.android.pass.preferences.CopyTotpToClipboard
import proton.android.pass.preferences.ThemePreference
import proton.android.pass.preferences.UseFaviconsPreference
import proton.pass.domain.Vault

sealed interface SettingsEvent {
    object Unknown : SettingsEvent
    object RestartApp : SettingsEvent
}

@Stable
data class SettingsUiState(
    val themePreference: ThemePreference,
    val copyTotpToClipboard: CopyTotpToClipboard,
    val isLoadingState: IsLoadingState,
    val primaryVault: Option<Vault>,
    val useFavicons: UseFaviconsPreference,
    val allowScreenshots: AllowScreenshotsPreference,
    val shareTelemetry: Boolean,
    val shareCrashes: Boolean,
    val event: SettingsEvent
) {
    companion object {
        val Initial = SettingsUiState(
            themePreference = ThemePreference.System,
            copyTotpToClipboard = CopyTotpToClipboard.NotEnabled,
            isLoadingState = IsLoadingState.NotLoading,
            primaryVault = None,
            useFavicons = UseFaviconsPreference.Enabled,
            allowScreenshots = AllowScreenshotsPreference.Disabled,
            shareTelemetry = true,
            shareCrashes = true,
            event = SettingsEvent.Unknown
        )
    }
}
