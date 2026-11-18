package io.valroot.core.model

/** Conservative mainnet aliases used by exchange transfer records. Unknown or composite labels stay unresolved. */
object TransactionChains {
    private val aliases =
        mapOf(
            "BTC" to "bitcoin:mainnet",
            "BITCOIN" to "bitcoin:mainnet",
            "BITCOIN:MAINNET" to "bitcoin:mainnet",
            "SOL" to "solana:mainnet",
            "SOLANA" to "solana:mainnet",
            "SOLANA:MAINNET" to "solana:mainnet",
            "ETH" to "eip155:1",
            "ETHEREUM" to "eip155:1",
            "ERC20" to "eip155:1",
            "ERC-20" to "eip155:1",
            "BSC" to "eip155:56",
            "BEP20" to "eip155:56",
            "BEP-20" to "eip155:56",
            "ARBI" to "eip155:42161",
            "ARBITRUM" to "eip155:42161",
            "ARBITRUM ONE" to "eip155:42161",
            "OP" to "eip155:10",
            "OPTIMISM" to "eip155:10",
            "BASE" to "eip155:8453",
            "MATIC" to "eip155:137",
            "POL" to "eip155:137",
            "POLYGON" to "eip155:137",
            "AVAXC" to "eip155:43114",
            "AVAX C-CHAIN" to "eip155:43114",
            "MANTLE" to "eip155:5000",
            "ZKSYNC" to "eip155:324",
            "LINEA" to "eip155:59144",
            "CELO" to "eip155:42220",
            "SCROLL" to "eip155:534352",
            "BLAST" to "eip155:81457",
            "OPBNB" to "eip155:204",
            "GNO" to "eip155:100",
            "XDAI" to "eip155:100",
            "TRX" to "tron:mainnet",
            "TRON" to "tron:mainnet",
            "TRC20" to "tron:mainnet",
            "TON" to "ton:mainnet",
            "SUI" to "sui:mainnet",
            "APT" to "aptos:mainnet",
            "APTOS" to "aptos:mainnet",
            "NEAR" to "near:mainnet",
            "XRP" to "xrp:mainnet",
            "LTC" to "litecoin:mainnet",
            "DOGE" to "dogecoin:mainnet",
        )

    fun normalize(chain: String): String? {
        val value = chain.trim().uppercase()
        aliases[value]?.let { return it }
        if (Regex("EIP155:[1-9][0-9]*").matches(value)) {
            return value.substringAfter(':').toLongOrNull()?.let { "eip155:$it" }
        }
        return null
    }

    /** Preserve an explicit unresolved label as a matching boundary, without treating it as a resolved identity. */
    fun matchingBoundary(chain: String): String? =
        normalize(chain) ?: chain.trim().takeIf { it.isNotEmpty() }?.let { "unresolved:${it.uppercase()}" }

    /** An unresolved chain cannot establish a shared on-chain transaction. */
    fun identity(
        chain: String,
        transaction: String,
    ): String? =
        normalize(chain)?.let { network ->
            transaction.trim().takeIf { it.isNotEmpty() }?.let { "$network|${TransactionIds.normalize(it)}" }
        }

    /** The same hash observed on different chains cannot establish a transfer relationship. */
    fun conflict(
        first: String?,
        second: String?,
    ): Boolean =
        first != null &&
            second != null &&
            '|' in first &&
            '|' in second &&
            first.substringAfter('|') == second.substringAfter('|') &&
            first.substringBefore('|') != second.substringBefore('|')
}
