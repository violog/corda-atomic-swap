package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.HTLC
import com.template.states.UTXO
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.*
import net.corda.core.identity.*
import net.corda.core.node.services.Vault
import net.corda.core.node.services.queryBy
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

object UnlockFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val linearId: UniqueIdentifier,
        private val secret: String
    ) : FlowLogic<SignedTransaction>() {
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "UNLOCK", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(TX_BUILD.label)
            progressTracker.currentStep = TX_BUILD
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputsCriteria = QueryCriteria.LinearStateQueryCriteria()
                .withUuid(listOf(linearId.id))
                .withStatus(Vault.StateStatus.UNCONSUMED)
                .withRelevancyStatus(Vault.RelevancyStatus.RELEVANT)
            val inputs = serviceHub.vaultService.queryBy<HTLC>(inputsCriteria).states
            if (inputs.size != 1)
                throw FlowException("Expected to get one HTLC state by UUID, found ${inputs.size}")
            val htlc = inputs.single().state.data
            // todo: give the wrong secret and see what happens
            val withSecret = htlc.withSecret(secret)
                .toStateAndRef(notary, inputs.single().state.constraint, inputs.single().ref)
            // oh, no, it probably won't work, because the state won't match ref; let's check:
            // if it matches, then I need to validate everything in the responder flow as well
            val output = UTXO(ourIdentity, htlc.asset, htlc.amount, listOf(counterparty, ourIdentity))

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Unlock(), ourIdentity.owningKey, htlc.sender.owningKey)
                .addInputState(withSecret)
                .addOutputState(output, UTXOContract.ID)

            myLog(TX_SIGN.label)
            progressTracker.currentStep = TX_SIGN
            val partSignedTx = serviceHub.signInitialTransaction(builder)

            myLog(INIT_SESSION.label)
            progressTracker.currentStep = INIT_SESSION
            val session = initiateFlow(htlc.sender)

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
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "UNLOCK", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    myLog(TX_ADD_CHECKS.label)
                    val ltx = stx.toLedgerTransaction(serviceHub, false)
                    val inputs = ltx.inputs.map { it.state.data }
                    val outputs = ltx.outputs.map { it.data }

                    "Inputs must be HTLC state references" using (inputs.all { it is HTLC })
                    "Outputs must be UTXO states" using (outputs.all { it is UTXO })
//                    val htlc = inputs.single() as HTLC
                    val utxo = outputs.single() as UTXO
//                    val now = Instant.now().epochSecond
//                    "Before locktime, unlocking transaction can only be initiated by the intended receiver" using
//                            (now < htlc.locktime && counterpartySession.counterparty == htlc.receiver)
//                    "After locktime, unlocking transaction can only be initiated by the sender" using
//                            (now >= htlc.locktime && counterpartySession.counterparty == htlc.sender)
                    // this should be enough
                    "Only the flow initiator can unlock his coins" using (counterpartySession.counterparty == utxo.owner)
                }
            }

            myLog("Responder flow initiated")
            val txId = subFlow(signTransactionFlow).id
            myLog(TX_FINALIZE.label)
            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }

}