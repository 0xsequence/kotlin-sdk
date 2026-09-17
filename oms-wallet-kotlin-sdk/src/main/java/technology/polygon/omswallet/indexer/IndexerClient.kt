package technology.polygon.omswallet.indexer

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
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
import technology.polygon.omswallet.SolanaNetwork
import technology.polygon.omswallet.models.ContractTokenBalance
import technology.polygon.omswallet.models.ContractVerificationStatus
import technology.polygon.omswallet.models.IndexerNetworkType
import technology.polygon.omswallet.models.MetadataOptions
import technology.polygon.omswallet.models.NativeTokenBalance
import technology.polygon.omswallet.models.SolanaBalance
import technology.polygon.omswallet.models.SolanaBalancesResult
import technology.polygon.omswallet.models.SolanaNetworkError
import technology.polygon.omswallet.models.SolanaTokenProgram
import technology.polygon.omswallet.models.SolanaVerificationSource
import technology.polygon.omswallet.models.SolanaVerificationStatus
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
import technology.polygon.omswallet.network.boolean
import technology.polygon.omswallet.network.int
import technology.polygon.omswallet.network.long
import technology.polygon.omswallet.network.objectOrNull
import technology.polygon.omswallet.network.parseJsonObject
import technology.polygon.omswallet.network.string

