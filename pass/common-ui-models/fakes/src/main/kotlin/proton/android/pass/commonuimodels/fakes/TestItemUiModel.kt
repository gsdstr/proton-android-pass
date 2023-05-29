package proton.android.pass.commonuimodels.fakes

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import proton.android.pass.commonuimodels.api.ItemUiModel
import proton.pass.domain.ItemId
import proton.pass.domain.ItemType
import proton.pass.domain.ShareId

object TestItemUiModel {

    fun create(
        title: String = "item-title",
        note: String = "item-note",
        itemType: ItemType = ItemType.Password,
        createTime: Instant = Clock.System.now(),
        modificationTime: Instant = Clock.System.now(),
        lastAutofillTime: Instant? = null
    ): ItemUiModel {
        return ItemUiModel(
            id = ItemId(id = "item-id"),
            shareId = ShareId(id = "share-id"),
            contents = itemType,
            name = title,
            note = note,
            createTime = createTime,
            state = 0,
            modificationTime = modificationTime,
            lastAutofillTime = lastAutofillTime,
        )
    }
}
