package me.proton.core.pass.data.responses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateVaultResponse(
    @SerialName("Code")
    val code: Int,
    @SerialName("Share")
    val share: ShareResponse
)
