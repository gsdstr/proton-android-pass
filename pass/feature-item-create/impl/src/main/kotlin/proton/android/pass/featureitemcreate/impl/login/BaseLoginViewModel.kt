package proton.android.pass.featureitemcreate.impl.login

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.proton.core.accountmanager.domain.AccountManager
import proton.android.pass.clipboard.api.ClipboardManager
import proton.android.pass.common.api.LoadingResult
import proton.android.pass.common.api.None
import proton.android.pass.common.api.Option
import proton.android.pass.common.api.Some
import proton.android.pass.common.api.asLoadingResult
import proton.android.pass.common.api.combineN
import proton.android.pass.common.api.getOrNull
import proton.android.pass.common.api.toOption
import proton.android.pass.commonuimodels.api.PackageInfoUi
import proton.android.pass.composecomponents.impl.uievents.IsLoadingState
import proton.android.pass.crypto.api.context.EncryptionContextProvider
import proton.android.pass.data.api.repositories.DRAFT_CUSTOM_FIELD_KEY
import proton.android.pass.data.api.repositories.DRAFT_PASSWORD_KEY
import proton.android.pass.data.api.repositories.DraftRepository
import proton.android.pass.data.api.url.UrlSanitizer
import proton.android.pass.data.api.usecases.ObserveCurrentUser
import proton.android.pass.data.api.usecases.ObserveUpgradeInfo
import proton.android.pass.data.api.usecases.UpgradeInfo
import proton.android.pass.featureitemcreate.impl.ItemSavedState
import proton.android.pass.featureitemcreate.impl.OpenScanState
import proton.android.pass.featureitemcreate.impl.alias.AliasItem
import proton.android.pass.featureitemcreate.impl.alias.CreateAliasViewModel
import proton.android.pass.log.api.PassLogger
import proton.android.pass.notifications.api.SnackbarDispatcher
import proton.android.pass.preferences.FeatureFlag
import proton.android.pass.preferences.FeatureFlagsPreferencesRepository
import proton.android.pass.totp.api.TotpManager
import proton.pass.domain.CustomFieldContent
import proton.pass.domain.HiddenState
import proton.pass.domain.ItemContents
import proton.pass.domain.PlanType

