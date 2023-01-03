package me.proton.pass.presentation.create.alias

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.proton.android.pass.data.api.crypto.EncryptionContextProvider
import me.proton.android.pass.data.api.repositories.AliasRepository
import me.proton.android.pass.data.api.repositories.ItemRepository
import me.proton.android.pass.data.api.usecases.UpdateAlias
import me.proton.android.pass.data.api.usecases.UpdateAliasContent
import me.proton.android.pass.data.api.usecases.UpdateAliasItemContent
import me.proton.android.pass.log.api.PassLogger
import me.proton.android.pass.notifications.api.SnackbarMessageRepository
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.domain.entity.UserId
import me.proton.pass.common.api.None
import me.proton.pass.common.api.Option
import me.proton.pass.common.api.Result
import me.proton.pass.common.api.Some
import me.proton.pass.common.api.asResultWithoutLoading
import me.proton.pass.common.api.onError
import me.proton.pass.common.api.onSuccess
import me.proton.pass.domain.AliasDetails
import me.proton.pass.domain.AliasOptions
import me.proton.pass.domain.AliasSuffix
import me.proton.pass.domain.Item
import me.proton.pass.domain.ItemId
import me.proton.pass.domain.ItemType
import me.proton.pass.domain.ShareId
import me.proton.pass.presentation.create.alias.AliasSnackbarMessage.InitError
import me.proton.pass.presentation.uievents.AliasSavedState
import me.proton.pass.presentation.uievents.IsButtonEnabled
import me.proton.pass.presentation.uievents.IsLoadingState
import me.proton.pass.presentation.uievents.ItemDeletedState
import me.proton.pass.presentation.utils.AliasUtils
import javax.inject.Inject

