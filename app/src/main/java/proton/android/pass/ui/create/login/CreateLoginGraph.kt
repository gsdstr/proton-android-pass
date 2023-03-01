package proton.android.pass.ui.create.login

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.ExperimentalLifecycleComposeApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import proton.android.pass.featurecreateitem.impl.alias.AliasItem
import proton.android.pass.featurecreateitem.impl.alias.RESULT_CREATED_DRAFT_ALIAS
import proton.android.pass.featurecreateitem.impl.login.CreateLoginScreen
import proton.android.pass.featurecreateitem.impl.login.InitialCreateLoginUiState
import proton.android.pass.featurecreateitem.impl.totp.TOTP_NAV_PARAMETER_KEY
import proton.android.pass.navigation.api.AppNavigator
import proton.android.pass.navigation.api.composable
import proton.android.pass.ui.navigation.CameraTotp
import proton.android.pass.ui.navigation.CreateLogin

@OptIn(
    ExperimentalAnimationApi::class,
    ExperimentalLifecycleComposeApi::class
)
fun NavGraphBuilder.createLoginGraph(nav: AppNavigator) {
    composable(CreateLogin) {
        val createdDraftAlias by nav.navState<AliasItem>(RESULT_CREATED_DRAFT_ALIAS, null)
            .collectAsStateWithLifecycle()
        val primaryTotp by nav.navState<String>(TOTP_NAV_PARAMETER_KEY, null)
            .collectAsStateWithLifecycle()
        val initialContents = InitialCreateLoginUiState(
            aliasItem = createdDraftAlias,
            primaryTotp = primaryTotp
        )
        CreateLoginScreen(
            initialContents = initialContents,
            onClose = { nav.onBackClick() },
            onSuccess = { nav.onBackClick() },
            onScanTotp = { nav.navigate(CameraTotp) }
        )
    }
}
