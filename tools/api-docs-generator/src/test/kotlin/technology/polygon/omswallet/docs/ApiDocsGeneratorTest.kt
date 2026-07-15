package technology.polygon.omswallet.docs

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiDocsGeneratorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `extracts public source signatures and kdoc without bodies`() {
        val root = temporaryFolder.newFolder("source")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            /** Creates a client. */
            class Client private constructor(
                val id: String,
                internalValue: String,
            ) {
                /** Starts work. */
                suspend fun start(value: String = "default") {
                    error(value)
                }

                protected fun subclassOnly(): Unit = Unit
                internal fun hidden(): Unit = Unit
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            assertEquals(listOf("technology.polygon.omswallet.Client"), symbols.keys.toList())
            val client = symbols.getValue("technology.polygon.omswallet.Client")
            assertEquals("class Client", client.declarations.first().signature)
            assertEquals("Creates a client.", client.declarations.first().summary)
            assertEquals("val id: String", client.declarations.single { "val id" in it.signature }.signature)
            assertTrue(client.declarations.single { "val id" in it.signature }.owned)
            assertContains(client.declarations.single { "start" in it.signature }.signature, "value: String = \"default\"")
            assertEquals("Starts work.", client.declarations.single { "start" in it.signature }.summary)
            assertContains(
                client.declarations
                    .single { "start" in it.signature }
                    .kdoc
                    .orEmpty(),
                "/**\n * Starts work.\n */",
            )
            assertFalse(client.declarations.any { "error(value)" in it.signature })
            assertFalse(client.declarations.any { "subclassOnly" in it.signature })
            assertFalse(client.declarations.any { "internalValue" in it.signature })
            assertFalse(client.declarations.any { "DefaultConstructorMarker" in it.signature })
        }
    }

    @Test
    fun `rejects inferred public types`() {
        val root = temporaryFolder.newFolder("inferred")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet
            val inferred = "value"
            """.trimIndent(),
        )

        val failure =
            assertFailsWith<ApiDocsException> {
                KotlinPsiApiExtractor().use { it.extract(root) }
            }
        assertContains(failure.message.orEmpty(), "Inferred public property type")
    }

    @Test
    fun `rejects inferred expression body return types`() {
        val root = temporaryFolder.newFolder("inferred-function")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet
            fun inferred() = "value"
            """.trimIndent(),
        )

        val failure =
            assertFailsWith<ApiDocsException> {
                KotlinPsiApiExtractor().use { it.extract(root) }
            }
        assertContains(failure.message.orEmpty(), "Inferred public return type")
    }

    @Test
    fun `rejects Kotlin parse errors`() {
        val root = temporaryFolder.newFolder("parse-error")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet
            fun broken(: String): Unit = Unit
            """.trimIndent(),
        )

        val failure =
            assertFailsWith<ApiDocsException> {
                KotlinPsiApiExtractor().use { it.extract(root) }
            }
        assertContains(failure.message.orEmpty(), "Kotlin parse errors")
        assertContains(failure.message.orEmpty(), "Api.kt")
    }

    @Test
    fun `scans Java and Kotlin source layouts`() {
        val root = temporaryFolder.newFolder("source-layouts")
        root.resolve("java/technology/polygon/omswallet/JavaApi.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package technology.polygon.omswallet
                class JavaApi
                """.trimIndent(),
            )
        }
        root.resolve("kotlin/technology/polygon/omswallet/KotlinApi.kt").apply {
            parentFile.mkdirs()
            writeText(
                """
                package technology.polygon.omswallet
                class KotlinApi
                """.trimIndent(),
            )
        }

        KotlinPsiApiExtractor().use { extractor ->
            assertEquals(
                listOf(
                    "technology.polygon.omswallet.JavaApi",
                    "technology.polygon.omswallet.KotlinApi",
                ),
                extractor.extract(root).keys.toList(),
            )
        }
    }

    @Test
    fun `preserves const initializers and enum constructor arguments`() {
        val root = temporaryFolder.newFolder("source-contract-values")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            const val API_VERSION: String = "2026-01"

            enum class Environment(val wireValue: String) {
                Production("production"),
                Sandbox(
                    "sandbox",
                ),
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            assertEquals(
                "const val API_VERSION: String = \"2026-01\"",
                symbols
                    .getValue("technology.polygon.omswallet#API_VERSION")
                    .declarations
                    .single()
                    .signature,
            )
            val enumDeclaration = symbols.getValue("technology.polygon.omswallet.Environment").declarations.single()
            assertEquals("enum class Environment(val wireValue: String)", enumDeclaration.signature)
            assertContains(enumDeclaration.enumEntries.single { "Production" in it }, "Production(\"production\")")
            assertContains(enumDeclaration.enumEntries.single { "Sandbox" in it }, "Sandbox(\n")
            assertContains(enumDeclaration.enumEntries.single { "Sandbox" in it }, "\"sandbox\"")
            assertFalse(enumDeclaration.enumEntries.any { ",," in it })
        }
    }

    @Test
    fun `rejects enum entries with class bodies`() {
        val root = temporaryFolder.newFolder("enum-body")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            enum class Operation {
                Custom {
                    override fun run(): Unit = Unit
                };

                abstract fun run(): Unit
            }
            """.trimIndent(),
        )

        val failure =
            assertFailsWith<ApiDocsException> {
                KotlinPsiApiExtractor().use { it.extract(root) }
            }
        assertContains(failure.message.orEmpty(), "Enum entry bodies are unsupported")
    }

    @Test
    fun `removes super constructor arguments but preserves declared supertypes`() {
        val root = temporaryFolder.newFolder("super-constructor")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            open class Parent<T>(value: T)
            interface Marker

            class Child(value: String) : Parent<String>(
                value = value,
            ), Marker
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val child =
                extractor
                    .extract(root)
                    .getValue("technology.polygon.omswallet.Child")
                    .declarations
                    .single()

            assertEquals("class Child(value: String) : Parent<String>, Marker", child.signature)
        }
    }

    @Test
    fun `splits only wallet and indexer client members`() {
        val root = temporaryFolder.newFolder("clients")
        root.resolve("WalletClient.kt").writeText(
            """
            package technology.polygon.omswallet.wallet

            class WalletClient {
                fun signOut() {}
            }

            class SessionState {
                val active: Boolean = false
            }
            """.trimIndent(),
        )
        root.resolve("IndexerClient.kt").writeText(
            """
            package technology.polygon.omswallet.indexer

            class IndexerClient {
                fun getTokenBalances(): Unit {}
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            assertTrue("technology.polygon.omswallet.wallet.WalletClient#signOut" in symbols)
            assertTrue("technology.polygon.omswallet.indexer.IndexerClient#getTokenBalances" in symbols)
            assertFalse("technology.polygon.omswallet.wallet.SessionState#active" in symbols)
            assertContains(
                symbols
                    .getValue("technology.polygon.omswallet.wallet.SessionState")
                    .declarations
                    .single { "active" in it.signature }
                    .signature,
                "val active: Boolean",
            )
        }
    }

    @Test
    fun `renders compound members inside the owner without promoting child summaries`() {
        val symbols =
            mapOf(
                "pkg.Client" to
                    ApiSymbol(
                        "pkg.Client",
                        mutableListOf(
                            ApiDeclaration("class Client", summary = null),
                            ApiDeclaration(
                                signature = "val option: String",
                                summary = "Child summary.",
                                kdoc = "/** Child summary. */",
                                owned = true,
                            ),
                        ),
                    ),
            )
        val groups =
            requiredGroups.mapIndexed { index, name ->
                PresentationGroup(name, if (index == 0) listOf("pkg.Client" to "Client") else emptyList())
            }

        val rendered = renderApiDocs(symbols, groups)

        assertContains(rendered, "$GENERATED_MARKER\n\n# Kotlin API reference")
        assertContains(rendered, "### `Client`\n\n```kotlin\nclass Client {")
        assertContains(rendered, "    /** Child summary. */\n    val option: String\n}")
        assertFalse("### `Client`\n\nChild summary." in rendered)
    }

    @Test
    fun `requires one shared summary for grouped overloads`() {
        val groups =
            requiredGroups.mapIndexed { index, name ->
                PresentationGroup(name, if (index == 0) listOf("pkg.Client#send" to "Client.send") else emptyList())
            }

        val matching =
            mapOf(
                "pkg.Client#send" to
                    ApiSymbol(
                        "pkg.Client#send",
                        mutableListOf(
                            ApiDeclaration("fun send(value: String)", "Sends a value."),
                            ApiDeclaration("fun send(value: Int)", "Sends a value."),
                        ),
                    ),
            )
        val rendered = renderApiDocs(matching, groups)
        assertEquals(1, Regex("Sends a value\\.").findAll(rendered).count())

        listOf(
            listOf("Sends text.", "Sends a value."),
            listOf("Sends a value.", null),
        ).forEach { summaries ->
            val inconsistent =
                mapOf(
                    "pkg.Client#send" to
                        ApiSymbol(
                            "pkg.Client#send",
                            summaries
                                .mapIndexed { index, summary -> ApiDeclaration("fun send(value: Type$index)", summary) }
                                .toMutableList(),
                        ),
                )
            val failure = assertFailsWith<ApiDocsException> { renderApiDocs(inconsistent, groups) }
            assertContains(failure.message.orEmpty(), "inconsistent summaries")
        }
    }

    @Test
    fun `preserves companion object members as type-level declarations`() {
        val root = temporaryFolder.newFolder("companion-members")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            class Network private constructor(val id: String) {
                companion object {
                    /** Ethereum mainnet. */
                    val MAINNET: Network = Network("1")
                    fun fromId(id: String): Network? = null
                }

                val displayName: String = id
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            val groups =
                requiredGroups.mapIndexed { index, name ->
                    PresentationGroup(
                        name,
                        if (index == 0) listOf("technology.polygon.omswallet.Network" to "Network") else emptyList(),
                    )
                }

            val rendered = renderApiDocs(symbols, groups)

            assertContains(rendered, "    companion object {")
            assertContains(rendered, "        val MAINNET: Network")
            assertContains(rendered, "        fun fromId(id: String): Network?")
            assertContains(rendered, "    val displayName: String")
            assertFalse("class Network {\n    val MAINNET" in rendered)
        }
    }

    @Test
    fun `preserves non-public setters and named companion supertypes`() {
        val root = temporaryFolder.newFolder("property-and-companion-signatures")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            interface Factory

            class Client {
                var state: String = "ready"
                    private set

                companion object Named : Factory {
                    val default: Client = Client()
                }
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            val groups =
                requiredGroups.mapIndexed { index, name ->
                    PresentationGroup(
                        name,
                        if (index == 0) {
                            listOf(
                                "technology.polygon.omswallet.Factory" to "Factory",
                                "technology.polygon.omswallet.Client" to "Client",
                            )
                        } else {
                            emptyList()
                        },
                    )
                }

            val rendered = renderApiDocs(symbols, groups)

            assertContains(rendered, "    var state: String\n        private set")
            assertContains(rendered, "    companion object Named : Factory {")
            assertContains(rendered, "        val default: Client")
        }
    }

    @Test
    fun `normalizes compound member indentation and renders kdoc links as code`() {
        val root = temporaryFolder.newFolder("compound-indentation")
        root.resolve("Api.kt").writeText(
            """
            package technology.polygon.omswallet

            /** Uses [Network]. */
            class Client private constructor() {
                /**
                 * Creates a [Client] using [technology.polygon.omswallet.utils.parseUnits].
                 *
                 * Later implementation details are not part of the summary.
                 *
                 * @param value ignored documentation detail
                 */
                constructor(
                    value: String,
                ) : this()
            }
            """.trimIndent(),
        )

        KotlinPsiApiExtractor().use { extractor ->
            val symbols = extractor.extract(root)
            val groups =
                requiredGroups.mapIndexed { index, name ->
                    PresentationGroup(name, if (index == 0) listOf("technology.polygon.omswallet.Client" to "Client") else emptyList())
                }

            val rendered = renderApiDocs(symbols, groups)

            assertContains(rendered, "### `Client`\n\nUses `Network`.")
            assertContains(
                rendered,
                """
                ```kotlin
                class Client {
                    /**
                     * Creates a `Client` using `technology.polygon.omswallet.utils.parseUnits`.
                     */
                    constructor(
                        value: String,
                    )
                }
                ```
                """.trimIndent(),
            )
            assertFalse("Later implementation details" in rendered)
            assertFalse("@param" in rendered)
        }
    }

    @Test
    fun `rejects unassigned and missing configured symbols`() {
        val symbols = mapOf("pkg.Client" to ApiSymbol("pkg.Client", mutableListOf(ApiDeclaration("class Client", null))))
        val emptyGroups = requiredGroups.map { PresentationGroup(it, emptyList()) }
        assertContains(
            assertFailsWith<ApiDocsException> { renderApiDocs(symbols, emptyGroups) }.message.orEmpty(),
            "unassigned",
        )

        val missingGroups =
            requiredGroups.mapIndexed { index, name ->
                PresentationGroup(name, if (index == 0) listOf("pkg.Missing" to "Missing") else emptyList())
            }
        assertContains(
            assertFailsWith<ApiDocsException> { renderApiDocs(emptyMap(), missingGroups) }.message.orEmpty(),
            "missing",
        )
    }
}
