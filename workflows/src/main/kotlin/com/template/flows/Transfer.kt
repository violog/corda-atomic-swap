package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.Asset
import com.template.states.UTXO
import net.corda.core.contracts.StateAndRef
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object TransferFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val receiver: Party,
        private val asset: Asset,
        private val amount: Int
    ) : FlowLogic<SignedTransaction>() {
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "TRANSFER", ourIdentity)

        // now I have to define logic that will pick first N UTXOs and reject the others
        private fun pickEnoughCoins(inputs: List<StateAndRef<UTXO>>, acc: List<StateAndRef<UTXO>> = listOf()):
                List<StateAndRef<UTXO>> {
            val sum = acc.map { it.state.data.amount }.sum()
            if (sum >= amount) return acc
            if (inputs.size == acc.size)
                throw FlowException("Insufficient $asset balance of party $ourIdentity: required $amount, found $sum")
            return pickEnoughCoins(inputs, acc + inputs[acc.size]) // tricky shit, you got it, right?
        }

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(TX_BUILD.label)
            progressTracker.currentStep = TX_BUILD
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputsCriteria = QueryCriteria.VaultQueryCriteria()
                .withStatus(Vault.StateStatus.UNCONSUMED)
                .withRelevancyStatus(Vault.RelevancyStatus.RELEVANT)
            val inputs = serviceHub.vaultService.queryBy<UTXO>(inputsCriteria).states
            // Use queryable state? But how? No, I don't need it. Probably I'll need it for HTLC, especially to get my secret from vault.
            val myInputs = inputs
                .filter { it.state.data.owner == ourIdentity }
                .filter { it.state.data.asset == asset }
            if (myInputs.isEmpty())
                throw FlowException("No UTXO found for party $ourIdentity")
            myLog("found ${inputs.size} UTXO of $asset, ${myInputs.size} of them is owned by the party $ourIdentity")

            val destination = UTXO(receiver, asset, amount, listOf(ourIdentity, receiver))
            val coinsToSend = pickEnoughCoins(myInputs)
            val sumToSend = coinsToSend.map { it.state.data.amount }.sum()
            val change = sumToSend - amount
            myLog("sending ${coinsToSend.size} coins of balance $sumToSend, change is $change")

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Transfer(), ourIdentity.owningKey, receiver.owningKey)
                .addOutputState(destination, UTXOContract.ID)
            coinsToSend.forEach { builder.addInputState(it) }
            if (change > 0)
                builder.addOutputState(UTXO(ourIdentity, asset, change, listOf(ourIdentity, receiver)))

            myLog(TX_SIGN.label)
            progressTracker.currentStep = TX_SIGN
            val partSignedTx = serviceHub.signInitialTransaction(builder)

            myLog(INIT_SESSION.label)
            progressTracker.currentStep = INIT_SESSION
            val session = initiateFlow(receiver)

            myLog(TX_COLLECTSIG.label)
            progressTracker.currentStep = TX_COLLECTSIG
            val fullSignedTx =
                subFlow(CollectSignaturesFlow(partSignedTx, setOf(session), TX_COLLECTSIG.childProgressTracker()))

            myLog(TX_FINALIZE.label)
            progressTracker.currentStep = TX_FINALIZE
            return subFlow(FinalityFlow(fullSignedTx, setOf(session), TX_FINALIZE.childProgressTracker()))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "TRANSFER", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    myLog(TX_ADD_CHECKS.label)
                    val ltx = stx.toLedgerTransaction(serviceHub, false)
                    val inputs = ltx.inputs.map { it.state.data }
                    val outputs = ltx.outputs.map { it.data }

                    "Input/output must be an UTXO state reference" using ((inputs + outputs).all { it is UTXO })
                    "Coins can only be transferred by their owner" using
                            (inputs.all { (it as UTXO).owner == counterpartySession.counterparty })
                }
            }

            myLog("Responder flow initiated")
            val txId = subFlow(signTransactionFlow).id
            myLog(TX_FINALIZE.label)
            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }

}

/*
* *** This is harder to understand than V1 (doesn't work, not intuitive)
        private fun pickEnoughCoinsV2(inputs: List<StateAndRef<UTXO>>): List<StateAndRef<UTXO>> {
            if (inputs.isEmpty())
                throw FlowException("Insufficient $asset balance of party $ourIdentity: required $amount, found $sum")

            val sum = inputs.map { it.state.data.amount }.sum()
            if (sum > amount)
                return inputs[0]+pickEnoughCoinsV2(inputs.drop(0))

                if (sum < amount) {
                    return pickEnoughCoinsV2(inputs, acc + inputs[acc.size]) // tricky shit, you got it, right?
                }
            return acc
        }
*/
