package io.valroot.core.model

/** One spelling per on-chain transaction ID, so IDs from wallets and exchanges compare equal. */
object TransactionIds {
    private val HEX = Regex("(0x)?[0-9a-fA-F]+")

    /**
     * Hex IDs (EVM hashes, Bitcoin txids) in lower case, since their case carries no meaning; every other ID (Solana's
     * Base58 signatures, for one) exactly as written, since its case is part of it. Surrounding space is removed.
     */
    fun normalize(id: String): String = id.trim().let { if (HEX.matches(it)) it.lowercase() else it }
}
