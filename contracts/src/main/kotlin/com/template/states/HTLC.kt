package com.template.states

import com.template.contracts.UTXOContract
import net.corda.core.contracts.BelongsToContract
import net.corda.core.contracts.ContractState
import net.corda.core.contracts.requireThat
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.time.Instant
import kotlin.math.pow

@BelongsToContract(UTXOContract::class)
data class HTLC(
    val sender: Party,
    val receiver: Party,
    override val asset: Asset,
    override val amount: Int,
    val secret: String?,
    val secretHash: String,
    private val lockDuration: Int,
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : ContractState, FungibleAsset {
    val locktime: Long = Instant.now().epochSecond + lockDuration

    init {
        requireThat {
            "Too few participants: given ${participants.size}, minimum is 2" using (participants.size >= 2)
            "Duration must be positive" using (lockDuration > 0)
        }
    }

    fun withParticipants(p: List<AbstractParty>): HTLC {
        return copy(participants = participants + p)
    }

    override fun toString(): String =
        "Party \"locked\" has ${amount.toFloat() / 10f.pow(asset.decimals)} of $asset"
}
