package com.template.contracts

import com.template.states.Asset
import com.template.states.FungibleAsset
import com.template.states.HTLC
import com.template.states.UTXO
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

class UTXOContract : Contract {
    private val availableAssets: List<Asset> = listOf(BTC, DASH)
    private val availableCommands: List<String> = listOf("Mint", "Burn", "Transfer", "Lock", "Unlock")

    // it is not a good idea to use companion objects, because more operations are performed (read StackOverflow)
    // Anyway, I'd like to get rid of ID, if it is useless because of @BelongsToContract
    companion object {
        val ID = UTXOContract::class.qualifiedName!!
    }

    interface Commands : CommandData {
        class Mint : Commands
        class Burn : Commands
        class Transfer : Commands

        // Why not separate HTLC contract? It is less convenient for 'intersecting' states, details are in corda/notes.txt
        class Lock : Commands
        class Unlock : Commands
//        class AddParticipants: Commands // Not implemented yet, participants must always be the same
    }

    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        val allStates = tx.inputStates + tx.outputStates
        requireThat {
            // fixme: probably this is checked earlier, if so - drop it
            "At least one input/output state must be specified in transaction" using allStates.isNotEmpty()
            val part = allStates.first().participants
            val isLockCmd = cmd.value is Commands.Lock || cmd.value is Commands.Unlock
            val allowNonEqual = cmd.value is Commands.Mint || cmd.value is Commands.Burn
            // Lazy values work as intended, you may check it in Kotlin console with sleeping function
            val inputs by lazy { tx.inputStates.map { it as FungibleAsset } }
            val outputs by lazy { tx.outputStates.map { it as FungibleAsset } }
            val allFung by lazy { inputs + outputs }
            val asset by lazy { allFung.first().asset }
            val inSum by lazy { inputs.map { it.amount }.sum() }
            val outSum by lazy { outputs.map { it.amount }.sum() }
            "Participants must appear same in all inputs and outputs" using allStates.all { part == it.participants }
            "All participants must sign the transaction" using cmd.signers.containsAll(part.map { it.owningKey })
            "All states must be of FungibleAsset state" using allStates.all { it is FungibleAsset }
            "All inputs and outputs must use the same asset" using allFung.all { asset == it.asset }
            "Only the following assets are allowed: $availableAssets" using availableAssets.contains(asset)
            "All amounts must be positive" using allFung.all { it.amount > 0 }
            "For any command except Mint and Burn input and output amount must be equal" using (allowNonEqual || inSum == outSum)
            // fixme: no, any number of inputs is possible! But for now OK, this is bullshit
            "Exactly one input state must be consumed" using (!isLockCmd || tx.inputs.size == 1)
            "Exactly one output state must be created" using (!isLockCmd || tx.outputs.size == 1)
        }

        when (cmd.value) {
            is Commands.Mint -> verifyMint(tx)
            is Commands.Burn -> verifyBurn(tx)
            is Commands.Transfer -> verifyTransfer(tx)
            is Commands.Lock -> verifyLock(tx)
            is Commands.Unlock -> verifyUnlock(tx)
            else -> throw IllegalArgumentException("Must use only one of the following commands: $availableCommands")
        }
    }

    private fun verifyMint(tx: LedgerTransaction) = requireThat {
        "No input states must be consumed" using tx.inputs.isEmpty()
        "Only one output state must be created" using (tx.outputs.size == 1)
        val out = tx.outputsOfType<UTXO>().single()
        "Owner must be among participants" using (out.participants.contains(out.owner))
    }

    private fun verifyBurn(tx: LedgerTransaction) = requireThat {
        "No output states must be created" using (tx.outputs.isEmpty())
    }

    private fun verifyTransfer(tx: LedgerTransaction) = requireThat {
        val inputs = tx.inputsOfType<UTXO>()
        val outputs = tx.outputsOfType<UTXO>()
        "Transaction must contain at least 1 input and 1 output of UTXO" using (inputs.isNotEmpty() && outputs.isNotEmpty())
        val participants = inputs.first().participants
        "New owners must be one of participants" using (participants.containsAll(outputs.map { it.owner }))
    }

    // fixme: ensure that all fields evolve as intended, especially in HTLC
    private fun verifyLock(tx: LedgerTransaction) = requireThat {
        val input = tx.inputsOfType<UTXO>().single()
        val output = tx.outputsOfType<HTLC>().single()
        "Sender must be the owner of UTXO" using (input.owner == output.sender)
        "Receiver must not be the owner of UTXO" using (input.owner != output.receiver)
        "Receiver must be among participants" using (input.participants.contains(output.receiver))
        "Secret must not be shared on locking" using (output.secret == null)
        "Locktime must be after the current time" using (output.locktime > Instant.now().epochSecond)
    }

    private fun verifyUnlock(tx: LedgerTransaction) {
        val input = tx.inputsOfType<HTLC>().single()
        val output = tx.outputsOfType<UTXO>().single()
        if (input.locktime > Instant.now().epochSecond) requireThat { // locktime haven't been reached yet
            "Secret must be provided to claim assets" using (input.secret != null)
            "Secret must correspond its hash" using (HTLC.hash(input.secret!!) == input.secretHash)
            "Only receiver is able to claim assets before locktime" using (input.receiver == output.owner)
            return
        }
        requireThat { // locktime have been reached
            "Only sender is able to claim assets after locktime" using (input.sender == output.owner)
        }
    }
}

// TODO: move to the different file
object BTC : Asset() {
    override val code: String = "BTC"
    override val decimals: Int = 8
}

object DASH : Asset() {
    override val code: String = "DASH"
    override val decimals: Int = 8
}
