/*
 * Copyright (c) 2023 Proton AG
 * This file is part of Proton AG and Proton Pass.
 *
 * Proton Pass is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Pass is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Pass.  If not, see <https://www.gnu.org/licenses/>.
 */

package proton.android.pass.featuremigrate.impl.confirmvault

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import proton.android.pass.common.api.Some
import proton.android.pass.composecomponents.impl.uievents.IsLoadingState
import proton.android.pass.data.fakes.usecases.TestGetVaultWithItemCountById
import proton.android.pass.data.fakes.usecases.TestMigrateItem
import proton.android.pass.data.fakes.usecases.TestMigrateVault
import proton.android.pass.featuremigrate.impl.MigrateModeArg
import proton.android.pass.featuremigrate.impl.MigrateModeValue
import proton.android.pass.navigation.api.CommonNavArgId
import proton.android.pass.navigation.api.CommonOptionalNavArgId
import proton.android.pass.navigation.api.DestinationShareNavArgId
import proton.android.pass.notifications.fakes.TestSnackbarDispatcher
import proton.android.pass.test.MainDispatcherRule
import proton.android.pass.test.TestSavedStateHandle
import proton.pass.domain.ItemId
import proton.pass.domain.ShareId
import proton.pass.domain.Vault
import proton.pass.domain.VaultWithItemCount

class MigrateConfirmVaultViewModelTest {

    @get:Rule
    val dispatcher = MainDispatcherRule()

    private lateinit var instance: MigrateConfirmVaultViewModel
    private lateinit var migrateItem: TestMigrateItem
    private lateinit var migrateVault: TestMigrateVault
    private lateinit var getVaultById: TestGetVaultWithItemCountById
    private lateinit var snackbarDispatcher: TestSnackbarDispatcher

    @Before
    fun setup() {
        migrateItem = TestMigrateItem()
        migrateVault = TestMigrateVault()
        snackbarDispatcher = TestSnackbarDispatcher()
        getVaultById = TestGetVaultWithItemCountById()
        instance = MigrateConfirmVaultViewModel(
            migrateItem = migrateItem,
            migrateVault = migrateVault,
            snackbarDispatcher = snackbarDispatcher,
            getVaultById = getVaultById,
            savedStateHandle = TestSavedStateHandle.create().apply {
                set(CommonNavArgId.ShareId.key, SHARE_ID.id)
                set(DestinationShareNavArgId.key, DESTINATION_SHARE_ID.id)
                set(MigrateModeArg.key, MODE.name)
                set(CommonOptionalNavArgId.ItemId.key, ITEM_ID.id)
            }
        )
    }

    @Test
    fun `stops loading when vault has emitted`() = runTest {
        val vault = sourceVault()
        getVaultById.emitValue(vault)
        instance.state.test {
            val secondState = awaitItem()
            assertThat(secondState.isLoading).isInstanceOf(IsLoadingState.NotLoading::class.java)
            assertThat(secondState.vault.isNotEmpty()).isTrue()

            val itemVault = secondState.vault.value()!!
            assertThat(itemVault).isEqualTo(vault)
        }
    }

    @Test
    fun `emits close if there is an error in get vault`() = runTest {
        getVaultById.sendException(IllegalStateException("test"))
        instance.state.test {
            val state = awaitItem()
            assertThat(state.event.isNotEmpty()).isTrue()

            val eventCasted = state.event as Some<ConfirmMigrateEvent>
            assertThat(eventCasted.value).isInstanceOf(ConfirmMigrateEvent.Close::class.java)
        }
    }

    @Test
    fun `emits close if cancel is clicked`() = runTest {
        getVaultById.emitValue(sourceVault())
        instance.onCancel()
        instance.state.test {
            val state = awaitItem()
            val eventCasted = state.event as Some<ConfirmMigrateEvent>
            assertThat(eventCasted.value).isInstanceOf(ConfirmMigrateEvent.Close::class.java)

            cancelAndConsumeRemainingEvents()
        }
    }

    private fun sourceVault(): VaultWithItemCount = VaultWithItemCount(
        vault = Vault(
            shareId = SHARE_ID,
            name = "source",
        ),
        activeItemCount = 1,
        trashedItemCount = 0
    )

    companion object {
        private val SHARE_ID = ShareId("123")
        private val DESTINATION_SHARE_ID = ShareId("456")
        private val ITEM_ID = ItemId("789")

        private val MODE = MigrateModeValue.SingleItem
    }
}
