package me.proton.android.pass.ui.create.note

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.proton.core.accountmanager.domain.AccountManager
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.decrypt
import me.proton.core.pass.domain.Item
import me.proton.core.pass.domain.ItemId
import me.proton.core.pass.domain.ShareId
import me.proton.core.pass.domain.repositories.ItemRepository
import me.proton.core.pass.domain.usecases.GetShareById

@HiltViewModel
class UpdateNoteViewModel @Inject constructor(
    private val cryptoContext: CryptoContext,
    private val accountManager: AccountManager,
    private val itemRepository: ItemRepository,
    private val getShare: GetShareById,
) : BaseNoteViewModel() {

    private var _item: Item? = null

    fun setItem(shareId: ShareId, itemId: ItemId) = viewModelScope.launch {
        if (_item == null) {
            viewState.value = viewState.value.copy(state = State.Loading)
            accountManager.getPrimaryUserId().first { userId -> userId != null }?.let { userId ->
                val retrievedItem = itemRepository.getById(userId, shareId, itemId)
                _item = retrievedItem

                viewState.value = ViewState(
                    state = State.Idle,
                    modelState = ModelState(
                        title = retrievedItem.title.decrypt(cryptoContext.keyStoreCrypto),
                        note = retrievedItem.note.decrypt(cryptoContext.keyStoreCrypto)
                    )
                )
            }
        }
    }

    fun updateItem(shareId: ShareId) = viewModelScope.launch {
        requireNotNull(_item)
        viewState.value = viewState.value.copy(state = State.Loading)
        accountManager.getPrimaryUserId().first { userId -> userId != null }?.let { userId ->
            val share = getShare.invoke(userId, shareId)
            requireNotNull(share)
            val itemContents = viewState.value.modelState.toItemContents()
            val updatedItem = itemRepository.updateItem(userId, share, _item!!, itemContents)
            viewState.value = viewState.value.copy(state = State.Success(updatedItem.id))
        }
    }
}
