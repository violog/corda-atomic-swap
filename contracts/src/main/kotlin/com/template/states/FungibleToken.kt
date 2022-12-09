package com.template.states

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
interface FungibleToken {
    val asset: Asset
    val amount: Long
}

@CordaSerializable
abstract class Asset {
    abstract val code: String
    abstract val decimals: Int
    override fun toString(): String = code
}

object BTC : Asset() {
    override val code: String = "BTC"
    override val decimals: Int = 8
}

object DASH : Asset() {
    override val code: String = "DASH"
    override val decimals: Int = 8
}
