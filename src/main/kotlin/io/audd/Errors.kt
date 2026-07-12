package io.audd

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Sealed exception hierarchy for the AudD SDK.
 *
 * Catching [AudDException] catches every error this SDK raises. Catch a
 * specific subclass for narrower error handling.
 */
public sealed class AudDException(
    message: String,
    public val cause0: Throwable? = null,
) : RuntimeException(message, cause0)

/**
 * Server returned `status: error`. Carries the AudD error code and the full
 * server echo (request_params, request_api_method, branded message, etc.).
 */
public open class AudDApiException(
    public val errorCode: Int,
    public val serverMessage: String,
    public val httpStatus: Int,
    public val requestId: String?,
    public val requestedParams: Map<String, JsonElement> = emptyMap(),
    public val requestMethod: String? = null,
    public val brandedMessage: String? = null,
    public val rawResponse: JsonElement? = null,
) : AudDException("[#$errorCode] $serverMessage")

/** 900 / 901 / 903 — token is the problem. */
public class AudDAuthenticationException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 902 — quota / per-copy limit reached. */
public class AudDQuotaException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 904 / 905 — endpoint not available with this token. */
public open class AudDSubscriptionException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 904 raised specifically from custom_catalog.* — overridden message clarifies the intent gap. */
public class AudDCustomCatalogAccessException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDSubscriptionException(
    errorCode,
    buildCustomCatalogMessage(serverMessage),
    httpStatus,
    requestId,
    requestedParams,
    requestMethod,
    brandedMessage,
    rawResponse,
)

private fun buildCustomCatalogMessage(serverMessage: String): String =
    "Adding songs to your custom catalog requires enterprise access that isn't " +
        "enabled on your account.\n\n" +
        "Note: the custom-catalog endpoint is for adding songs to your private " +
        "fingerprint database, not for music recognition. If you intended to " +
        "identify music, use recognize(...) (or recognizeEnterprise(...) for " +
        "files longer than 25 seconds) instead.\n\n" +
        "To request custom-catalog access, contact api@audd.io.\n\n" +
        "[Server message: $serverMessage]"

/** 50 / 51 / 600 / 601 / 602 / 700 / 701 / 702 / 906 — bad input from caller. */
public class AudDInvalidRequestException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 300 / 400 / 500 — caller's audio file is the problem. */
public class AudDInvalidAudioException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 611 — per-stream daily rate limit (and HTTP 429). */
public class AudDRateLimitException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 610 — subscription stream slots exhausted. */
public class AudDStreamLimitException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 907 — song hasn't been released yet. */
public class AudDNotReleasedException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 19 family + 31337 — security/abuse/sanctions/IP ban/maintenance. */
public class AudDBlockedException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 20 — app needs update / paid version required. */
public class AudDNeedsUpdateException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** 100 / 1000 / unknown codes / generic upstream failures. */
public class AudDServerException(
    errorCode: Int,
    serverMessage: String,
    httpStatus: Int,
    requestId: String?,
    requestedParams: Map<String, JsonElement> = emptyMap(),
    requestMethod: String? = null,
    brandedMessage: String? = null,
    rawResponse: JsonElement? = null,
) : AudDApiException(errorCode, serverMessage, httpStatus, requestId, requestedParams, requestMethod, brandedMessage, rawResponse)

/** Network / TLS / timeout — no response received. */
public class AudDConnectionException(
    message: String,
    public val original: Throwable? = null,
) : AudDException(message, original)

/** Server returned malformed (or non-JSON) response body. */
public class AudDSerializationException(
    message: String,
    public val rawText: String = "",
    cause: Throwable? = null,
) : AudDException(message, cause)

internal fun raiseFromErrorResponse(
    body: JsonObject,
    httpStatus: Int,
    requestId: String?,
    customCatalogContext: Boolean = false,
): Nothing {
    val err = body["error"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())
    val code = err["error_code"]?.jsonPrimitive?.intOrNull ?: 0
    val message = err["error_message"]?.jsonPrimitive?.contentOrNull ?: ""
    val requestedParams = (body["request_params"] as? JsonObject)
        ?: (body["requested_params"] as? JsonObject)
        ?: JsonObject(emptyMap())
    val requestMethod = body["request_api_method"]?.jsonPrimitive?.contentOrNull
    val branded = brandedMessage(body["result"])

    val ctorArgs = ApiCtorArgs(
        errorCode = code,
        serverMessage = message,
        httpStatus = httpStatus,
        requestId = requestId,
        requestedParams = requestedParams,
        requestMethod = requestMethod,
        brandedMessage = branded,
        rawResponse = body,
    )

    val ex: AudDApiException = when (code) {
        900, 901, 903 -> AudDAuthenticationException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        902 -> AudDQuotaException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        904, 905 -> if (customCatalogContext) {
            AudDCustomCatalogAccessException(
                ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
                ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
            )
        } else {
            AudDSubscriptionException(
                ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
                ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
            )
        }
        50, 51, 600, 601, 602, 700, 701, 702, 906 -> AudDInvalidRequestException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        300, 400, 500 -> AudDInvalidAudioException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        611 -> AudDRateLimitException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        610 -> AudDStreamLimitException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        907 -> AudDNotReleasedException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        19, 31337 -> AudDBlockedException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        20 -> AudDNeedsUpdateException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
        else -> AudDServerException(
            ctorArgs.errorCode, ctorArgs.serverMessage, ctorArgs.httpStatus, ctorArgs.requestId,
            ctorArgs.requestedParams, ctorArgs.requestMethod, ctorArgs.brandedMessage, ctorArgs.rawResponse,
        )
    }
    throw ex
}

internal data class ApiCtorArgs(
    val errorCode: Int,
    val serverMessage: String,
    val httpStatus: Int,
    val requestId: String?,
    val requestedParams: Map<String, JsonElement>,
    val requestMethod: String?,
    val brandedMessage: String?,
    val rawResponse: JsonElement?,
)

private fun brandedMessage(result: JsonElement?): String? {
    val obj = result as? JsonObject ?: return null
    val artist = obj["artist"]?.jsonPrimitive?.contentOrNull
    val title = obj["title"]?.jsonPrimitive?.contentOrNull
    if (artist.isNullOrBlank() && title.isNullOrBlank()) return null
    return listOfNotNull(artist, title).joinToString(" — ")
}
