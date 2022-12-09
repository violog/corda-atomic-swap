package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.Asset
import com.template.states.UTXO
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.*
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object MintFlow {
    private const val flowLabel = "MINT"

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val asset: Asset,
        private val amount: Long
    ) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(START_FLOW.label)
            progressTracker.currentStep = START_FLOW
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val output = UTXO(ourIdentity, asset, amount, counterparty)
            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Mint(), ourIdentity.owningKey, counterparty.owningKey)
                .addOutputState(output, UTXOContract.ID)
            return subFlow(SignFinalizeFlow(counterparty, builder, flowLabel, progressTracker))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            // I still don't need counterparty's signature. I don't get why it's needed
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    myLog(TX_ADD_CHECKS.label)
                    val out = stx.tx.outputs.single().data
                    "Output must be UTXO transaction" using (out is UTXO)
                    "Coins can only be minted to the transaction initiator" using
                            ((out as UTXO).owner == counterpartySession.counterparty) // counterparty is Initiator
                }
            }

            myLog("Responder flow initiated")
            val txId = subFlow(signTransactionFlow).id
            myLog(TX_FINALIZE.label)
            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }

}
