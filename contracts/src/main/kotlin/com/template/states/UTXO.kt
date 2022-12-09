package com.template.states

import com.template.contracts.UTXOContract
import com.template.states.HTLC.Companion.org
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import kotlin.math.pow

@BelongsToContract(UTXOContract::class)
data class UTXO(
    val owner: Party,
    override val asset: Asset,
    override val amount: Long,
    override val participants: List<AbstractParty> = listOf(owner)
) : ContractState, FungibleToken {
    init {
        requireThat {
            "Owner is not in participants" using (participants.contains(owner))
            "Too few participants: given ${participants.size}, minimum is 2" using (participants.size >= 2)
        }
    }

    constructor(owner: Party, asset: Asset, amount: Long, counterparty: Party) :
            this(owner, asset, amount, listOf(owner, counterparty))

    override fun toString(): String =
        "owner=${owner.org} value=${amount.toFloat() / 10f.pow(asset.decimals)}$asset"
}
