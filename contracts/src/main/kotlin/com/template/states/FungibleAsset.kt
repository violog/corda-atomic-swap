package com.template.states

import net.corda.core.serialization.CordaSerializable

@CordaSerializable
interface FungibleAsset {
    val asset: Asset
    val amount: Int
}

@CordaSerializable
abstract class Asset {
    abstract val code: String
    abstract val decimals: Int
    override fun toString(): String = code
}
