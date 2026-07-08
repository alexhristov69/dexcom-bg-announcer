package com.dexcom.bgannouncer.dexcom

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ShareAuthRequest(
    @SerialName("accountName") val accountName: String,
    @SerialName("password") val password: String,
    @SerialName("applicationId") val applicationId: String,
)

@Serializable
data class ShareLoginRequest(
    @SerialName("accountId") val accountId: String,
    @SerialName("password") val password: String,
    @SerialName("applicationId") val applicationId: String,
)

@Serializable
data class ShareGlucoseRecord(
    @SerialName("Value") val value: Int,
    @SerialName("Trend") val trend: Int? = null,
    @SerialName("ST") val systemTime: String? = null,
    @SerialName("DT") val displayTime: String? = null,
    @SerialName("WT") val warmupTime: String? = null,
)
