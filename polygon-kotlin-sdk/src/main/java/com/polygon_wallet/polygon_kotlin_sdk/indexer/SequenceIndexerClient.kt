package com.polygon_wallet.polygon_kotlin_sdk.indexer

import com.polygon_wallet.polygon_kotlin_sdk.models.TokenBalance
import com.polygon_wallet.polygon_kotlin_sdk.models.TokenBalancesPage
import com.polygon_wallet.polygon_kotlin_sdk.models.TokenBalancesResult
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceEnvironment
import com.polygon_wallet.polygon_kotlin_sdk.network.SequenceHttpClient
import com.polygon_wallet.polygon_kotlin_sdk.network.arrayOrEmpty
import com.polygon_wallet.polygon_kotlin_sdk.network.boolean
import com.polygon_wallet.polygon_kotlin_sdk.network.long
import com.polygon_wallet.polygon_kotlin_sdk.network.objectOrNull
import com.polygon_wallet.polygon_kotlin_sdk.network.parseJsonObject
import com.polygon_wallet.polygon_kotlin_sdk.network.string
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SequenceIndexerClient(
    private val projectAccessKey: String,
    private val environment: SequenceEnvironment,
    private val transport: SequenceHttpClient = SequenceHttpClient(),
) {
    suspend fun getTokenBalances(
        chainId: String,
        contractAddress: String,
        walletAddress: String,
        includeMetadata: Boolean,
    ): TokenBalancesResult {
        val response = transport.postJson(
            baseUrl = environment.indexerUrlForChainId(chainId),
            path = "/GetTokenBalances",
            body = buildJsonObject {
                putJsonObject("page") {
                    put("page", 0)
                    put("pageSize", 40)
                    put("more", false)
                }
                put("contractAddress", contractAddress)
                put("accountAddress", walletAddress)
                put("includeMetadata", includeMetadata)
            }.toString(),
            headers = defaultHeaders(),
        )

        val root = parseJsonObject(response.body)
        val pageObject = root.objectOrNull("page")
        val page = pageObject?.let {
            TokenBalancesPage(
                page = it.long("page")?.toInt() ?: 0,
                pageSize = it.long("pageSize")?.toInt() ?: 0,
                more = it.boolean("more") == true,
            )
        }

        val balances = root.arrayOrEmpty("balances").mapNotNull { element ->
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

    private fun defaultHeaders(): Map<String, String> = mapOf(
        SequenceEnvironment.accessKeyHeaderName to projectAccessKey,
        "Accept" to "application/json",
    )
}
