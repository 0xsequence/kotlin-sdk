package technology.polygon.omswallet.models

/** HPKE cipher suites accepted by the wallet-import transport. */
enum class WalletImportCipherSuite(
    val wireValue: String,
) {
    X25519Sha256Aes256Gcm("x25519-sha256-aes256gcm"),
    X25519Sha256ChaCha20Poly1305("x25519-sha256-chacha20poly1305"),
    P256Sha256Aes256Gcm("p256-sha256-aes256gcm"),
    P256Sha256ChaCha20Poly1305("p256-sha256-chacha20poly1305"),
}

/** Plaintext private-key input for high-level wallet import. */
sealed interface WalletImportPrivateKey {
    val walletType: WalletType

    /** Ethereum private key supplied as hexadecimal text. */
    data class Ethereum(
        val value: String,
    ) : WalletImportPrivateKey {
        override val walletType: WalletType = WalletType.Ethereum
    }

    /** Ethereum private key supplied as 32 raw bytes. */
    class EthereumBytes(
        val value: ByteArray,
    ) : WalletImportPrivateKey {
        override val walletType: WalletType = WalletType.Ethereum
    }

    /** Solana seed or keypair supplied as base58 text. */
    data class Solana(
        val value: String,
    ) : WalletImportPrivateKey {
        override val walletType: WalletType = WalletType.Solana
    }

    /** Solana seed or keypair supplied as 32 or 64 raw bytes. */
    class SolanaBytes(
        val value: ByteArray,
    ) : WalletImportPrivateKey {
        override val walletType: WalletType = WalletType.Solana
    }
}

/** Attested public key returned for an advanced wallet-import encryption flow. */
data class WalletImportRecipientKey(
    val keyId: String,
    val cipherSuite: WalletImportCipherSuite,
    val publicKey: String,
)

/** Caller-encrypted private-key material accepted by advanced wallet import. */
data class EncryptedWalletImportKeyMaterial(
    val keyId: String,
    val cipherSuite: WalletImportCipherSuite,
    val encapsulatedKey: String,
    val ciphertext: String,
)
