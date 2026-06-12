package com.omsclient.kotlin_sdk.indexer

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.OmsRequestException
import com.omsclient.kotlin_sdk.OmsResponseException
import com.omsclient.kotlin_sdk.OmsSdkErrorCode
import com.omsclient.kotlin_sdk.OmsSdkOperation
import com.omsclient.kotlin_sdk.OmsUpstreamError
import com.omsclient.kotlin_sdk.OmsUpstreamService
import com.omsclient.kotlin_sdk.models.TokenBalance
import com.omsclient.kotlin_sdk.models.TokenBalancesPage
import com.omsclient.kotlin_sdk.models.TokenBalancesPageRequest
import com.omsclient.kotlin_sdk.models.TokenBalancesResult
import com.omsclient.kotlin_sdk.models.TokenContractInfo
import com.omsclient.kotlin_sdk.models.TokenMetadata
import com.omsclient.kotlin_sdk.models.TokenMetadataAsset
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.OMSClientHttpResponse
import com.omsclient.kotlin_sdk.network.OMSClientJson
import com.omsclient.kotlin_sdk.network.arrayOrEmpty
import com.omsclient.kotlin_sdk.network.boolean
import com.omsclient.kotlin_sdk.network.int
import com.omsclient.kotlin_sdk.network.long
import com.omsclient.kotlin_sdk.network.objectOrNull
import com.omsclient.kotlin_sdk.network.parseJsonObject
import com.omsclient.kotlin_sdk.network.string
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class IndexerClient internal constructor(
    private val publishableKey: String,
    private val environment: OMSClientEnvironment,
    private val transport: OMSClientHttpClient = OMSClientHttpClient(),
) {
    /**
     * Gets token balances for [walletAddress] on [network].
     */
    suspend fun getTokenBalances(
        network: Network,
        contractAddress: String? = null,
        walletAddress: String,
        includeMetadata: Boolean,
        page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
    ): TokenBalancesResult {
        val operation = OmsSdkOperation.IndexerGetTokenBalances
        val response =
            postIndexerJson(
                operation = operation,
                network = network,
                path = "/GetTokenBalances",
                body =
                    buildJsonObject {
                        putJsonObject("page") {
                            put("page", page.page)
                            put("pageSize", page.pageSize)
                            put("more", false)
                        }
                        contractAddress?.let { put("contractAddress", it) }
                        put("accountAddress", walletAddress)
                        put("includeMetadata", includeMetadata)
                    }.toString(),
            )

        val root = parseIndexerJsonObject(response, operation)
        val pageObject = root.objectOrNull("page")
        val page =
            pageObject?.let {
                TokenBalancesPage(
                    page = it.long("page")?.toInt() ?: 0,
                    pageSize = it.long("pageSize")?.toInt() ?: 0,
                    more = it.boolean("more") == true,
                )
            }

        val balances =
            root.arrayOrEmpty("balances").mapNotNull { element ->
                val objectValue = element as? JsonObject ?: return@mapNotNull null
                objectValue.toTokenBalance()
            }

        return TokenBalancesResult(
            status = response.statusCode,
            page = page,
            balances = balances,
        )
    }

    /**
     * Gets the native token balance for [walletAddress] on [network].
     */
    suspend fun getNativeTokenBalance(
        network: Network,
        walletAddress: String,
    ): TokenBalance? {
        val operation = OmsSdkOperation.IndexerGetNativeTokenBalance
        val response =
            postIndexerJson(
                operation = operation,
                network = network,
                path = "/GetNativeTokenBalance",
                body =
                    buildJsonObject {
                        put("accountAddress", walletAddress)
                    }.toString(),
            )

        val balanceObject = parseIndexerJsonObject(response, operation).objectOrNull("balance") ?: return null
        return TokenBalance(
            contractType = "NATIVE",
            contractAddress = null,
            accountAddress = balanceObject.string("accountAddress"),
            tokenId = null,
            balance = balanceObject.string("balance") ?: balanceObject.string("balanceWei"),
            balanceUSD = balanceObject.string("balanceUSD"),
            priceUSD = balanceObject.string("priceUSD"),
            priceUpdatedAt = balanceObject.string("priceUpdatedAt"),
            blockHash = null,
            blockNumber = null,
            chainId = balanceObject.long("chainId") ?: network.id.toLong(),
        )
    }

    private suspend fun postIndexerJson(
        operation: OmsSdkOperation,
        network: Network,
        path: String,
        body: String,
    ): OMSClientHttpResponse {
        val response =
            try {
                transport.postJsonWithStatus(
                    baseUrl = environment.indexerUrlFor(network),
                    path = path,
                    body = body,
                    headers = defaultHeaders(),
                )
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                throw OmsRequestException(
                    operation = operation,
                    upstreamError = throwable.toIndexerUpstreamError(),
                    message = throwable.message ?: "${operation.id} request failed",
                    cause = throwable,
                )
            }

        if (response.statusCode !in 200..299) {
            val parsed = parseJsonOrText(response.body)
            val message = indexerResponseMessage(parsed, operation, response.statusCode)
            throw OmsRequestException(
                code = OmsSdkErrorCode.HttpError,
                operation = operation,
                status = response.statusCode,
                retryable = response.statusCode >= 500,
                upstreamError =
                    parsed.toIndexerUpstreamError(
                        status = response.statusCode,
                        fallbackMessage = message,
                    ),
                message = message,
            )
        }

        return response
    }

    private fun parseIndexerJsonObject(
        response: OMSClientHttpResponse,
        operation: OmsSdkOperation,
    ): JsonObject =
        try {
            parseJsonObject(response.body)
        } catch (throwable: Throwable) {
            val message = "Invalid JSON response from ${operation.id}"
            throw OmsResponseException(
                operation = operation,
                status = response.statusCode,
                upstreamError =
                    OmsUpstreamError(
                        service = OmsUpstreamService.Indexer,
                        message = message,
                        status = response.statusCode,
                    ),
                message = message,
                cause = throwable,
            )
        }

    private fun defaultHeaders(): Map<String, String> =
        mapOf(
            OMSClientEnvironment.accessKeyHeaderName to publishableKey,
            "Accept" to "application/json",
        )

    private fun JsonObject.toTokenBalance(): TokenBalance =
        TokenBalance(
            contractType = string("contractType"),
            contractAddress = string("contractAddress"),
            accountAddress = string("accountAddress"),
            tokenId = string("tokenId") ?: string("tokenID"),
            balance = string("balance"),
            balanceUSD = string("balanceUSD"),
            priceUSD = string("priceUSD"),
            priceUpdatedAt = string("priceUpdatedAt"),
            blockHash = string("blockHash"),
            blockNumber = long("blockNumber"),
            chainId = long("chainId"),
            uniqueCollectibles = string("uniqueCollectibles"),
            isSummary = boolean("isSummary"),
            contractInfo = objectOrNull("contractInfo")?.toTokenContractInfo(),
            tokenMetadata = objectOrNull("tokenMetadata")?.toTokenMetadata(),
        )

    private fun JsonObject.toTokenContractInfo(): TokenContractInfo =
        TokenContractInfo(
            chainId = long("chainId"),
            address = string("address"),
            source = string("source"),
            name = string("name"),
            type = string("type"),
            symbol = string("symbol"),
            decimals = int("decimals"),
            logoURI = string("logoURI"),
            deployed = boolean("deployed"),
            bytecodeHash = string("bytecodeHash"),
            extensions = objectOrNull("extensions")?.toMap(),
            updatedAt = string("updatedAt"),
            queuedAt = string("queuedAt"),
            status = string("status"),
        )

    private fun JsonObject.toTokenMetadata(): TokenMetadata =
        TokenMetadata(
            chainId = long("chainId"),
            contractAddress = string("contractAddress"),
            tokenId = string("tokenId") ?: string("tokenID"),
            source = string("source"),
            name = string("name"),
            description = string("description"),
            image = string("image"),
            video = string("video"),
            audio = string("audio"),
            properties = objectOrNull("properties")?.toMap(),
            attributes =
                (this["attributes"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { it.toMap() },
            imageData = string("image_data"),
            externalUrl = string("external_url"),
            backgroundColor = string("background_color"),
            animationUrl = string("animation_url"),
            decimals = int("decimals"),
            updatedAt = string("updatedAt"),
            assets =
                (this["assets"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { it.toTokenMetadataAsset() },
            status = string("status"),
            queuedAt = string("queuedAt"),
            lastFetched = string("lastFetched"),
        )

    private fun JsonObject.toTokenMetadataAsset(): TokenMetadataAsset =
        TokenMetadataAsset(
            id = long("id"),
            collectionId = long("collectionId"),
            tokenId = string("tokenId") ?: string("tokenID"),
            url = string("url"),
            metadataField = string("metadataField"),
            name = string("name"),
            filesize = long("filesize"),
            mimeType = string("mimeType"),
            width = int("width"),
            height = int("height"),
            updatedAt = string("updatedAt"),
        )

    private fun JsonObject.toMap(): Map<String, JsonElement> = entries.associate { it.key to it.value }

    private fun parseJsonOrText(body: String): Any = runCatching { OMSClientJson.json.parseToJsonElement(body) }.getOrElse { body }

    private fun indexerResponseMessage(
        payload: Any,
        operation: OmsSdkOperation,
        status: Int,
    ): String =
        when (payload) {
            is JsonObject -> payload.string("message") ?: payload.string("msg")
            else -> null
        } ?: "${operation.id} failed with HTTP $status"

    private fun Any.toIndexerUpstreamError(
        status: Int,
        fallbackMessage: String,
    ): OmsUpstreamError =
        when (this) {
            is JsonObject -> {
                OmsUpstreamError(
                    service = OmsUpstreamService.Indexer,
                    name = string("name") ?: string("error"),
                    code = stringOrNumber("code"),
                    message = string("message") ?: string("msg") ?: fallbackMessage,
                    status = status,
                )
            }

            else -> {
                OmsUpstreamError(
                    service = OmsUpstreamService.Indexer,
                    message = fallbackMessage,
                    status = status,
                )
            }
        }

    private fun Throwable.toIndexerUpstreamError(): OmsUpstreamError =
        OmsUpstreamError(
            service = OmsUpstreamService.Indexer,
            name = javaClass.simpleName,
            message = message,
            status = null,
        )

    private fun JsonObject.stringOrNumber(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
}
