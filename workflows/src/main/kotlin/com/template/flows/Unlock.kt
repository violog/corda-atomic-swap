package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.HTLC
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
    private const val flowLabel = "UNLOCK"

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val linearId: UniqueIdentifier,
        private val secret: String
    ) : FlowLogic<SignedTransaction>() {
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(START_FLOW.label)
            progressTracker.currentStep = START_FLOW
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputsCriteria = QueryCriteria.LinearStateQueryCriteria()
                .withUuid(listOf(linearId.id))
                .withRelevancyStatus(Vault.RelevancyStatus.RELEVANT)
            val inputs = serviceHub.vaultService.queryBy<HTLC>(inputsCriteria).states
            if (inputs.size != 1)
                throw FlowException("Expected to get one HTLC state by UUID, found ${inputs.size}")

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Unlock(), ourIdentity.owningKey, counterparty.owningKey)
                .addInputState(inputs.single())
                .addOutputState(inputs.single().state.data.withSecret(secret))

            return subFlow(SignFinalizeFlow(counterparty, builder, flowLabel, progressTracker))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    myLog(TX_ADD_CHECKS.label)
                    val ltx = stx.toLedgerTransaction(serviceHub, false)
                    val inputs = ltx.inputs.map { it.state.data }
                    "Inputs and outputs must be HTLC states" using (inputs.all { it is HTLC })
                    val htlc = inputs.single() as HTLC
                    "Only the flow initiator can unlock his coins" using (counterpartySession.counterparty == htlc.receiver)
                }
            }

            myLog("Responder flow initiated")
            val txId = subFlow(signTransactionFlow).id
            myLog(TX_FINALIZE.label)
            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }

}
