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
import java.time.Instant

object ConvertFlow {
    private const val flowLabel = "CONVERT"

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val linearId: UniqueIdentifier
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

            val htlc = inputs.single().state.data
            if (!htlc.isSecretValid() && Instant.now().epochSecond < htlc.locktime)
                myLog("Warning: input has invalid or unset unlocking secret and locktime is not reached. Transaction will fail for sure. You need to run unlock flow instead. Input: $htlc")
            val output = UTXO(ourIdentity, htlc.asset, htlc.amount, htlc.participants)

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Convert(), ourIdentity.owningKey, counterparty.owningKey)
                .addInputState(inputs.single())
                .addOutputState(output)

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
                    // this should be enough, same in Unlock.kt
                    "Only the flow initiator can convert his unlocked coins" using (counterpartySession.counterparty == utxo.owner)
                }
            }

            myLog("Responder flow initiated")
            val txId = subFlow(signTransactionFlow).id
            myLog(TX_FINALIZE.label)
            return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
        }
    }

}
