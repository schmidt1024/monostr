package com.monostr.monero

import java.math.BigDecimal
import java.net.URLEncoder

/** Builds `monero:` payment URIs. Amounts are piconero (1 XMR = 10^12). */
object MoneroUri {
    private const val DECIMALS = 12
    private val PLAIN_DECIMAL = Regex("""\d+(\.\d+)?""")

    fun formatAmount(piconero: Long): String {
        require(piconero >= 0) { "amount must not be negative" }
        return BigDecimal.valueOf(piconero).movePointLeft(DECIMALS).stripTrailingZeros().toPlainString()
    }

    fun parseAmount(xmr: String): Long {
        val normalized = xmr.trim().replace(',', '.')
        require(PLAIN_DECIMAL.matches(normalized)) { "not a decimal amount: '$xmr'" }
        val value = try {
            BigDecimal(normalized)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("not a decimal amount: '$xmr'", e)
        }
        require(value.signum() >= 0) { "amount must not be negative" }
        require(value.stripTrailingZeros().scale() <= DECIMALS) { "at most $DECIMALS decimal places" }
        return try {
            value.movePointRight(DECIMALS).longValueExact()
        } catch (e: ArithmeticException) {
            throw IllegalArgumentException("amount too large: '$xmr'", e)
        }
    }

    fun build(address: String, piconero: Long, description: String? = null): String {
        require(piconero > 0) { "amount must be positive" }
        val sb = StringBuilder("monero:").append(address).append("?tx_amount=").append(formatAmount(piconero))
        if (!description.isNullOrEmpty()) {
            sb.append("&tx_description=").append(encode(description))
        }
        return sb.toString()
    }

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
