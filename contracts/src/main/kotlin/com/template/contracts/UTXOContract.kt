package com.template.contracts

import com.template.states.Asset
import com.template.states.UTXO
import net.corda.core.contracts.*
import net.corda.core.transactions.LedgerTransaction

class UTXOContract : Contract {
    private val availableAssets: List<Asset> = listOf(BTC, DASH)
    private val availableCommands: List<String> = listOf("Mint", "Burn", "Transfer")

    // it is not a good idea to use companion objects, because more operations are performed (read StackOverflow)
    // Anyway, I'd like to get rid of ID, if it is useless because of @BelongsToContract
    companion object {
        val ID = UTXOContract::class.qualifiedName!!
    }

    interface Commands : CommandData {
        class Mint : Commands
        class Burn : Commands
        class Transfer : Commands
//        class AddParticipants: Commands // Not implemented yet, participants must always be the same
    }

    override fun verify(tx: LedgerTransaction) {
        val cmd = tx.commands.requireSingleCommand<Commands>()
        when (cmd.value) {
            is Commands.Mint -> verifyMint(tx)
            is Commands.Burn -> verifyBurn(tx)
            is Commands.Transfer -> verifyTransfer(tx)
            else -> throw IllegalArgumentException("Must use only one of the following commands: $availableCommands")
        }
    }

    private fun verifyMint(tx: LedgerTransaction) {
        requireThat {
            "No input states must be consumed" using (tx.inputs.isEmpty())
            "Only one output state must be created" using (tx.outputs.size == 1)
            val cmd = tx.commands.requireSingleCommand<Commands.Mint>()
            val out = tx.outputsOfType<UTXO>().single()
            "Participants must not be changed" using (areParticipantsSame(listOf(), listOf(out)))
//            "Only owner of UTXO to be minted must sign the transaction" using
//                    (cmd.signers.size == 1 && cmd.signers.single() == out.owner.owningKey)
            "All participants must sign the transaction" using (containsAllSigners(cmd, listOf(out)))
            "Minted amount must be positive" using (out.amount > 0)
            "Invalid asset ${out.asset}, must be one of the following: $availableAssets" using
                    (availableAssets.contains(out.asset))
//            "Value must be greater than fee" using (out.asset.fee)
        }
    }

    private fun verifyBurn(tx: LedgerTransaction) {
        requireThat {
            "At least one input state must be consumed" using (tx.inputs.isNotEmpty())
            "No output states must be created" using (tx.outputs.isEmpty())
            val cmd = tx.commands.requireSingleCommand<Commands.Burn>()
            // fuck, I need multiple inputs
            val inputs = tx.inputsOfType<UTXO>()
            "Participants must not be changed" using (areParticipantsSame(inputs, listOf()))
            "All participants must sign the transaction" using (containsAllSigners(cmd, inputs))
            "Assets to burn must share the same network" using (areAssetsSame(inputs, listOf()))
            "Found invalid asset among these: ${inputs.map { it.asset.code }}; must be one of the following: $availableAssets" using
                    (inputs.all { availableAssets.contains(it.asset) })
        }
    }

    private fun verifyTransfer(tx: LedgerTransaction) {
        requireThat {
            val inputs = tx.inputsOfType<UTXO>()
            val outputs = tx.outputsOfType<UTXO>()
            "At least one input state must be consumed" using (inputs.isNotEmpty())
            "At least one output state must be created" using (outputs.isNotEmpty())
            "Participants must be the same in all inputs and outputs" using (areParticipantsSame(inputs, outputs))
            val cmd = tx.commands.requireSingleCommand<Commands.Transfer>()
            "All participants must sign the transaction" using (containsAllSigners(cmd, inputs))
            "All assets must share the same network" using (areAssetsSame(inputs, outputs))
            "Inputs and outputs must be positive" using (arePositive(inputs) && arePositive(outputs))
            val participants = inputs.first().participants
            "New owners must be one of participants" using (participants.containsAll(outputs.map { it.owner }))
            val inSum = inputs.map { it.amount }.sum()
            val outSum = outputs.map { it.amount }.sum()
            "Sum of inputs must equal sum of outputs" using (inSum == outSum)
        }
    }

    private fun containsAllSigners(cmd: CommandWithParties<Commands>, utxos: List<UTXO>): Boolean =
//        areParticipantsSame(utxos, listOf()) && // should be already evaluated in any verifier
        cmd.signers.containsAll(anyFirst(utxos, listOf()).participants.map { it.owningKey })

    private fun arePositive(utxos: List<UTXO>): Boolean =
        utxos.all { it.amount > 0 }

    private fun areParticipantsSame(inputs: List<UTXO>, outputs: List<UTXO>): Boolean =
        (inputs + outputs).all { anyFirst(inputs, outputs).participants == it.participants }
//        val inPart = inputs.map { it.participants }
//        val outPart = outputs.map { it.participants }
//        val allPart = inPart + outPart
//        return allPart.all { allPart.first() == it }

    // todo: I can refactor args list to a single list and pass concatenated list
    private fun areAssetsSame(inputs: List<UTXO>, outputs: List<UTXO>): Boolean =
        (inputs + outputs).all { anyFirst(inputs, outputs).asset == it.asset }

    private fun anyFirst(inputs: List<UTXO>, outputs: List<UTXO>): UTXO =
        if (inputs.isNotEmpty()) inputs.first() else outputs.first()
}

object BTC : Asset() {
    override val code: String = "BTC"
    override val decimals: Int = 8
}

object DASH : Asset() {
    override val code: String = "DASH"
    override val decimals: Int = 8
}