@HiltViewModel
class UpdateAliasViewModel @Inject constructor(
    private val accountManager: AccountManager,
    private val itemRepository: ItemRepository,
    private val aliasRepository: AliasRepository,
    private val snackbarMessageRepository: SnackbarMessageRepository,
    private val updateAliasUseCase: UpdateAlias,
    private val encryptionContextProvider: EncryptionContextProvider,
    savedStateHandle: SavedStateHandle
) : BaseAliasViewModel(snackbarMessageRepository, savedStateHandle) {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        PassLogger.e(TAG, throwable)
    }

    private val itemId: Option<ItemId> =
        Option.fromNullable(savedStateHandle.get<String>("itemId")?.let { ItemId(it) })

    private val _aliasDeletedState: MutableStateFlow<ItemDeletedState> = MutableStateFlow(ItemDeletedState.Unknown)
    val aliasDeletedState: StateFlow<ItemDeletedState> = _aliasDeletedState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = ItemDeletedState.Unknown
        )

    private var _item: Item? = null

    private var itemDataChanged = false
    private var mailboxesChanged = false

    init {
        viewModelScope.launch(coroutineExceptionHandler) {
            isApplyButtonEnabledState.update { IsButtonEnabled.Disabled }
            setupInitialState()
        }
    }

    override fun onMailboxesChanged(mailboxes: List<AliasMailboxUiModel>) {
        super.onMailboxesChanged(mailboxes)
        isApplyButtonEnabledState.update { IsButtonEnabled.Enabled }
        mailboxesChanged = true
    }

    override fun onNoteChange(value: String) {
        super.onNoteChange(value)
        isApplyButtonEnabledState.update { IsButtonEnabled.Enabled }
        itemDataChanged = true
    }

    override fun onTitleChange(value: String) {
        aliasItemState.update { it.copy(title = value) }
        aliasItemValidationErrorsState.update {
            it.toMutableSet()
                .apply { remove(AliasItemValidationErrors.BlankTitle) }
        }
        isApplyButtonEnabledState.update { IsButtonEnabled.Enabled }
        itemDataChanged = true
    }

    override fun onAliasChange(value: String) {
        // no-op as alias cannot be changed from Update view
        // should never be called
        PassLogger.e(
            TAG,
            IllegalStateException("UpdateAliasViewModel.onAliasChange should never be called")
        )
    }

    fun onDeleteAlias() = viewModelScope.launch {
        isLoadingState.update { IsLoadingState.Loading }
        val userId = accountManager.getPrimaryUserId().first { userId -> userId != null }
        if (userId != null && shareId is Some && itemId is Some) {
            itemRepository.trashItem(userId, shareId.value, itemId.value)
            snackbarMessageRepository.emitSnackbarMessage(AliasSnackbarMessage.AliasMovedToTrash)
            _aliasDeletedState.update { ItemDeletedState.Deleted }
        } else {
            showError("Empty user/share/item Id", InitError)
        }
        isLoadingState.update { IsLoadingState.NotLoading }
    }

    private suspend fun setupInitialState() {
        if (_item != null) return
        isLoadingState.update { IsLoadingState.Loading }

        val userId = accountManager.getPrimaryUserId().first { userId -> userId != null }
        if (userId != null && shareId is Some && itemId is Some) {
            fetchInitialData(userId, shareId.value, itemId.value)
        } else {
            showError("Empty user/share/item Id", InitError)
        }
        isLoadingState.update { IsLoadingState.NotLoading }
    }

    private suspend fun fetchInitialData(userId: UserId, shareId: ShareId, itemId: ItemId) {
        val itemResult = itemRepository.getById(userId, shareId, itemId)
        itemResult
            .onSuccess { item ->
                _item = item
                aliasRepository.getAliasDetails(userId, shareId, itemId)
                    .asResultWithoutLoading()
                    .collect { onAliasDetails(it, item) }
            }
            .onError { showError("Error getting item by id", InitError, it) }
    }

    private suspend fun onAliasDetails(result: Result<AliasDetails>, item: Item) {
        result
            .onSuccess { details ->
                val alias = item.itemType as ItemType.Alias
                val email = alias.aliasEmail
                val (prefix, suffix) = AliasUtils.extractPrefixSuffix(email)

                val mailboxes = details.availableMailboxes.map { mailbox ->
                    AliasMailboxUiModel(
                        model = mailbox,
                        selected = details.mailboxes.any { it.id == mailbox.id }
                    )
                }

                aliasItemState.update {
                    encryptionContextProvider.withEncryptionContext {
                        it.copy(
                            title = decrypt(item.title),
                            note = decrypt(item.note),
                            alias = prefix,
                            aliasOptions = AliasOptions(emptyList(), details.mailboxes),
                            selectedSuffix = AliasSuffix(suffix, suffix, false, ""),
                            mailboxes = mailboxes,
                            aliasToBeCreated = email,
                            mailboxTitle = getMailboxTitle(mailboxes)
                        )
                    }
                }
            }
            .onError {
                showError("Error getting alias mailboxes", InitError, it)
            }
    }

    private suspend fun showError(
        message: String,
        snackbarMessage: AliasSnackbarMessage,
        it: Throwable? = null
    ) {
        PassLogger.i(TAG, it ?: Exception(message), message)
        snackbarMessageRepository.emitSnackbarMessage(snackbarMessage)
    }

    fun updateAlias() = viewModelScope.launch(coroutineExceptionHandler) {
        val canUpdate = canUpdateAlias()
        if (!canUpdate) {
            PassLogger.i(TAG, "Cannot update alias")
            return@launch
        }

        val body = createUpdateAliasBody()
        isLoadingState.update { IsLoadingState.Loading }

        val userId = accountManager.getPrimaryUserId().first { userId -> userId != null }
        if (userId != null) {
            updateAliasUseCase(
                userId = userId,
                item = _item!!,
                content = body
            )
                .onSuccess { item ->
                    PassLogger.i(TAG, "Alias successfully updated")
                    isAliasSavedState.update {
                        AliasSavedState.Success(
                            itemId = item.id,
                            alias = "" // we don't care about it as we are updating it
                        )
                    }
                    isLoadingState.update { IsLoadingState.NotLoading }
                }
                .onError {
                    val defaultMessage = "Update alias error"
                    PassLogger.i(TAG, it ?: Exception(defaultMessage), defaultMessage)
                    snackbarMessageRepository.emitSnackbarMessage(AliasSnackbarMessage.AliasUpdated)
                    isLoadingState.update { IsLoadingState.NotLoading }
                }
        } else {
            PassLogger.i(TAG, "Empty User Id")
            snackbarMessageRepository.emitSnackbarMessage(AliasSnackbarMessage.ItemCreationError)
            isLoadingState.update { IsLoadingState.NotLoading }
        }
    }

    private fun canUpdateAlias(): Boolean {
        if (!itemDataChanged && !mailboxesChanged) {
            PassLogger.i(TAG, "Nor item nor mailboxes have changed")
            return false
        }

        val aliasItem = aliasItemState.value
        val aliasItemValidationErrors = aliasItem.validate()
        if (aliasItemValidationErrors.isNotEmpty()) {
            PassLogger.i(TAG, "alias item validation has failed: $aliasItemValidationErrors")
            aliasItemValidationErrorsState.update { aliasItemValidationErrors }
            return false
        }
        return true
    }

    private fun createUpdateAliasBody(): UpdateAliasContent {
        val mailboxes = if (mailboxesChanged) {
            val selectedMailboxes = aliasItemState.value
                .mailboxes
                .filter { it.selected }
                .map { it.model }
            Some(selectedMailboxes)
        } else None

        val itemData = if (itemDataChanged) {
            val aliasItem = aliasItemState.value
            Some(
                UpdateAliasItemContent(
                    title = aliasItem.title,
                    note = aliasItem.note
                )
            )
        } else None

        val body = UpdateAliasContent(
            mailboxes = mailboxes,
            itemData = itemData
        )
        return body
    }

    companion object {
        const val TAG = "UpdateAliasViewModel"
    }
}
