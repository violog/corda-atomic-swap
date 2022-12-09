package com.template.contracts

import com.template.states.*
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

@Deprecated(
    "UTXOContract is used to handle all transactions, because it is inconvenient and confusing" +
            " to use multiple contracts for intersecting states (it will require command checks on both contracts)"
)
class HTLCContract : Contract {
    private val availableAssets: List<Asset> = listOf(BTC, DASH)
    private val availableCommands: List<String> = listOf("Lock", "Unlock")

    companion object {
        val ID = HTLCContract::class.qualifiedName!!
    }

    interface Commands : CommandData {
        class Lock : Commands
        class Unlock : Commands
//        class AddParticipants: Commands // Not implemented yet, participants must always be the same
    }

    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        requireThat {
            "Exactly one input state must be consumed" using (tx.inputs.size == 1)
            "Exactly one output state must be created" using (tx.outputs.size == 1)
            val input = tx.inputs.single().state.data
            val output = tx.outputs.single().data
            "Participants must not be changed" using (input.participants == output.participants)
            "All participants must sign the transaction" using
                    (cmd.signers.containsAll(input.participants.map { it.owningKey }))
        }

        when (cmd.value) {
            is Commands.Lock -> verifyLock(tx)
            is Commands.Unlock -> verifyUnlock(tx)
            else -> throw IllegalArgumentException("Must use only one of the following commands: $availableCommands")
        }
    }

    // for now only one input and output for convenience
    private fun verifyLock(tx: LedgerTransaction) = requireThat {
        val input = tx.inputsOfType<UTXO>().single()
        val output = tx.outputsOfType<HTLC>().single()
        val cmd = tx.commands.requireSingleCommand<Commands.Lock>()
        "Sender must be the owner of UTXO" using (input.owner == output.sender)
        "Receiver must not be the owner of UTXO" using (input.owner != output.receiver)
        "Receiver must be among participants" using (input.participants.contains(output.receiver))
        "Must be valid input and output assets" using
                (availableAssets.containsAll(listOf(input.asset, output.asset)))
        "Assets to lock must share the same network" using (input.asset == output.asset)
        "Input and output amount must be equal and positive" using
                (input.amount == output.amount && input.amount > 0)
        "Secret must not be shared on locking" using (output.secret == null)
        "Locktime must be after the current time" using (output.locktime > Instant.now().epochSecond)
    }

    private fun verifyUnlock(tx: LedgerTransaction) = requireThat {
        val input = tx.inputsOfType<HTLC>().single()
        val output = tx.outputsOfType<UTXO>().single()
        val cmd = tx.commands.requireSingleCommand<Commands.Unlock>()
        "Assets to unlock must share the same network" using (input.asset == output.asset)
        "Input and output amount must be equal and positive" using
                (input.amount == output.amount && input.amount > 0)
        "Receiver must be among participants" using (input.participants.contains(input.receiver))
        "Must be valid input and output assets" using
                (availableAssets.containsAll(listOf(input.asset, output.asset)))
        "Assets to lock must share the same network" using (input.asset == output.asset)
        "Input and output amount must be equal and positive" using
                (input.amount == output.amount && input.amount > 0)
        // oh, shit, how can I set the secret into existing input?
        // No, no, I don't want to create one more command. Is there another way?
        "Secret must not be shared on locking" using (input.secret != null)
    }

    /*
        private fun containsAllSigners(cmd: CommandWithParties<Commands>, utxos: List<ContractState>): Boolean =
            cmd.signers.containsAll(anyFirst(utxos, listOf()).participants.map { it.owningKey })

        private fun arePositive(utxos: List<ContractState>): Boolean =
            utxos.all { it.amount > 0 }

        private fun areParticipantsSame(inputs: List<ContractState>, outputs: List<ContractState>): Boolean =
            (inputs + outputs).all { anyFirst(inputs, outputs).participants == it.participants }

        private fun areAssetsSame(inputs: List<ContractState>, outputs: List<ContractState>): Boolean =
            (inputs + outputs).all { anyFirst(inputs, outputs).asset == it.asset }

        private fun anyFirst(inputs: List<ContractState>, outputs: List<ContractState>): ContractState =
            if (inputs.isNotEmpty()) inputs.first() else outputs.first()
    */

    private fun isValidHash(hash: String): Boolean =
        hash.length == 32 && hash.all { it in 'a'..'f' || it in '0'..'9' }
}
