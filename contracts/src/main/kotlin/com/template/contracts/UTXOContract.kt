package com.template.contracts

import com.template.states.*
import net.corda.core.contracts.CommandData
import net.corda.core.contracts.Contract
import net.corda.core.contracts.requireSingleCommand
import net.corda.core.contracts.requireThat
import net.corda.core.transactions.LedgerTransaction
import java.time.Instant

class UTXOContract : Contract {
    private val availableAssets: List<Asset> = listOf(BTC, DASH)
    private val availableCommands: List<String> = listOf("Mint", "Burn", "Transfer", "Lock", "Unlock")

    interface Commands : CommandData {
        class Mint : Commands // [] -> [UTXO]
        class Burn : Commands // [UTXO, ...] -> []
        class Transfer : Commands // [UTXO, ...] -> [UTXO, ...]

        // Why not separate HTLC contract? It is less convenient for 'intersecting' states, details are in corda/notes.txt
        class Lock : Commands // [UTXO] -> [HTLC] (no secret)
        class Unlock : Commands // [HTLC] -> [HTLC] (supplying HTLC with a secret)
        class Convert : Commands // [HTLC] -> [UTXO] (possible when valid secret was provided or locktime was reached)
    }

    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        val allStates = tx.inputStates + tx.outputStates
        requireThat {
            val part = allStates.first().participants
            val isLockCmd = cmd.value is Commands.Lock || cmd.value is Commands.Unlock || cmd.value is Commands.Convert
            val allowNonEqual = cmd.value is Commands.Mint || cmd.value is Commands.Burn
            // Lazy values work as intended, you may check it in Kotlin console with sleeping function
            val inputs by lazy { tx.inputStates.map { it as FungibleToken } }
            val outputs by lazy { tx.outputStates.map { it as FungibleToken } }
            val allFung by lazy { inputs + outputs }
            val asset by lazy { allFung.first().asset }
            val inSum by lazy { inputs.map { it.amount }.sum() }
            val outSum by lazy { outputs.map { it.amount }.sum() }
            "Participants must appear same in all inputs and outputs" using allStates.all { part == it.participants }
            "All participants must sign the transaction" using cmd.signers.containsAll(part.map { it.owningKey })
            "All states must be of FungibleAsset state" using allStates.all { it is FungibleToken }
            "All inputs and outputs must use the same asset" using allFung.all { asset == it.asset }
            "Only the following assets are allowed: $availableAssets" using availableAssets.contains(asset)
            "All amounts must be positive" using allFung.all { it.amount > 0 }
            "For any command except Mint and Burn input and output amount must be equal" using (allowNonEqual || inSum == outSum)
            "Exactly one input state must be consumed" using (!isLockCmd || tx.inputs.size == 1)
            "Exactly one output state must be created" using (!isLockCmd || tx.outputs.size == 1)
        }

        when (cmd.value) {
            is Commands.Mint -> verifyMint(tx)
            is Commands.Burn -> verifyBurn(tx)
            is Commands.Transfer -> verifyTransfer(tx)
            is Commands.Lock -> verifyLock(tx)
            is Commands.Unlock -> verifyUnlock(tx)
            is Commands.Convert -> verifyConvert(tx)
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

    private fun verifyLock(tx: LedgerTransaction) = requireThat {
        val input = tx.inputsOfType<UTXO>().single()
        val output = tx.outputsOfType<HTLC>().single()
        "Sender must be the owner of UTXO" using (input.owner == output.sender)
        "Receiver must not be the owner of UTXO" using (input.owner != output.receiver)
        "Receiver must be among participants" using (input.participants.contains(output.receiver))
        "Secret must not be shared on locking" using (output.secret == null)
        "Locktime must be after the current time" using (output.locktime > Instant.now().epochSecond)
    }

    private fun verifyUnlock(tx: LedgerTransaction) = requireThat {
        val input = tx.inputsOfType<HTLC>().single()
        val output = tx.outputsOfType<HTLC>().single()
        val noSecret = output.withoutSecret()
        "All fields, except secret, must remain the same" using (input == noSecret)
        "Locktime is reached, coins can only be taken by sender with Convert command" using
                (Instant.now().epochSecond < input.locktime)
        "Valid secret must be provided to claim assets" using output.isSecretValid()
    }

    private fun verifyConvert(tx: LedgerTransaction) {
        val input = tx.inputsOfType<HTLC>().single()
        val output = tx.outputsOfType<UTXO>().single()
        if (Instant.now().epochSecond < input.locktime) requireThat { // locktime haven't been reached yet
            "Valid secret must be provided to claim assets before locktime" using input.isSecretValid()
            "Only receiver is able to claim assets before locktime" using (input.receiver == output.owner)
            return
        }
        requireThat { // locktime have been reached
            "Only sender is able to claim assets after locktime" using (input.sender == output.owner)
        }
    }
}
