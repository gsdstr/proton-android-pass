package proton.android.pass.featurehome.impl

import androidx.compose.material.ExperimentalMaterialApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import proton.android.pass.clipboard.api.ClipboardManager
import proton.android.pass.common.api.LoadingResult
import proton.android.pass.common.api.None
import proton.android.pass.common.api.Option
import proton.android.pass.common.api.Some
import proton.android.pass.common.api.asResultWithoutLoading
import proton.android.pass.common.api.map
import proton.android.pass.common.api.onError
import proton.android.pass.common.api.onSuccess
import proton.android.pass.common.api.toOption
import proton.android.pass.commonui.api.GroupedItemList
import proton.android.pass.commonui.api.GroupingKeys.NoGrouping
import proton.android.pass.commonui.api.ItemSorter.sortByCreationAsc
import proton.android.pass.commonui.api.ItemSorter.sortByCreationDesc
import proton.android.pass.commonui.api.ItemSorter.sortByMostRecent
import proton.android.pass.commonui.api.ItemSorter.sortByTitleAsc
import proton.android.pass.commonui.api.ItemSorter.sortByTitleDesc
import proton.android.pass.commonui.api.ItemUiFilter.filterByQuery
import proton.android.pass.commonui.api.toUiModel
import proton.android.pass.commonuimodels.api.ItemUiModel
import proton.android.pass.commonuimodels.api.ShareUiModel
import proton.android.pass.composecomponents.impl.uievents.IsLoadingState
import proton.android.pass.composecomponents.impl.uievents.IsProcessingSearchState
import proton.android.pass.composecomponents.impl.uievents.IsRefreshingState
import proton.android.pass.crypto.api.context.EncryptionContextProvider
import proton.android.pass.crypto.api.extensions.toVault
import proton.android.pass.data.api.usecases.ApplyPendingEvents
import proton.android.pass.data.api.usecases.ClearTrash
import proton.android.pass.data.api.usecases.DeleteItem
import proton.android.pass.data.api.usecases.GetShareById
import proton.android.pass.data.api.usecases.ItemTypeFilter
import proton.android.pass.data.api.usecases.ObserveItems
import proton.android.pass.data.api.usecases.RestoreItem
import proton.android.pass.data.api.usecases.RestoreItems
import proton.android.pass.data.api.usecases.TrashItem
import proton.android.pass.data.api.usecases.searchentry.AddSearchEntry
import proton.android.pass.data.api.usecases.searchentry.DeleteAllSearchEntry
import proton.android.pass.data.api.usecases.searchentry.ObserveSearchEntry
import proton.android.pass.data.api.usecases.searchentry.ObserveSearchEntry.SearchEntrySelection
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.AliasMovedToTrash
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.ClearTrashError
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.DeleteItemError
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.LoginMovedToTrash
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.NoteMovedToTrash
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.ObserveItemsError
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.RefreshError
import proton.android.pass.featurehome.impl.HomeSnackbarMessage.RestoreItemsError
import proton.android.pass.log.api.PassLogger
import proton.android.pass.notifications.api.SnackbarDispatcher
import proton.android.pass.telemetry.api.EventItemType
import proton.android.pass.telemetry.api.TelemetryManager
import proton.pass.domain.ItemId
import proton.pass.domain.ItemState
import proton.pass.domain.ItemType
import proton.pass.domain.Share
import proton.pass.domain.ShareId
import proton.pass.domain.ShareSelection
import javax.inject.Inject