abstract class BaseLoginViewModel(
    protected val accountManager: AccountManager,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val clipboardManager: ClipboardManager,
    private val totpManager: TotpManager,
    private val draftRepository: DraftRepository,
    private val encryptionContextProvider: EncryptionContextProvider,
    observeCurrentUser: ObserveCurrentUser,
    observeUpgradeInfo: ObserveUpgradeInfo,
    ffRepo: FeatureFlagsPreferencesRepository,
) : ViewModel() {

    private val hasUserEditedContentFlow: MutableStateFlow<Boolean> = MutableStateFlow(false)
    protected val itemContentState: MutableStateFlow<ItemContents.Login> =
        MutableStateFlow(ItemContents.Login.Empty)
    protected val aliasLocalItemState: MutableStateFlow<Option<AliasItem>> = MutableStateFlow(None)
    private val aliasDraftState: Flow<Option<AliasItem>> = draftRepository
        .get(CreateAliasViewModel.KEY_DRAFT_ALIAS)

    init {
        viewModelScope.launch {
            launch { observeGeneratedPassword() }
            launch { observeNewCustomField() }
        }
    }

    private val aliasState: Flow<Option<AliasItem>> = combine(
        aliasLocalItemState,
        aliasDraftState.onStart { emit(None) }
    ) { aliasItem, aliasDraft ->
        when (aliasDraft) {
            is Some -> {
                onAliasCreated(aliasDraft.value)
                aliasDraft
            }

            None -> aliasItem
        }
    }

    protected val isLoadingState: MutableStateFlow<IsLoadingState> =
        MutableStateFlow(IsLoadingState.NotLoading)
    protected val isItemSavedState: MutableStateFlow<ItemSavedState> =
        MutableStateFlow(ItemSavedState.Unknown)
    private val openScanState: MutableStateFlow<OpenScanState> =
        MutableStateFlow(OpenScanState.Unknown)

    private val eventsFlow: Flow<Events> = combine(isItemSavedState, openScanState, ::Events)

    data class Events(
        val itemSavedState: ItemSavedState,
        val openScanState: OpenScanState,
    )

    private val loginItemValidationErrorsState: MutableStateFlow<Set<LoginItemValidationErrors>> =
        MutableStateFlow(emptySet())
    private val focusLastWebsiteState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    protected val canUpdateUsernameState: MutableStateFlow<Boolean> = MutableStateFlow(true)

    protected val upgradeInfoFlow: Flow<UpgradeInfo> = observeUpgradeInfo().distinctUntilChanged()

    private val loginAliasItemWrapperState = combine(
        itemContentState,
        loginItemValidationErrorsState,
        canUpdateUsernameState,
        observeCurrentUser().map { it.email },
        aliasState
    ) { loginItem, loginItemValidationErrors, updateUsername, primaryEmail, aliasItem ->
        LoginAliasItemWrapper(
            loginItem,
            loginItemValidationErrors,
            updateUsername,
            primaryEmail,
            aliasItem
        )
    }

    private data class LoginAliasItemWrapper(
        val content: ItemContents.Login,
        val loginItemValidationErrors: Set<LoginItemValidationErrors>,
        val canUpdateUsername: Boolean,
        val primaryEmail: String?,
        val aliasItem: Option<AliasItem>
    )

    protected val itemHadTotpState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val totpUiStateFlow = combine(
        itemHadTotpState,
        upgradeInfoFlow.asLoadingResult()
    ) { itemHadTotp, upgradeInfoResult ->
        when (upgradeInfoResult) {
            is LoadingResult.Error -> TotpUiState.Error
            LoadingResult.Loading -> TotpUiState.Loading
            is LoadingResult.Success -> if (upgradeInfoResult.data.hasReachedTotpLimit()) {
                TotpUiState.Limited(itemHadTotp)
            } else {
                TotpUiState.Success
            }
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PROTECTED)
    val baseLoginUiState: StateFlow<BaseLoginUiState> = combineN(
        loginAliasItemWrapperState,
        isLoadingState,
        eventsFlow,
        focusLastWebsiteState,
        hasUserEditedContentFlow,
        totpUiStateFlow,
        upgradeInfoFlow.asLoadingResult(),
        ffRepo.get<Boolean>(FeatureFlag.CUSTOM_FIELDS_ENABLED)
    ) { loginItemWrapper, isLoading, events,
        focusLastWebsite, hasUserEditedContent,
        totpUiState, upgradeInfoResult, customFieldsEnabled ->

        val customFieldsState = if (!customFieldsEnabled) {
            CustomFieldsState.Disabled
        } else {
            val plan = upgradeInfoResult.getOrNull()?.plan
            when (plan?.planType) {
                is PlanType.Paid, is PlanType.Trial -> {
                    CustomFieldsState.Enabled(loginItemWrapper.content.customFields)
                }

                else -> {
                    if (loginItemWrapper.content.customFields.isNotEmpty()) {
                        CustomFieldsState.Limited
                    } else {
                        CustomFieldsState.Disabled
                    }
                }
            }
        }

        BaseLoginUiState(
            contents = loginItemWrapper.content,
            validationErrors = loginItemWrapper.loginItemValidationErrors,
            isLoadingState = isLoading,
            isItemSaved = events.itemSavedState,
            openScanState = events.openScanState,
            focusLastWebsite = focusLastWebsite,
            canUpdateUsername = loginItemWrapper.canUpdateUsername,
            primaryEmail = loginItemWrapper.primaryEmail,
            aliasItem = loginItemWrapper.aliasItem.value(),
            hasUserEditedContent = hasUserEditedContent,
            hasReachedAliasLimit = upgradeInfoResult.getOrNull()?.hasReachedAliasLimit() ?: false,
            totpUiState = totpUiState,
            customFieldsState = customFieldsState
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BaseLoginUiState.Initial
        )

    fun onTitleChange(value: String) {
        onUserEditedContent()
        itemContentState.update { it.copy(title = value) }
        loginItemValidationErrorsState.update {
            it.toMutableSet().apply { remove(LoginItemValidationErrors.BlankTitle) }
        }

        val aliasItem = aliasLocalItemState.value
        if (aliasItem is Some) {
            aliasLocalItemState.update { aliasItem.value.copy(title = value).toOption() }
        }
    }

    fun onUsernameChange(value: String) {
        onUserEditedContent()
        itemContentState.update { it.copy(username = value) }
    }

    fun onPasswordChange(value: String) {
        onUserEditedContent()
        encryptionContextProvider.withEncryptionContext {
            itemContentState.update {
                it.copy(password = HiddenState.Revealed(encrypt(value), value))
            }
        }
    }

    fun onTotpChange(value: String) {
        onUserEditedContent()
        val newValue = value.replace(" ", "").replace("\n", "")
        encryptionContextProvider.withEncryptionContext {
            itemContentState.update {
                it.copy(primaryTotp = HiddenState.Revealed(encrypt(newValue), newValue))
            }
        }
        loginItemValidationErrorsState.update {
            it.toMutableSet().apply { remove(LoginItemValidationErrors.InvalidTotp) }
        }
    }

    fun onWebsiteChange(value: String, index: Int) {
        onUserEditedContent()
        val newValue = value.replace(" ", "").replace("\n", "")
        itemContentState.update {
            it.copy(
                urls = it.urls.toMutableList()
                    .apply { this[index] = newValue }
            )
        }
        loginItemValidationErrorsState.update {
            it.toMutableSet().apply { remove(LoginItemValidationErrors.InvalidUrl(index)) }
        }
        focusLastWebsiteState.update { false }
    }

    fun onAddWebsite() {
        onUserEditedContent()
        itemContentState.update {
            val websites = sanitizeWebsites(it.urls).toMutableList()
            websites.add("")

            it.copy(urls = websites)
        }
        focusLastWebsiteState.update { true }
    }

    fun onRemoveWebsite(index: Int) {
        onUserEditedContent()
        itemContentState.update {
            it.copy(urls = it.urls.toMutableList().apply { removeAt(index) })
        }
        loginItemValidationErrorsState.update {
            it.toMutableSet().apply { remove(LoginItemValidationErrors.InvalidUrl(index)) }
        }
        focusLastWebsiteState.update { false }
    }

    fun onNoteChange(value: String) {
        onUserEditedContent()
        itemContentState.update { it.copy(note = value) }
    }

    fun onEmitSnackbarMessage(snackbarMessage: LoginSnackbarMessages) =
        viewModelScope.launch {
            snackbarDispatcher(snackbarMessage)
        }

    fun onAliasCreated(aliasItem: AliasItem) {
        onUserEditedContent()
        aliasLocalItemState.update { aliasItem.toOption() }
        val alias = aliasItem.aliasToBeCreated
        if (alias != null) {
            itemContentState.update { it.copy(username = alias) }
            canUpdateUsernameState.update { false }
        }
    }

    fun onClose() {
        draftRepository.delete<AliasItem>(CreateAliasViewModel.KEY_DRAFT_ALIAS)
    }

    protected suspend fun validateItem(): Boolean {
        itemContentState.update { state ->
            val websites = sanitizeWebsites(state.urls)
            val decryptedTotp = encryptionContextProvider.withEncryptionContext {
                decrypt(state.primaryTotp.encrypted)
            }
            val sanitisedPrimaryTotp = if (decryptedTotp.isNotBlank()) {
                sanitizeOTP(decryptedTotp)
                    .fold(
                        onSuccess = { it },
                        onFailure = {
                            loginItemValidationErrorsState.update { errors ->
                                errors.toMutableSet()
                                    .apply { add(LoginItemValidationErrors.InvalidTotp) }
                            }
                            snackbarDispatcher(LoginSnackbarMessages.InvalidTotpError)
                            return false
                        }
                    )
            } else {
                ""
            }
            val hiddenState = encryptionContextProvider.withEncryptionContext {
                HiddenState.Revealed(encrypt(sanitisedPrimaryTotp), sanitisedPrimaryTotp)
            }
            state.copy(urls = websites, primaryTotp = hiddenState)
        }
        val loginItem = itemContentState.value
        val loginItemValidationErrors = loginItem.validate()
        if (loginItemValidationErrors.isNotEmpty()) {
            loginItemValidationErrorsState.update { loginItemValidationErrors }
            return false
        }
        return true
    }

    private fun sanitizeWebsites(websites: List<String>): List<String> =
        websites.map { url ->
            if (url.isBlank()) {
                ""
            } else {
                UrlSanitizer.sanitize(url).fold(
                    onSuccess = { it },
                    onFailure = { url }
                )
            }
        }

    private fun sanitizeOTP(otp: String): Result<String> {
        val isUri = otp.contains("://")
        return totpManager.parse(otp)
            .fold(
                onSuccess = { spec ->
                    val uri = totpManager.generateUri(spec)
                    Result.success(uri)
                },
                onFailure = {
                    // If is an URI, we require it to be valid. Otherwise we interpret it as secret
                    if (isUri) {
                        Result.failure(it)
                    } else {
                        totpManager.generateUriWithDefaults(otp)
                    }
                }
            )
    }

    fun onDeleteLinkedApp(packageInfo: PackageInfoUi) {
        onUserEditedContent()
        itemContentState.update {
            it.copy(packageInfoSet = it.packageInfoSet.minus(packageInfo.toPackageInfo()))
        }
    }

    fun onPasteTotp() = viewModelScope.launch(Dispatchers.IO) {
        onUserEditedContent()
        clipboardManager.getClipboardContent()
            .onSuccess { clipboardContent ->
                val sanitisedContent = clipboardContent
                    .replace(" ", "")
                    .replace("\n", "")
                val encryptedContent = encryptionContextProvider.withEncryptionContext {
                    encrypt(sanitisedContent)
                }
                withContext(Dispatchers.Main) {
                    itemContentState.update {
                        it.copy(
                            primaryTotp = HiddenState.Revealed(encryptedContent, sanitisedContent)
                        )
                    }
                }
            }
            .onFailure { PassLogger.d(TAG, it, "Failed on getting clipboard content") }
    }

    fun onRemoveAlias() {
        onUserEditedContent()
        aliasLocalItemState.update { None }
        draftRepository.delete<AliasItem>(CreateAliasViewModel.KEY_DRAFT_ALIAS)

        itemContentState.update { it.copy(username = "") }
        canUpdateUsernameState.update { true }
    }

    protected fun onUserEditedContent() {
        if (hasUserEditedContentFlow.value) return
        hasUserEditedContentFlow.update { true }
    }

    private suspend fun observeGeneratedPassword() {
        draftRepository
            .get<String>(DRAFT_PASSWORD_KEY)
            .collect {
                if (it is Some) {
                    draftRepository.delete<String>(DRAFT_PASSWORD_KEY).value()
                        ?.let { encryptedPassword ->
                            itemContentState.update { content ->
                                encryptionContextProvider.withEncryptionContext {
                                    content.copy(
                                        password = HiddenState.Revealed(
                                            encryptedPassword,
                                            decrypt(encryptedPassword)
                                        )
                                    )
                                }
                            }
                        }
                }
            }
    }

    private suspend fun observeNewCustomField() {
        draftRepository
            .get<CustomFieldContent>(DRAFT_CUSTOM_FIELD_KEY)
            .collect {
                if (it is Some) {
                    draftRepository.delete<CustomFieldContent>(DRAFT_CUSTOM_FIELD_KEY).value()
                        ?.let { customField ->
                            itemContentState.update { loginItem ->
                                val customFields = loginItem.customFields.toMutableList()
                                customFields.add(customField)
                                loginItem.copy(customFields = customFields.toPersistentList())
                            }
                        }
                }
            }
    }

    fun onFocusChange(field: MainLoginField, focused: Boolean) {
        itemContentState.update {
            when (field) {
                MainLoginField.Password -> {
                    val password = encryptionContextProvider.withEncryptionContext {
                        if (focused) {
                            if (it.password.encrypted.isNotBlank()) {
                                HiddenState.Revealed(
                                    it.password.encrypted,
                                    decrypt(it.password.encrypted)
                                )
                            } else {
                                HiddenState.Revealed(encrypt(""), "")
                            }
                        } else {
                            HiddenState.Concealed(it.password.encrypted)
                        }
                    }
                    it.copy(password = password)
                }

                MainLoginField.Totp -> {
                    val primaryTotp = encryptionContextProvider.withEncryptionContext {
                        if (it.primaryTotp.encrypted.isNotBlank()) {
                            HiddenState.Revealed(
                                it.primaryTotp.encrypted,
                                decrypt(it.primaryTotp.encrypted)
                            )
                        } else {
                            HiddenState.Revealed(encrypt(""), "")
                        }
                    }
                    it.copy(primaryTotp = primaryTotp)
                }

                else -> it
            }
        }
    }

    companion object {
        private const val TAG = "BaseLoginViewModel"
    }
}

