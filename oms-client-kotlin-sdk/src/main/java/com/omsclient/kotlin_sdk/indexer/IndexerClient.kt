package com.omsclient.kotlin_sdk.indexer

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.models.TokenBalance
import com.omsclient.kotlin_sdk.models.TokenBalancesPage
import com.omsclient.kotlin_sdk.models.TokenBalancesPageRequest
import com.omsclient.kotlin_sdk.models.TokenBalancesResult
import com.omsclient.kotlin_sdk.models.TokenContractInfo
import com.omsclient.kotlin_sdk.models.TokenMetadata
import com.omsclient.kotlin_sdk.models.TokenMetadataAsset
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.arrayOrEmpty
import com.omsclient.kotlin_sdk.network.boolean
import com.omsclient.kotlin_sdk.network.int
import com.omsclient.kotlin_sdk.network.long
import com.omsclient.kotlin_sdk.network.objectOrNull
import com.omsclient.kotlin_sdk.network.parseJsonObject
import com.omsclient.kotlin_sdk.network.string
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
        val response =
            transport.postJson(
                baseUrl = environment.indexerUrlFor(network),
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
                headers = defaultHeaders(),
            )

        val root = parseJsonObject(response.body)
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
        val response =
            transport.postJson(
                baseUrl = environment.indexerUrlFor(network),
                path = "/GetNativeTokenBalance",
                body =
                    buildJsonObject {
                        put("accountAddress", walletAddress)
                    }.toString(),
                headers = defaultHeaders(),
            )

        val balanceObject = parseJsonObject(response.body).objectOrNull("balance") ?: return null
        return TokenBalance(
            contractType = "NATIVE",
            contractAddress = null,
            accountAddress = balanceObject.string("accountAddress"),
            tokenId = null,
            balance = balanceObject.string("balance") ?: balanceObject.string("balanceWei"),
            blockHash = null,
            blockNumber = null,
            chainId = balanceObject.long("chainId") ?: network.id.toLong(),
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
            imageData = string("imageData"),
            externalUrl = string("externalUrl"),
            backgroundColor = string("backgroundColor"),
            animationUrl = string("animationUrl"),
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
}
