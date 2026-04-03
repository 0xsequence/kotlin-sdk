package com.polygon_wallet.polygon_kotlin_sdk.network

import com.polygon_wallet.polygon_kotlin_sdk.api.SequenceApiClient
import com.polygon_wallet.polygon_kotlin_sdk.indexer.SequenceIndexerClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SequenceServiceClientsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun verifySignatureParsesApiResponse() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"isValid":true}""")
                .build(),
        )

        val environment = SequenceEnvironment(
            apiRpcUrl = server.url("/rpc/API/").toString(),
        )
        val client = SequenceApiClient("test-access-key", environment, SequenceHttpClient())

        val response = client.isValidMessageSignature(
            chainId = "80002",
            walletAddress = "0xabc",
            message = "hello",
            signature = "0xsig",
        )
        val request = requireNotNull(server.takeRequest())

        assertEquals("/rpc/API/IsValidMessageSignature", request.target)
        assertEquals("test-access-key", request.headers[SequenceEnvironment.accessKeyHeaderName])
        assertEquals(true, response.isValid)
        assertEquals(200, response.status)
    }

    @Test
    fun getTokenBalancesParsesIndexerResponse() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "page": {"page": 1, "pageSize": 25, "more": false},
                      "balances": [
                        {
                          "contractType": "ERC20",
                          "contractAddress": "0xcontract",
                          "accountAddress": "0xwallet",
                          "tokenID": "0",
                          "balance": "1000",
                          "blockHash": "0xhash",
                          "blockNumber": 12345,
                          "chainId": 137
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val template = server.url("/polygon/rpc/Indexer/").toString().replace("/polygon/", "/{value}/")
        val environment = SequenceEnvironment(
            indexerUrlTemplate = template,
        )
        val client = SequenceIndexerClient("test-access-key", environment, SequenceHttpClient())

        val response = client.getTokenBalances(
            chainId = "137",
            contractAddress = "0xcontract",
            walletAddress = "0xwallet",
            includeMetadata = true,
        )
        val request = requireNotNull(server.takeRequest())

        assertEquals("/polygon/rpc/Indexer/GetTokenBalances", request.target)
        assertEquals("test-access-key", request.headers[SequenceEnvironment.accessKeyHeaderName])
        assertEquals(
            "{\"page\":{\"page\":0,\"pageSize\":40,\"more\":false},\"contractAddress\":\"0xcontract\",\"accountAddress\":\"0xwallet\",\"includeMetadata\":true}",
            requireNotNull(request.body).utf8(),
        )
        assertEquals(1, response.page?.page)
        assertEquals(25, response.page?.pageSize)
        assertEquals(false, response.page?.more)
        assertEquals(1, response.balances.size)
        assertEquals("1000", response.balances.single().balance)
        assertEquals(137L, response.balances.single().chainId)
    }

    @Test
    fun getTokenBalancesTreatsNullPageAndBalancesAsEmpty() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"page":null,"balances":null}""")
                .build(),
        )

        val template = server.url("/polygon/rpc/Indexer/").toString().replace("/polygon/", "/{value}/")
        val environment = SequenceEnvironment(
            indexerUrlTemplate = template,
        )
        val client = SequenceIndexerClient("test-access-key", environment, SequenceHttpClient())

        val response = client.getTokenBalances(
            chainId = "137",
            contractAddress = "0xcontract",
            walletAddress = "0xwallet",
            includeMetadata = true,
        )

        assertEquals(null, response.page)
        assertTrue(response.balances.isEmpty())
    }
}