class IndexerClient private constructor(
    private val publishableKey: String,
    private val environment: OMSWalletEnvironment,
    private val transport: OMSWalletHttpClient,
) {
    /**
     * Gets token and native balances for [walletAddress].
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

        return decodeIndexerResponse(response, operation) { root ->
            TokenBalancesResult(
                status = response.statusCode,
                page = root.objectOrNull("page")?.toTokenBalancesPage(),
                balances = flattenGatewayResults(root.requiredObjectArray("balances")).map { it.toTokenBalance() },
                nativeBalances = flattenGatewayResults(root.requiredObjectArray("nativeBalances")).map { it.toNativeTokenBalance() },
            )
        }
    }

    /**
     * Gets transaction history for [walletAddress].
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

        return decodeIndexerResponse(response, operation) { root ->
            TransactionHistoryResult(
                status = response.statusCode,
                page = root.objectOrNull("page")?.toTokenBalancesPage(),
                transactions = flattenGatewayResults(root.requiredObjectArray("transactions")).map { it.toTransaction() },
            )
        }
    }

    /** Gets native SOL and fungible-token balances for [walletAddress]. */
    suspend fun getSolanaBalances(
        walletAddress: String,
        networks: List<SolanaNetwork> = listOf(SolanaNetwork.Mainnet, SolanaNetwork.Devnet),
        includeMetadata: Boolean = true,
        omitNativeBalances: Boolean? = null,
        mintAddresses: List<String> = emptyList(),
        excludedMintAddresses: List<String> = emptyList(),
    ): SolanaBalancesResult {
        val operation = OMSWalletOperation.IndexerGetSolanaBalances
        val response =
            postIndexerGatewayJson(
                operation = operation,
                baseUrl = environment.solanaIndexerGatewayUrl,
                webRpcHeaderValue = solanaIndexerGatewayWebrpcHeaderValue,
                path = "/GetTokenBalancesDetails",
                body =
                    buildJsonObject {
                        putJsonArray("networks") { networks.forEach { add(it.wireValue) } }
                        putJsonObject("filter") {
                            putJsonArray("accountAddresses") { add(walletAddress) }
                            omitNativeBalances?.let { put("omitNativeBalances", it) }
                            putStringArrayIfNotEmpty("contractWhitelist", mintAddresses)
                            putStringArrayIfNotEmpty("contractBlacklist", excludedMintAddresses)
                        }
                        put("omitMetadata", includeMetadata == false)
                    }.toString(),
            )
        return decodeIndexerResponse(response, operation) { root ->
            SolanaBalancesResult(
                status = response.statusCode,
                balances = root.requiredObjectArray("balances").map { it.toSolanaBalance() },
                errors = root.requiredObjectArray("errors").map { it.toSolanaNetworkError() },
            )
        }
    }

    private suspend fun postIndexerGatewayJson(
        operation: OMSWalletOperation,
        baseUrl: String = environment.indexerGatewayUrl,
        webRpcHeaderValue: String = indexerGatewayWebrpcHeaderValue,
        path: String,
        body: String,
    ): OMSWalletHttpResponse {
        val response =
            try {
                transport.postJsonWithStatus(
                    baseUrl = baseUrl,
                    path = path,
                    body = body,
                    headers = defaultGatewayHeaders(webRpcHeaderValue),
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

    private fun <T> decodeIndexerResponse(
        response: OMSWalletHttpResponse,
        operation: OMSWalletOperation,
        decode: (JsonObject) -> T,
    ): T =
        try {
            decode(parseIndexerJsonObject(response, operation))
        } catch (throwable: CancellationException) {
            throw throwable
        } catch (throwable: OMSWalletResponseException) {
            throw throwable
        } catch (throwable: Throwable) {
            val message = "Invalid response from ${operation.id}"
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

    private fun defaultGatewayHeaders(webRpcHeaderValue: String): Map<String, String> =
        mapOf(
            "Api-Key" to publishableKey,
            "Accept" to "application/json",
            "Webrpc" to webRpcHeaderValue,
        )

    private fun JsonObject.toSolanaBalance(): SolanaBalance {
        val network = requiredSolanaNetwork("network")
        val accountAddress = requiredString("accountAddress")
        val name = requiredString("name")
        val symbol = requiredString("symbol")
        val decimals = requiredInt("decimals")
        val balance = requiredString("balance")
        val formattedBalance = requiredString("formattedBalance")
        val imageUrl = optionalNonEmptyString("imageUrl")
        val metadataUri = optionalNonEmptyString("metadataUri")
        val verificationStatus = requiredVerificationStatus("verificationStatus")
        val verificationSource = requiredVerificationSource("verificationSource")
        val priceUSD = optionalString("priceUSD")
        val balanceUSD = optionalString("balanceUSD")
        return when (requiredString("assetType")) {
            "native" -> {
                require(this["tokenProgram"] == null || this["tokenProgram"] === JsonNull)
                require(this["mintAddress"] == null || this["mintAddress"] === JsonNull)
                SolanaBalance.Native(
                    network,
                    accountAddress,
                    name,
                    symbol,
                    decimals,
                    balance,
                    formattedBalance,
                    imageUrl,
                    metadataUri,
                    verificationStatus,
                    verificationSource,
                    priceUSD,
                    balanceUSD,
                )
            }

            "fungible-token" -> {
                SolanaBalance.FungibleToken(
                    network,
                    accountAddress,
                    requiredTokenProgram("tokenProgram"),
                    requiredString("mintAddress"),
                    name,
                    symbol,
                    decimals,
                    balance,
                    formattedBalance,
                    imageUrl,
                    metadataUri,
                    verificationStatus,
                    verificationSource,
                    priceUSD,
                    balanceUSD,
                )
            }

            else -> {
                throw IllegalArgumentException("Invalid assetType")
            }
        }
    }

    private fun JsonObject.toSolanaNetworkError(): SolanaNetworkError =
        SolanaNetworkError(requiredSolanaNetwork("network"), requiredString("reason"))

    private fun JsonObject.requiredSolanaNetwork(name: String): SolanaNetwork =
        when (requiredString(name)) {
            SolanaNetwork.Mainnet.wireValue -> SolanaNetwork.Mainnet
            SolanaNetwork.Devnet.wireValue -> SolanaNetwork.Devnet
            else -> throw IllegalArgumentException("Invalid $name")
        }

    private fun JsonObject.requiredTokenProgram(name: String): SolanaTokenProgram =
        when (requiredString(name)) {
            "spl-token" -> SolanaTokenProgram.SplToken
            "token-2022" -> SolanaTokenProgram.Token2022
            else -> throw IllegalArgumentException("Invalid $name")
        }

    private fun JsonObject.requiredVerificationStatus(name: String): SolanaVerificationStatus =
        when (requiredString(name)) {
            "verified" -> SolanaVerificationStatus.Verified
            "unverified" -> SolanaVerificationStatus.Unverified
            "unknown" -> SolanaVerificationStatus.Unknown
            else -> throw IllegalArgumentException("Invalid $name")
        }

    private fun JsonObject.requiredVerificationSource(name: String): SolanaVerificationSource =
        when (requiredString(name)) {
            "jupiter" -> SolanaVerificationSource.Jupiter
            "solflare-utl" -> SolanaVerificationSource.SolflareUtl
            "none" -> SolanaVerificationSource.None
            else -> throw IllegalArgumentException("Invalid $name")
        }

    private fun JsonObject.optionalNonEmptyString(name: String): String? = optionalString(name)?.takeIf(String::isNotEmpty)

    private fun JsonObject.toTokenBalancesPage(): TokenBalancesPage =
        TokenBalancesPage(
            page = requiredInt("page"),
            pageSize = requiredInt("pageSize"),
            more = requiredBoolean("more"),
        )

    private fun JsonObject.toNativeTokenBalance(): NativeTokenBalance =
        NativeTokenBalance(
            accountAddress = requiredString("accountAddress"),
            name = requiredString("name"),
            symbol = requiredString("symbol"),
            balance = optionalString("balance") ?: requiredString("balanceWei"),
            balanceUSD = optionalString("balanceUSD"),
            priceUSD = optionalString("priceUSD"),
            priceUpdatedAt = optionalString("priceUpdatedAt"),
            chainId = requiredLong("chainId"),
        )

    private fun JsonObject.toTokenBalance(): ContractTokenBalance =
        ContractTokenBalance(
            contractType = requiredString("contractType"),
            contractAddress = requiredString("contractAddress"),
            accountAddress = requiredString("accountAddress"),
            tokenId = optionalString("tokenId") ?: requiredString("tokenID"),
            balance = requiredString("balance"),
            balanceUSD = optionalString("balanceUSD"),
            priceUSD = optionalString("priceUSD"),
            priceUpdatedAt = optionalString("priceUpdatedAt"),
            blockHash = requiredString("blockHash"),
            blockNumber = requiredLong("blockNumber"),
            chainId = requiredLong("chainId"),
            uniqueCollectibles = optionalString("uniqueCollectibles"),
            isSummary = optionalBoolean("isSummary"),
            contractInfo = objectOrNull("contractInfo")?.toTokenContractInfo(),
            tokenMetadata = objectOrNull("tokenMetadata")?.toTokenMetadata(),
        )

    private fun JsonObject.toTransaction(): Transaction =
        Transaction(
            txnHash = requiredString("txnHash"),
            blockNumber = requiredLong("blockNumber"),
            blockHash = requiredString("blockHash"),
            chainId = requiredLong("chainId"),
            metaTxnId = optionalString("metaTxnId") ?: optionalString("metaTxnID"),
            transfers = requiredObjectArray("transfers").map { it.toTransactionTransfer() },
            timestamp = requiredString("timestamp"),
        )

    private fun JsonObject.toTransactionTransfer(): TransactionTransfer =
        TransactionTransfer(
            transferType = requiredString("transferType"),
            contractAddress = requiredString("contractAddress"),
            contractType = requiredString("contractType"),
            from = requiredString("from"),
            to = requiredString("to"),
            tokenIds = stringArrayOrNull("tokenIds") ?: stringArrayOrNull("tokenIDs"),
            amounts = requiredStringArray("amounts"),
            logIndex = requiredLong("logIndex"),
            amountsUSD = stringArrayOrNull("amountsUSD"),
            pricesUSD = stringArrayOrNull("pricesUSD"),
            contractInfo = objectOrNull("contractInfo")?.toTokenContractInfo(),
            tokenMetadata = objectOrNull("tokenMetadata")?.toTokenMetadataRecord(),
        )

    private fun JsonObject.toTokenContractInfo(): TokenContractInfo =
        TokenContractInfo(
            chainId = requiredLong("chainId"),
            address = requiredString("address"),
            source = requiredString("source"),
            name = requiredString("name"),
            type = requiredString("type"),
            symbol = requiredString("symbol"),
            decimals = optionalInt("decimals"),
            logoURI = optionalString("logoURI"),
            deployed = requiredBoolean("deployed"),
            bytecodeHash = requiredString("bytecodeHash"),
            extensions = requiredObject("extensions").toMap(),
            updatedAt = requiredString("updatedAt"),
            queuedAt = optionalString("queuedAt"),
            status = requiredString("status"),
        )

    private fun JsonObject.toTokenMetadata(): TokenMetadata =
        TokenMetadata(
            chainId = optionalLong("chainId"),
            contractAddress = optionalString("contractAddress"),
            tokenId = optionalString("tokenId") ?: requiredString("tokenID"),
            source = requiredString("source"),
            name = requiredString("name"),
            description = optionalString("description"),
            image = optionalString("image"),
            video = optionalString("video"),
            audio = optionalString("audio"),
            properties = objectOrNull("properties")?.toMap(),
            attributes =
                (this["attributes"] as? JsonArray)
                    ?.map { attribute ->
                        (attribute as? JsonObject)?.toMap()
                            ?: throw IllegalArgumentException("Invalid token metadata attribute")
                    }
                    ?: throw IllegalArgumentException("Missing or invalid attributes"),
            imageData = optionalString("image_data"),
            externalUrl = optionalString("external_url"),
            backgroundColor = optionalString("background_color"),
            animationUrl = optionalString("animation_url"),
            decimals = optionalInt("decimals"),
            updatedAt = optionalString("updatedAt"),
            assets = optionalObjectArray("assets")?.map { it.toTokenMetadataAsset() },
            status = requiredString("status"),
            queuedAt = optionalString("queuedAt"),
            lastFetched = optionalString("lastFetched"),
        )

    private fun JsonObject.toTokenMetadataAsset(): TokenMetadataAsset =
        TokenMetadataAsset(
            id = optionalLong("id"),
            collectionId = optionalLong("collectionId"),
            tokenId = optionalString("tokenId") ?: optionalString("tokenID"),
            url = optionalString("url"),
            metadataField = optionalString("metadataField"),
            name = optionalString("name"),
            filesize = optionalLong("filesize"),
            mimeType = optionalString("mimeType"),
            width = optionalInt("width"),
            height = optionalInt("height"),
            updatedAt = optionalString("updatedAt"),
        )

    private fun JsonObject.toMap(): Map<String, JsonElement> = entries.associate { it.key to it.value }

    private fun JsonObject.toTokenMetadataRecord(): Map<String, TokenMetadata> =
        entries
            .associate { (tokenId, metadata) ->
                val metadataObject =
                    metadata as? JsonObject
                        ?: throw IllegalArgumentException("Invalid token metadata for $tokenId")
                tokenId to metadataObject.toTokenMetadata()
            }.toMap()

    private fun JsonObject.stringArrayOrNull(name: String): List<String>? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val values = value as? JsonArray ?: throw IllegalArgumentException("Invalid $name")
        return values.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw IllegalArgumentException("Invalid $name entry")
        }
    }

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun JsonObject.optionalString(name: String): String? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $name")
        if (!primitive.isString) {
            throw IllegalArgumentException("Invalid $name")
        }
        return primitive.contentOrNull
    }

    private fun JsonObject.requiredLong(name: String): Long =
        optionalLong(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun JsonObject.optionalLong(name: String): Long? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $name")
        if (primitive.isString) {
            throw IllegalArgumentException("Invalid $name")
        }
        return primitive.longOrNull ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun JsonObject.requiredInt(name: String): Int = optionalInt(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun JsonObject.optionalInt(name: String): Int? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $name")
        if (primitive.isString) {
            throw IllegalArgumentException("Invalid $name")
        }
        return primitive.intOrNull ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean =
        optionalBoolean(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun JsonObject.optionalBoolean(name: String): Boolean? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $name")
        if (primitive.isString) {
            throw IllegalArgumentException("Invalid $name")
        }
        return primitive.booleanOrNull ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        objectOrNull(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun JsonObject.requiredObjectArray(name: String): List<JsonObject> {
        val values = this[name] as? JsonArray ?: throw IllegalArgumentException("Missing or invalid $name")
        return values.map { value ->
            value as? JsonObject ?: throw IllegalArgumentException("Invalid $name entry")
        }
    }

    private fun JsonObject.optionalObjectArray(name: String): List<JsonObject>? {
        val value = this[name] ?: return null
        if (value === JsonNull) return null
        val values = value as? JsonArray ?: throw IllegalArgumentException("Invalid $name")
        return values.map { item ->
            item as? JsonObject ?: throw IllegalArgumentException("Invalid $name entry")
        }
    }

    private fun JsonObject.requiredStringArray(name: String): List<String> =
        stringArrayOrNull(name) ?: throw IllegalArgumentException("Missing or invalid $name")

    private fun flattenGatewayResults(groups: List<JsonObject>): List<JsonObject> =
        groups.flatMap { group ->
            group.requiredObjectArray("results")
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

    companion object {
        @JvmSynthetic
        internal fun create(
            publishableKey: String,
            environment: OMSWalletEnvironment,
            transport: OMSWalletHttpClient = OMSWalletHttpClient(),
        ): IndexerClient = IndexerClient(publishableKey, environment, transport)

        private const val indexerGatewayWebrpcHeaderValue: String =
            "webrpc@v0.31.2;gen-typescript@v0.23.1;sequence-indexer@v0.4.0"
        private const val solanaIndexerGatewayWebrpcHeaderValue: String =
            "webrpc@v0.31.2;gen-kotlin@v0.3.2;solana-indexer-gateway@v1"
    }
}
