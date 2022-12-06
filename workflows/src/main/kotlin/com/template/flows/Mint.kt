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
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val asset: Asset,
        private val amount: Int
    ) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "MINT", ourIdentity)
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(TX_BUILD.label)
            progressTracker.currentStep = TX_BUILD
            val notary = serviceHub.networkMapCache.notaryIdentities.single()
            val output = UTXO(ourIdentity, asset, amount, counterparty)
            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Mint(), ourIdentity.owningKey, counterparty.owningKey)
                .addOutputState(output, UTXOContract.ID)
            // It didn't help to drop @BelongsToContract
            // .addOutputState(TransactionState(output, UTXOContract.ID, notary))

            // Tx is verified 3 times: on signing, collecting signatures and on finalization
            // However, flows execution is continued when tx verification fails on signing: errors are shown in log
            // Later it tries to collect signatures and throws exception on verification, stopping flow
            // Moreover, if I use builder.verify(serviceHub), flow stops at this function
            // I don't need to use this verifier, because I'm sure that my flow is good
            myLog(TX_SIGN.label)
            progressTracker.currentStep = TX_SIGN
            val partSignedTx = serviceHub.signInitialTransaction(builder)

            myLog(INIT_SESSION.label)
            progressTracker.currentStep = INIT_SESSION
            val session = initiateFlow(counterparty)

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
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "MINT", ourIdentity)

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
