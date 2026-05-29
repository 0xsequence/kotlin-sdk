package com.omsclient.kotlin_sdk.indexer

import com.omsclient.kotlin_sdk.Network
import com.omsclient.kotlin_sdk.models.TokenBalance
import com.omsclient.kotlin_sdk.models.TokenBalancesPage
import com.omsclient.kotlin_sdk.models.TokenBalancesPageRequest
import com.omsclient.kotlin_sdk.models.TokenBalancesResult
import com.omsclient.kotlin_sdk.network.OMSClientEnvironment
import com.omsclient.kotlin_sdk.network.OMSClientHttpClient
import com.omsclient.kotlin_sdk.network.arrayOrEmpty
import com.omsclient.kotlin_sdk.network.boolean
import com.omsclient.kotlin_sdk.network.long
import com.omsclient.kotlin_sdk.network.objectOrNull
import com.omsclient.kotlin_sdk.network.parseJsonObject
import com.omsclient.kotlin_sdk.network.string
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
                TokenBalance(
                    contractType = objectValue.string("contractType"),
                    contractAddress = objectValue.string("contractAddress"),
                    accountAddress = objectValue.string("accountAddress"),
                    tokenId = objectValue.string("tokenID"),
                    balance = objectValue.string("balance"),
                    blockHash = objectValue.string("blockHash"),
                    blockNumber = objectValue.long("blockNumber"),
                    chainId = objectValue.long("chainId"),
                )
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
}
