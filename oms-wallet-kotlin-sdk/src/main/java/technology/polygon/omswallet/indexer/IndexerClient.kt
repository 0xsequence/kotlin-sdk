package technology.polygon.omswallet.indexer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import technology.polygon.omswallet.Network
import technology.polygon.omswallet.OMSWalletErrorCode
import technology.polygon.omswallet.OMSWalletOperation
import technology.polygon.omswallet.OMSWalletRequestException
import technology.polygon.omswallet.OMSWalletResponseException
import technology.polygon.omswallet.OMSWalletUpstreamError
import technology.polygon.omswallet.OMSWalletUpstreamService
import technology.polygon.omswallet.models.ContractVerificationStatus
import technology.polygon.omswallet.models.IndexerNetworkType
import technology.polygon.omswallet.models.MetadataOptions
import technology.polygon.omswallet.models.TokenBalance
import technology.polygon.omswallet.models.TokenBalancesPage
import technology.polygon.omswallet.models.TokenBalancesPageRequest
import technology.polygon.omswallet.models.TokenBalancesResult
import technology.polygon.omswallet.models.TokenContractInfo
import technology.polygon.omswallet.models.TokenMetadata
import technology.polygon.omswallet.models.TokenMetadataAsset
import technology.polygon.omswallet.models.Transaction
import technology.polygon.omswallet.models.TransactionHistoryResult
import technology.polygon.omswallet.models.TransactionTransfer
import technology.polygon.omswallet.network.OMSWalletEnvironment
import technology.polygon.omswallet.network.OMSWalletHttpClient
import technology.polygon.omswallet.network.OMSWalletHttpResponse
import technology.polygon.omswallet.network.OMSWalletJson
import technology.polygon.omswallet.network.arrayOrEmpty
import technology.polygon.omswallet.network.boolean
import technology.polygon.omswallet.network.int
import technology.polygon.omswallet.network.long
import technology.polygon.omswallet.network.objectOrNull
import technology.polygon.omswallet.network.parseJsonObject
import technology.polygon.omswallet.network.string

