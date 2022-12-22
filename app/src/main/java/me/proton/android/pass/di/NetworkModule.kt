/*
 * Copyright (c) 2022 Proton Technologies AG
 * This file is part of Proton Technologies AG and Proton Mail.
 *
 * Proton Mail is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Proton Mail is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Proton Mail. If not, see <https://www.gnu.org/licenses/>.
 */

package me.proton.android.pass.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.proton.android.pass.appconfig.api.AppConfig
import me.proton.android.pass.network.PassApiClient
import me.proton.core.network.data.client.ExtraHeaderProviderImpl
import me.proton.core.network.data.di.AlternativeApiPins
import me.proton.core.network.data.di.BaseProtonApiUrl
import me.proton.core.network.data.di.CertificatePins
import me.proton.core.network.data.di.Constants
import me.proton.core.network.data.di.DohProviderUrls
import me.proton.core.network.domain.ApiClient
import me.proton.core.network.domain.client.ExtraHeaderProvider
import me.proton.core.network.domain.serverconnection.DohAlternativesListener
import me.proton.core.util.kotlin.takeIfNotBlank
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("LongParameterList")
object NetworkModule {

    @Provides
    @BaseProtonApiUrl
    fun provideProtonApiUrl(appConfig: AppConfig): HttpUrl =
        "https://api.${appConfig.host}".toHttpUrl()

    @Provides
    @Singleton
    fun provideExtraHeaderProvider(appConfig: AppConfig): ExtraHeaderProvider =
        ExtraHeaderProviderImpl().apply {
            appConfig.proxyToken
                ?.takeIfNotBlank()
                ?.let { addHeaders("X-atlas-secret" to it) }
        }

    @DohProviderUrls
    @Provides
    fun provideDohProviderUrls(): Array<String> = Constants.DOH_PROVIDERS_URLS

    @CertificatePins
    @Provides
    fun provideCertificatePins(appConfig: AppConfig): Array<String> =
        Constants.DEFAULT_SPKI_PINS.takeIf { appConfig.useDefaultPins } ?: emptyArray()

    @AlternativeApiPins
    @Provides
    fun provideAlternativeApiPins(appConfig: AppConfig): List<String> =
        Constants.ALTERNATIVE_API_SPKI_PINS.takeIf { appConfig.useDefaultPins } ?: emptyList()

    @Provides
    @Singleton
    fun provideDohAlternativesListener(): DohAlternativesListener? = null
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindModule {
    @Binds
    abstract fun bindApiClient(apiClient: PassApiClient): ApiClient
}