@Suppress("LongParameterList")
@ExperimentalMaterialApi
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val trashItem: TrashItem,
    private val snackbarDispatcher: SnackbarDispatcher,
    private val clipboardManager: ClipboardManager,
    private val applyPendingEvents: ApplyPendingEvents,
    private val encryptionContextProvider: EncryptionContextProvider,
    private val getShareById: GetShareById,
    private val restoreItem: RestoreItem,
    private val restoreItems: RestoreItems,
    private val deleteItem: DeleteItem,
    private val clearTrash: ClearTrash,
    private val addSearchEntry: AddSearchEntry,
    private val deleteAllSearchEntry: DeleteAllSearchEntry,
    private val observeSearchEntry: ObserveSearchEntry,
    private val telemetryManager: TelemetryManager,
    clock: Clock,
    observeItems: ObserveItems
) : ViewModel() {

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        PassLogger.e(TAG, throwable)
    }

    // Variable to keep track of whether the user has entered the search in this session, so we
    // don't send an EnterSearch event every time they click on the search bar
    private var hasEnteredSearch = false

    private data class FiltersWrapper(
        val vaultSelection: HomeVaultSelection,
        val sortingSelection: SortingType,
        val itemTypeSelection: HomeItemTypeSelection
    )

    private val itemTypeSelectionFlow: MutableStateFlow<HomeItemTypeSelection> =
        MutableStateFlow(HomeItemTypeSelection.AllItems)
    private val vaultSelectionFlow: MutableStateFlow<HomeVaultSelection> =
        MutableStateFlow(HomeVaultSelection.AllVaults)

    private val searchQueryState: MutableStateFlow<String> = MutableStateFlow("")
    private val isInSearchModeState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val isInSuggestionsModeState: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val isProcessingSearchState: MutableStateFlow<IsProcessingSearchState> =
        MutableStateFlow(IsProcessingSearchState.NotLoading)

    private val selectedShareFlow: Flow<Option<ShareUiModel>> = vaultSelectionFlow
        .mapLatest {
            when (it) {
                HomeVaultSelection.AllVaults -> None
                HomeVaultSelection.Trash -> None
                is HomeVaultSelection.Vault -> shareIdToShare(it.shareId)
            }
        }

    private val searchEntryState = vaultSelectionFlow
        .flatMapLatest {
            when (it) {
                HomeVaultSelection.AllVaults ->
                    observeSearchEntry(SearchEntrySelection.AllVaults)
                HomeVaultSelection.Trash -> emptyFlow()
                is HomeVaultSelection.Vault ->
                    observeSearchEntry(SearchEntrySelection.Vault(it.shareId))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = emptyList()
        )

    private data class ActionRefreshingWrapper(
        val refreshing: IsRefreshingState,
        val actionState: ActionState
    )

    private val isRefreshing: MutableStateFlow<IsRefreshingState> =
        MutableStateFlow(IsRefreshingState.NotRefreshing)

    private val actionStateFlow: MutableStateFlow<ActionState> =
        MutableStateFlow(ActionState.Unknown)

    private val refreshingLoadingFlow = combine(
        isRefreshing,
        actionStateFlow,
        ::ActionRefreshingWrapper
    )

    private val sortingTypeState: MutableStateFlow<SortingType> =
        MutableStateFlow(SortingType.MostRecent)

    private val itemUiModelFlow: Flow<LoadingResult<List<ItemUiModel>>> = vaultSelectionFlow
        .flatMapLatest { vault ->
            val (shareSelection, itemState) = when (vault) {
                HomeVaultSelection.AllVaults -> ShareSelection.AllShares to ItemState.Active
                is HomeVaultSelection.Vault -> ShareSelection.Share(vault.shareId) to ItemState.Active
                HomeVaultSelection.Trash -> ShareSelection.AllShares to ItemState.Trashed
            }

            observeItems(
                selection = shareSelection,
                itemState = itemState,

                // We observe them all, because otherwise in the All part of the search we would not
                // know how many ItemTypes are there for the other ItemTypes.
                // We filter out the results using the filterByType function
                filter = ItemTypeFilter.All
            ).asResultWithoutLoading()
                .map { itemResult ->
                    itemResult.map { list ->
                        encryptionContextProvider.withEncryptionContext {
                            list.map { it.toUiModel(this@withEncryptionContext) }
                        }
                    }
                }
                .distinctUntilChanged()
        }

    private val filteredSearchEntriesFlow = combine(
        itemUiModelFlow,
        searchEntryState
    ) { itemResult, searchEntryList ->
        itemResult.map { list ->
            val searchEntryMap = searchEntryList.associateBy { it.itemId to it.shareId }
            list.filter { searchEntryMap.containsKey(it.id to it.shareId) }
                .sortedByDescending { searchEntryMap[it.id to it.shareId]?.createTime }
                .takeIf { it.isNotEmpty() }
                ?.let { persistentListOf(GroupedItemList(NoGrouping, it)) }
                ?: persistentListOf()
        }
    }.distinctUntilChanged()

    private val sortedListItemFlow = combine(
        itemUiModelFlow,
        sortingTypeState
    ) { result, sortingType ->
        when (sortingType) {
            SortingType.TitleAsc -> result.map { list -> list.sortByTitleAsc() }
            SortingType.TitleDesc -> result.map { list -> list.sortByTitleDesc() }
            SortingType.CreationAsc -> result.map { list -> list.sortByCreationAsc() }
            SortingType.CreationDesc -> result.map { list -> list.sortByCreationDesc() }
            SortingType.MostRecent -> result.map { list -> list.sortByMostRecent(clock.now()) }
        }
    }.distinctUntilChanged()

    @OptIn(FlowPreview::class)
    private val textFilterListItemFlow = combine(
        sortedListItemFlow,
        searchQueryState.debounce(DEBOUNCE_TIMEOUT)
    ) { result, searchQuery ->
        isProcessingSearchState.update { IsProcessingSearchState.NotLoading }
        if (searchQuery.isNotBlank()) {
            result.map { grouped ->
                grouped.map { GroupedItemList(it.key, filterByQuery(it.items, searchQuery)) }
            }
        } else {
            result
        }
    }.flowOn(Dispatchers.Default)

    private val resultsFlow = combine(
        filteredSearchEntriesFlow,
        textFilterListItemFlow,
        itemTypeSelectionFlow,
        isInSuggestionsModeState,
        isInSearchModeState
    ) { recentSearchResult, result, itemTypeSelection, isInSuggestionsMode, isInSearchMode ->
        if (
            isInSuggestionsMode &&
            isInSearchMode
        ) {
            recentSearchResult
        } else {
            result.map { grouped ->
                grouped
                    .map {
                        if (isInSearchMode) {
                            GroupedItemList(it.key, filterByType(it.items, itemTypeSelection))
                        } else {
                            it
                        }
                    }
                    .filter { it.items.isNotEmpty() }
                    .toImmutableList()
            }
        }

    }.flowOn(Dispatchers.Default)

    private val itemTypeCountFlow = textFilterListItemFlow.map { result ->
        when (result) {
            is LoadingResult.Error -> ItemTypeCount(loginCount = 0, aliasCount = 0, noteCount = 0)
            LoadingResult.Loading -> ItemTypeCount(loginCount = 0, aliasCount = 0, noteCount = 0)
            is LoadingResult.Success -> {
                result.data.map { it.items }.flatten().let { list ->
                    ItemTypeCount(
                        loginCount = list.count { it.itemType is ItemType.Login },
                        aliasCount = list.count { it.itemType is ItemType.Alias },
                        noteCount = list.count { it.itemType is ItemType.Note }
                    )
                }
            }
        }
    }.distinctUntilChanged()

    private val searchUiStateFlow = combine(
        searchQueryState,
        isProcessingSearchState,
        isInSearchModeState,
        isInSuggestionsModeState,
        itemTypeCountFlow,
        ::SearchUiState
    )

    private val filtersWrapperFlow = combine(
        vaultSelectionFlow,
        sortingTypeState,
        itemTypeSelectionFlow,
        ::FiltersWrapper
    )

    val homeUiState = combine(
        selectedShareFlow,
        filtersWrapperFlow,
        resultsFlow,
        searchUiStateFlow,
        refreshingLoadingFlow,
    ) { selectedShare, filtersWrapper, itemsResult, searchUiState, refreshingLoading ->
        val isLoading = IsLoadingState.from(itemsResult is LoadingResult.Loading)

        val items = when (itemsResult) {
            LoadingResult.Loading -> persistentListOf()
            is LoadingResult.Success -> itemsResult.data
            is LoadingResult.Error -> {
                PassLogger.e(TAG, itemsResult.exception, "Observe items error")
                snackbarDispatcher(ObserveItemsError)
                persistentListOf()
            }
        }

        HomeUiState(
            homeListUiState = HomeListUiState(
                isLoading = isLoading,
                isRefreshing = refreshingLoading.refreshing,
                actionState = refreshingLoading.actionState,
                items = items,
                selectedShare = selectedShare,
                homeVaultSelection = filtersWrapper.vaultSelection,
                homeItemTypeSelection = filtersWrapper.itemTypeSelection,
                sortingType = filtersWrapper.sortingSelection
            ),
            searchUiState = searchUiState
        )
    }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Lazily,
            initialValue = HomeUiState.Loading
        )

    fun onSearchQueryChange(query: String) {
        if (query.contains("\n")) return

        searchQueryState.update { query }
        isInSuggestionsModeState.update { false }
        isProcessingSearchState.update { IsProcessingSearchState.Loading }
    }

    fun onStopSearching() {
        searchQueryState.update { "" }
        isInSearchModeState.update { false }
        isInSuggestionsModeState.update { false }
        itemTypeSelectionFlow.update { HomeItemTypeSelection.AllItems }
    }

    fun onEnterSearch() {
        searchQueryState.update { "" }
        isInSearchModeState.update { true }
        if (searchEntryState.value.isNotEmpty()) {
            isInSuggestionsModeState.update { true }
        }
        if (!hasEnteredSearch) {
            telemetryManager.sendEvent(SearchTriggered)
        }
        hasEnteredSearch = true
    }

    fun onSortingTypeChanged(sortingType: SortingType) {
        sortingTypeState.update { sortingType }
    }

    fun onRefresh() = viewModelScope.launch(coroutineExceptionHandler) {
        isRefreshing.update { IsRefreshingState.Refreshing }
        applyPendingEvents()
            .onError { t ->
                PassLogger.e(TAG, t, "Apply pending events failed")
                snackbarDispatcher(RefreshError)
            }

        isRefreshing.update { IsRefreshingState.NotRefreshing }
    }

    fun sendItemToTrash(item: ItemUiModel?) = viewModelScope.launch(coroutineExceptionHandler) {
        if (item == null) return@launch

        trashItem(shareId = item.shareId, itemId = item.id)
            .onSuccess {
                when (item.itemType) {
                    is ItemType.Alias ->
                        snackbarDispatcher(AliasMovedToTrash)
                    is ItemType.Login ->
                        snackbarDispatcher(LoginMovedToTrash)
                    is ItemType.Note ->
                        snackbarDispatcher(NoteMovedToTrash)
                    ItemType.Password -> {}
                }
            }
            .onError {
                PassLogger.e(TAG, it, "Trash item failed")
                snackbarDispatcher(HomeSnackbarMessage.MoveToTrashError)
            }
    }

    fun copyToClipboard(text: String, homeClipboardType: HomeClipboardType) {
        viewModelScope.launch {
            when (homeClipboardType) {
                HomeClipboardType.Alias -> {
                    withContext(Dispatchers.IO) {
                        clipboardManager.copyToClipboard(text = text)
                    }
                    snackbarDispatcher(HomeSnackbarMessage.AliasCopied)
                }
                HomeClipboardType.Note -> {
                    withContext(Dispatchers.IO) {
                        clipboardManager.copyToClipboard(text = text)
                    }
                    snackbarDispatcher(HomeSnackbarMessage.NoteCopied)
                }
                HomeClipboardType.Password -> {
                    withContext(Dispatchers.IO) {
                        clipboardManager.copyToClipboard(
                            text = encryptionContextProvider.withEncryptionContext { decrypt(text) },
                            isSecure = true
                        )
                    }
                    snackbarDispatcher(HomeSnackbarMessage.PasswordCopied)
                }
                HomeClipboardType.Username -> {
                    withContext(Dispatchers.IO) {
                        clipboardManager.copyToClipboard(text = text)
                    }
                    snackbarDispatcher(HomeSnackbarMessage.UsernameCopied)
                }
            }
        }
    }

    fun setItemTypeSelection(homeItemTypeSelection: HomeItemTypeSelection) {
        itemTypeSelectionFlow.update { homeItemTypeSelection }
        isInSuggestionsModeState.update { false }
    }

    fun setVaultSelection(homeVaultSelection: HomeVaultSelection) {
        vaultSelectionFlow.update { homeVaultSelection }
    }

    fun restoreActionState() {
        actionStateFlow.update { ActionState.Unknown }
    }

    fun restoreItem(item: ItemUiModel) = viewModelScope.launch {
        actionStateFlow.update { ActionState.Loading }
        runCatching {
            restoreItem.invoke(shareId = item.shareId, itemId = item.id)
        }.onSuccess {
            actionStateFlow.update { ActionState.Done }
            PassLogger.i(TAG, "Item restored successfully")
        }.onFailure {
            PassLogger.e(TAG, it, "Error restoring item")
            actionStateFlow.update { ActionState.Done }
            snackbarDispatcher(RestoreItemsError)
        }
    }

    fun deleteItem(item: ItemUiModel) = viewModelScope.launch {
        actionStateFlow.update { ActionState.Loading }
        runCatching {
            deleteItem.invoke(shareId = item.shareId, itemId = item.id)
        }.onSuccess {
            actionStateFlow.update { ActionState.Done }
            PassLogger.i(TAG, "Item deleted successfully")
            telemetryManager.sendEvent(ItemDelete(EventItemType.from(item.itemType)))
        }.onFailure {
            PassLogger.e(TAG, it, "Error deleting item")
            actionStateFlow.update { ActionState.Done }
            snackbarDispatcher(DeleteItemError)
        }
    }

    fun clearTrash() = viewModelScope.launch {
        actionStateFlow.update { ActionState.Loading }

        val deletedItems = homeUiState.value.homeListUiState.items
        runCatching {
            clearTrash.invoke()
        }.onSuccess {
            actionStateFlow.update { ActionState.Done }
            PassLogger.i(TAG, "Trash cleared successfully")
            emitDeletedItems(deletedItems)
        }.onFailure {
            PassLogger.e(TAG, it, "Error clearing trash")
            actionStateFlow.update { ActionState.Done }
            snackbarDispatcher(ClearTrashError)
        }
    }

    fun restoreItems() = viewModelScope.launch {
        actionStateFlow.update { ActionState.Loading }
        runCatching {
            restoreItems.invoke()
        }.onSuccess {
            actionStateFlow.update { ActionState.Done }
            PassLogger.i(TAG, "Items restored successfully")
        }.onFailure {
            PassLogger.e(TAG, it, "Error restoring items")
            actionStateFlow.update { ActionState.Done }
            snackbarDispatcher(RestoreItemsError)
        }
    }

    fun onItemClicked(shareId: ShareId, itemId: ItemId) {
        if (homeUiState.value.searchUiState.inSearchMode) {
            viewModelScope.launch {
                addSearchEntry(shareId, itemId)
            }
            telemetryManager.sendEvent(SearchItemClicked)
        }
    }

    fun onClearAllRecentSearch() {
        viewModelScope.launch {
            deleteAllSearchEntry()
            isInSuggestionsModeState.update { false }
        }
    }

    private suspend fun shareIdToShare(shareId: ShareId): Option<ShareUiModel> {
        val shareResult = getShareById(shareId = shareId)
            .map(Share?::toOption)
            .map {
                it.flatMap { share ->
                    when (val asVault = share.toVault(encryptionContextProvider)) {
                        None -> None
                        is Some -> ShareUiModel.fromVault(asVault.value).toOption()
                    }
                }
            }

        return when (shareResult) {
            LoadingResult.Loading -> None
            is LoadingResult.Error -> {
                PassLogger.w(TAG, shareResult.exception, "Error getting share by id")
                None
            }
            is LoadingResult.Success -> {
                shareResult.data
            }
        }
    }

    private fun filterByType(
        items: List<ItemUiModel>,
        itemTypeSelection: HomeItemTypeSelection
    ) = items.filter { item ->
        when (itemTypeSelection) {
            HomeItemTypeSelection.AllItems -> true
            HomeItemTypeSelection.Aliases -> item.itemType is ItemType.Alias
            HomeItemTypeSelection.Logins -> item.itemType is ItemType.Login
            HomeItemTypeSelection.Notes -> item.itemType is ItemType.Note
        }
    }

    private fun emitDeletedItems(items: List<GroupedItemList>) {
        items.forEach { list ->
            list.items.forEach { item ->
                telemetryManager.sendEvent(ItemDelete(EventItemType.from(item.itemType)))
            }
        }
    }

    companion object {
        private const val DEBOUNCE_TIMEOUT = 300L
        private const val TAG = "HomeViewModel"
    }
}