class IndexerClient internal constructor(
    private val publishableKey: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient = OMSWalletHttpClient(),
) {
    /**
     * Gets token and native balances for [walletAddress] through IndexerGateway.
     */
    suspend fun getBalances(
        walletAddress: String,
        networks: List<Network> = emptyList(),
        networkType: IndexerNetworkType = IndexerNetworkType.MAINNETS,
        contractAddresses: List<String> = emptyList(),
        includeMetadata: Boolean = true,
        omitPrices: Boolean? = null,
        tokenIds: List<String> = emptyList(),
        contractStatus: ContractVerificationStatus? = null,
        page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
    ): TokenBalancesResult {
        val operation = OMSWalletOperation.IndexerGetBalances
        val response =
            postIndexerGatewayJson(
                operation = operation,
                path = "/GetTokenBalancesDetails",
                body =
                    buildJsonObject {
                        if (networks.isNotEmpty()) {
                            putJsonArray("chainIds") {
                                networks.forEach { network -> add(network.id) }
                            }
                        } else {
                            put("networkType", networkType.wireValue)
                        }
                        putJsonObject("filter") {
                            putJsonArray("accountAddresses") {
                                add(walletAddress)
                            }
                            putStringArrayIfNotEmpty("contractWhitelist", contractAddresses)
                            contractStatus?.let { put("contractStatus", it.wireValue) }
                            put("omitNativeBalances", false)
                            omitPrices?.let { put("omitPrices", it) }
                            putStringArrayIfNotEmpty("tokenIDs", tokenIds)
                        }
                        put("omitMetadata", includeMetadata == false)
                        putJsonObject("page") {
                            put("page", page.page)
                            put("pageSize", page.pageSize)
                        }
                    }.toString(),
            )

        val root = parseIndexerJsonObject(response, operation)
        return TokenBalancesResult(
            status = response.statusCode,
            page = root.objectOrNull("page")?.toTokenBalancesPage(),
            balances = flattenGatewayResults(root.arrayOrEmpty("balances")).map { it.toTokenBalance() },
            nativeBalances = flattenGatewayResults(root.arrayOrEmpty("nativeBalances")).map { it.toNativeTokenBalance() },
        )
    }

    /**
     * Gets transaction history for [walletAddress] through IndexerGateway.
     */
    suspend fun getTransactionHistory(
        walletAddress: String,
        networks: List<Network> = emptyList(),
        networkType: IndexerNetworkType = IndexerNetworkType.MAINNETS,
        contractAddresses: List<String> = emptyList(),
        transactionHashes: List<String> = emptyList(),
        metaTransactionIds: List<String> = emptyList(),
        fromBlock: Long? = null,
        toBlock: Long? = null,
        tokenId: String? = null,
        includeMetadata: Boolean = true,
        omitPrices: Boolean? = null,
        metadataOptions: MetadataOptions? = null,
        page: TokenBalancesPageRequest = TokenBalancesPageRequest(),
    ): TransactionHistoryResult {
        val operation = OMSWalletOperation.IndexerGetTransactionHistory
        val response =
            postIndexerGatewayJson(
                operation = operation,
                path = "/GetTransactionHistory",
                body =
                    buildJsonObject {
                        if (networks.isNotEmpty()) {
                            putJsonArray("chainIds") {
                                networks.forEach { network -> add(network.id) }
                            }
                        } else {
                            put("networkType", networkType.wireValue)
                        }
                        putJsonObject("filter") {
                            putJsonArray("accountAddresses") {
                                add(walletAddress)
                            }
                            putStringArrayIfNotEmpty("contractAddresses", contractAddresses)
                            putStringArrayIfNotEmpty("transactionHashes", transactionHashes)
                            putStringArrayIfNotEmpty("metaTransactionIDs", metaTransactionIds)
                            fromBlock?.let { put("fromBlock", it) }
                            toBlock?.let { put("toBlock", it) }
                            tokenId?.let { put("tokenID", it) }
                            omitPrices?.let { put("omitPrices", it) }
                        }
                        put("includeMetadata", includeMetadata)
                        metadataOptions?.let { options ->
                            putJsonObject("metadataOptions") {
                                options.verifiedOnly?.let { put("verifiedOnly", it) }
                                options.unverifiedOnly?.let { put("unverifiedOnly", it) }
                                putStringArrayIfNotEmpty("includeContracts", options.includeContracts)
                            }
                        }
                        putJsonObject("page") {
                            put("page", page.page)
                            put("pageSize", page.pageSize)
                        }
                    }.toString(),
            )

        val root = parseIndexerJsonObject(response, operation)
        return TransactionHistoryResult(
            status = response.statusCode,
            page = root.objectOrNull("page")?.toTokenBalancesPage(),
            transactions = flattenGatewayResults(root.arrayOrEmpty("transactions")).map { it.toTransaction() },
        )
    }

    private suspend fun postIndexerGatewayJson(
        operation: OMSWalletOperation,
        path: String,
        body: String,
    ): OMSWalletHttpResponse {
        val response =
            try {
                transport.postJsonWithStatus(
                    baseUrl = environment.indexerGatewayUrl,
                    path = path,
                    body = body,
                    headers = defaultGatewayHeaders(),
                )
            } catch (throwable: CancellationException) {
                throw throwable
            } catch (throwable: Throwable) {
                throw OMSWalletRequestException(
                    operation = operation,
                    upstreamError = throwable.toIndexerUpstreamError(),
                    message = throwable.message ?: "${operation.id} request failed",
                    cause = throwable,
                )
            }

        if (response.statusCode !in 200..299) {
            val parsed = parseJsonOrText(response.body)
            val message = indexerResponseMessage(parsed, operation, response.statusCode)
            throw OMSWalletRequestException(
                code = OMSWalletErrorCode.HttpError,
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
        response: OMSWalletHttpResponse,
        operation: OMSWalletOperation,
    ): JsonObject =
        try {
            parseJsonObject(response.body)
        } catch (throwable: Throwable) {
            val message = "Invalid JSON response from ${operation.id}"
            throw OMSWalletResponseException(
                operation = operation,
                status = response.statusCode,
                upstreamError =
                    OMSWalletUpstreamError(
                        service = OMSWalletUpstreamService.Indexer,
                        message = message,
                        status = response.statusCode,
                    ),
                message = message,
                cause = throwable,
            )
        }

    private fun defaultGatewayHeaders(): Map<String, String> =
        mapOf(
            "Api-Key" to publishableKey,
            "Accept" to "application/json",
            "Webrpc" to indexerGatewayWebrpcHeaderValue,
        )

    private fun JsonObject.toTokenBalancesPage(): TokenBalancesPage =
        TokenBalancesPage(
            page = long("page")?.toInt() ?: 0,
            pageSize = long("pageSize")?.toInt() ?: 0,
            more = boolean("more") == true,
        )

    private fun JsonObject.toNativeTokenBalance(): TokenBalance =
        TokenBalance(
            contractType = "NATIVE",
            contractAddress = null,
            accountAddress = string("accountAddress"),
            tokenId = null,
            name = string("name"),
            symbol = string("symbol"),
            balance = string("balance") ?: string("balanceWei"),
            balanceUSD = string("balanceUSD"),
            priceUSD = string("priceUSD"),
            priceUpdatedAt = string("priceUpdatedAt"),
            blockHash = null,
            blockNumber = null,
            chainId = long("chainId"),
        )

    private fun JsonObject.toTokenBalance(): TokenBalance =
        TokenBalance(
            contractType = string("contractType"),
            contractAddress = string("contractAddress"),
            accountAddress = string("accountAddress"),
            tokenId = string("tokenId") ?: string("tokenID"),
            name = string("name"),
            symbol = string("symbol"),
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

    private fun JsonObject.toTransaction(): Transaction =
        Transaction(
            txnHash = string("txnHash"),
            blockNumber = long("blockNumber"),
            blockHash = string("blockHash"),
            chainId = long("chainId"),
            metaTxnId = string("metaTxnId") ?: string("metaTxnID"),
            transfers =
                (this["transfers"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { it.toTransactionTransfer() },
            timestamp = string("timestamp"),
        )

    private fun JsonObject.toTransactionTransfer(): TransactionTransfer =
        TransactionTransfer(
            transferType = string("transferType"),
            contractAddress = string("contractAddress"),
            contractType = string("contractType"),
            from = string("from"),
            to = string("to"),
            tokenIds = stringArrayOrNull("tokenIds") ?: stringArrayOrNull("tokenIDs"),
            amounts = stringArrayOrNull("amounts"),
            logIndex = long("logIndex"),
            amountsUSD = stringArrayOrNull("amountsUSD"),
            pricesUSD = stringArrayOrNull("pricesUSD"),
            contractInfo = objectOrNull("contractInfo")?.toTokenContractInfo(),
            tokenMetadata = objectOrNull("tokenMetadata")?.toTokenMetadataRecord(),
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

    private fun JsonObject.toTokenMetadataRecord(): Map<String, TokenMetadata> =
        entries
            .mapNotNull { (tokenId, metadata) ->
                val metadataObject = metadata as? JsonObject ?: return@mapNotNull null
                tokenId to metadataObject.toTokenMetadata()
            }.toMap()

    private fun JsonObject.stringArrayOrNull(name: String): List<String>? =
        (this[name] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    private fun flattenGatewayResults(groups: List<JsonElement>): List<JsonObject> =
        groups.flatMap { group ->
            (group as? JsonObject)
                ?.arrayOrEmpty("results")
                ?.mapNotNull { it as? JsonObject }
                ?: emptyList()
        }

    private fun JsonObjectBuilder.putStringArrayIfNotEmpty(
        name: String,
        values: List<String>,
    ) {
        if (values.isEmpty()) {
            return
        }
        putJsonArray(name) {
            values.forEach { value -> add(value) }
        }
    }

    private fun parseJsonOrText(body: String): Any = runCatching { OMSWalletJson.json.parseToJsonElement(body) }.getOrElse { body }

    private fun indexerResponseMessage(
        payload: Any,
        operation: OMSWalletOperation,
        status: Int,
    ): String =
        when (payload) {
            is JsonObject -> payload.string("message") ?: payload.string("msg")
            else -> null
        } ?: "${operation.id} failed with HTTP $status"

    private fun Any.toIndexerUpstreamError(
        status: Int,
        fallbackMessage: String,
    ): OMSWalletUpstreamError =
        when (this) {
            is JsonObject -> {
                OMSWalletUpstreamError(
                    service = OMSWalletUpstreamService.Indexer,
                    name = string("name") ?: string("error"),
                    code = stringOrNumber("code"),
                    message = string("message") ?: string("msg") ?: fallbackMessage,
                    status = status,
                )
            }

            else -> {
                OMSWalletUpstreamError(
                    service = OMSWalletUpstreamService.Indexer,
                    message = fallbackMessage,
                    status = status,
                )
            }
        }

    private fun Throwable.toIndexerUpstreamError(): OMSWalletUpstreamError =
        OMSWalletUpstreamError(
            service = OMSWalletUpstreamService.Indexer,
            name = javaClass.simpleName,
            message = message,
            status = null,
        )

    private fun JsonObject.stringOrNumber(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val indexerGatewayWebrpcHeaderValue: String =
            "webrpc@v0.31.2;gen-typescript@v0.23.1;sequence-indexer@v0.4.0"
    }
}
