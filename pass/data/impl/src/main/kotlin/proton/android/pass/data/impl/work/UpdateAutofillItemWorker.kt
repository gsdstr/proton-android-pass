package proton.android.pass.data.impl.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import proton.android.pass.common.api.LoadingResult
import proton.android.pass.common.api.Option
import proton.android.pass.common.api.Some
import proton.android.pass.common.api.map
import proton.android.pass.common.api.toOption
import proton.android.pass.data.api.repositories.ItemRepository
import proton.android.pass.data.api.usecases.UpdateAutofillItemData
import proton.android.pass.log.api.PassLogger
import proton.pass.domain.ItemId
import proton.pass.domain.ShareId
import proton.pass.domain.entity.PackageName
import java.io.IOException

@HiltWorker
class UpdateAutofillItemWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted private val workerParameters: WorkerParameters,
    private val itemRepository: ItemRepository
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        PassLogger.i(TAG, "Starting work")
        return getData(workerParameters.inputData)
            .fold(
                onSuccess = { inputData ->
                    run(inputData).also { PassLogger.i(TAG, "Completed work") }
                },
                onFailure = { throwable ->
                    Result.failure().also { PassLogger.w(TAG, throwable) }
                }
            )
    }

    private suspend fun run(inputData: InputData): Result =
        if (inputData.shouldAssociate) {
            updateItemWithPackageNameOrUrl(inputData)
        } else {
            updateLastUsed(inputData)
        }

    private suspend fun updateItemWithPackageNameOrUrl(
        inputData: InputData
    ): Result {
        val message = "Adding package and url to item [itemId=${inputData.itemId}]" +
            " [packageName=${inputData.packageName}] " +
            " [url=${inputData.url}]"
        PassLogger.d(TAG, message)
        val result = itemRepository
            .addPackageAndUrlToItem(
                shareId = inputData.shareId,
                itemId = inputData.itemId,
                packageName = inputData.packageName,
                url = inputData.url
            )
            .map { updateLastUsed(inputData) }
        return when (result) {
            is LoadingResult.Error -> {
                PassLogger.e(TAG, result.exception)
                Result.failure()
            }
            is LoadingResult.Success -> {
                val successMessage = "Successfully added package or url and updated last used item"
                PassLogger.i(TAG, successMessage)
                Result.success()
            }
            LoadingResult.Loading -> Result.failure()
        }
    }

    private suspend fun updateLastUsed(inputData: InputData): Result =
        try {
            PassLogger.d(TAG, "Start update last used")
            itemRepository.updateItemLastUsed(inputData.shareId, inputData.itemId)
            PassLogger.d(TAG, "Completed update last used")
            Result.success()
        } catch (e: IOException) {
            PassLogger.w(TAG, e, "Failed update last used")
            Result.failure()
        }

    private fun getData(inputData: Data): kotlin.Result<InputData> {
        val shareId = inputData.getString(ARG_SHARE_ID) ?: return kotlin.Result.failure(
            IllegalStateException("Missing $ARG_SHARE_ID")
        )
        val itemId = inputData.getString(ARG_ITEM_ID) ?: return kotlin.Result.failure(
            IllegalStateException("Missing $ARG_ITEM_ID")
        )
        val packageName = inputData.getString(ARG_PACKAGE_NAME).toOption().map { PackageName(it) }
        val url = inputData.getString(ARG_URL).toOption()
        val shouldAssociate = inputData.getBoolean(ARG_SHOULD_ASSOCIATE, false)
        if (url.isEmpty() && packageName.isEmpty()) {
            return kotlin.Result.failure(
                IllegalStateException("Did not receive neither package name nor url")
            )
        }

        return kotlin.Result.success(
            InputData(
                shareId = ShareId(shareId),
                itemId = ItemId(itemId),
                packageName = packageName,
                url = url,
                shouldAssociate = shouldAssociate
            )
        )
    }

    internal data class InputData(
        val shareId: ShareId,
        val itemId: ItemId,
        val packageName: Option<PackageName>,
        val url: Option<String>,
        val shouldAssociate: Boolean
    )

    companion object {

        private const val TAG = "AddPackageNameToItemWorker"

        private const val ARG_SHARE_ID = "arg_share_id"
        private const val ARG_ITEM_ID = "arg_item_id"
        private const val ARG_PACKAGE_NAME = "arg_package_name"
        private const val ARG_URL = "arg_url"
        private const val ARG_SHOULD_ASSOCIATE = "arg_should_associate"

        fun create(data: UpdateAutofillItemData): Data {
            val extras = mutableMapOf(
                ARG_SHARE_ID to data.shareId.id,
                ARG_ITEM_ID to data.itemId.id,
                ARG_SHOULD_ASSOCIATE to data.shouldAssociate,
            )

            val packageName = data.packageName
            if (packageName is Some && packageName.value.packageName.isNotBlank()) {
                extras[ARG_PACKAGE_NAME] = packageName.value.packageName
            }

            val url = data.url
            if (url is Some && url.value.isNotBlank()) {
                extras[ARG_URL] = url.value
            }

            return Data.Builder()
                .putAll(extras.toMap())
                .build()
        }
    }
}
