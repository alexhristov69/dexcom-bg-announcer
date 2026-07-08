package com.dexcom.bgannouncer.dexcom

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
@Serializable
data class ShareErrorResponse(
    @SerialName("Code") val code: String? = null,
    @SerialName("Message") val message: String? = null,
)

@Serializable
data class ShareLoginByNameRequest(
    @SerialName("accountName") val accountName: String,
    @SerialName("password") val password: String,
    @SerialName("applicationId") val applicationId: String,
)

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
    @SerialName("Trend")
    @Serializable(with = GlucoseTrendFieldSerializer::class)
    val trend: GlucoseTrend? = null,
    @SerialName("ST") val systemTime: String? = null,
    @SerialName("DT") val displayTime: String? = null,
    @SerialName("WT") val warmupTime: String? = null,
)

object GlucoseTrendFieldSerializer : KSerializer<GlucoseTrend> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("GlucoseTrend", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): GlucoseTrend {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder != null) {
            return when (val element = jsonDecoder.decodeJsonElement()) {
                is JsonPrimitive -> when {
                    element.isString -> GlucoseTrend.fromName(element.content)
                    element.intOrNull != null -> GlucoseTrend.fromCode(element.intOrNull!!)
                    else -> GlucoseTrend.NONE
                }
                else -> GlucoseTrend.NONE
            }
        }
        return GlucoseTrend.fromCode(decoder.decodeInt())
    }

    override fun serialize(encoder: Encoder, value: GlucoseTrend) {
        encoder.encodeInt(value.code)
    }
}