package technology.polygon.omswallet

/** Trust policy used to verify attested wallet-import responses. */
class WalletImportConfiguration(
    trustedPcr0s: Collection<String>,
) {
    internal val trustedPcr0s: Set<String> =
        trustedPcr0s
            .map { it.trim().lowercase().removePrefix("0x") }
            .also { normalized ->
                require(
                    normalized.isNotEmpty() &&
                        normalized.all { value ->
                            value.length == 96 &&
                                value.all { it in '0'..'9' || it in 'a'..'f' } &&
                                value.any { it != '0' }
                        },
                ) {
                    "walletImport.trustedPcr0s must contain at least one nonzero 48-byte hex PCR0"
                }
            }.toSet()
}
