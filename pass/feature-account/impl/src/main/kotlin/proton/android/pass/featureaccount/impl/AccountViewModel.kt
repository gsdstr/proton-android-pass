package proton.android.pass.featureaccount.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import proton.android.pass.common.api.LoadingResult
import proton.android.pass.common.api.asLoadingResult
import proton.android.pass.composecomponents.impl.uievents.IsLoadingState
import proton.android.pass.data.api.usecases.GetUpgradeInfo
import proton.android.pass.data.api.usecases.ObserveCurrentUser
import proton.android.pass.log.api.PassLogger
import proton.pass.domain.PlanType
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    observeCurrentUser: ObserveCurrentUser,
    getUpgradeInfo: GetUpgradeInfo
) : ViewModel() {

    private val currentUser = observeCurrentUser()
        .distinctUntilChanged()

    val state: StateFlow<AccountUiState> = combine(
        currentUser.asLoadingResult(),
        getUpgradeInfo().asLoadingResult()
    ) { userResult, upgradeInfoResult ->
        val plan = when (upgradeInfoResult) {
            is LoadingResult.Error -> {
                PassLogger.e(TAG, upgradeInfoResult.exception, "Error retrieving user plan")
                PlanSection.Hide
            }

            LoadingResult.Loading -> PlanSection.Loading
            is LoadingResult.Success -> when (val plan = upgradeInfoResult.data.plan.planType) {
                PlanType.Free -> PlanSection.Data(planName = plan.humanReadableName())
                is PlanType.Paid -> PlanSection.Data(planName = plan.humanReadableName())
            }
        }
        val showUpgradeButton = when (upgradeInfoResult) {
            is LoadingResult.Error -> false
            LoadingResult.Loading -> false
            is LoadingResult.Success -> upgradeInfoResult.data.isUpgradeAvailable
        }
        when (userResult) {
            LoadingResult.Loading -> AccountUiState.Initial
            is LoadingResult.Error -> AccountUiState(
                email = null,
                plan = PlanSection.Hide,
                isLoadingState = IsLoadingState.NotLoading,
                showUpgradeButton = showUpgradeButton
            )

            is LoadingResult.Success -> AccountUiState(
                email = userResult.data.email,
                plan = plan,
                isLoadingState = IsLoadingState.NotLoading,
                showUpgradeButton = showUpgradeButton
            )
        }
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = AccountUiState.Initial
        )

    companion object {
        private const val TAG = "AccountViewModel"
    }
}
