package com.template.states

import com.template.contracts.UTXOContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.serialization.CordaSerializable
import kotlin.math.pow

@BelongsToContract(UTXOContract::class) // IDK how to make it belong to multiple contracts
data class UTXO(
    val owner: Party,
    val asset: Asset,
    val amount: Int,
    // to make it easier, I can create just counterparty, but this is stupid
    // I want to have multiple participants that can see my balance; for now it will be only counterparty
    override val participants: List<AbstractParty> = listOf(owner)
) : ContractState {
    // TODO: check duplicate participants, do it in withParticipants as well
    init {
        if (!participants.contains(owner)) {
            throw IllegalArgumentException("Owner is not in participants")
        }
        if (participants.size < 2) {
            throw IllegalArgumentException("Too few participants: given ${participants.size}, minimum is 2")
        }
    }

    constructor(owner: Party, asset: Asset, amount: Int, counterparty: Party) :
            this(owner, asset, amount, listOf(owner, counterparty))

    fun withParticipants(p: List<AbstractParty>): UTXO {
        return copy(participants = participants + p)
    }

    override fun toString(): String =
        "Party \"${owner.name.organisation}\" has ${amount.toFloat() / 10f.pow(asset.decimals)} of $asset"
}

@CordaSerializable
abstract class Asset {
    abstract val code: String
    abstract val decimals: Int
    override fun toString(): String = code
}