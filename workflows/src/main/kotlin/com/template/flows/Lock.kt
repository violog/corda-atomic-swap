package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.Asset
import com.template.states.HTLC
import com.template.states.UTXO
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

object LockFlow {
    private const val flowLabel = "LOCK"

    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val receiver: Party,
        private val asset: Asset,
        private val amount: Int,
        private val lockDuration: Int,
        private val secret: String
    ) : FlowLogic<SignedTransaction>() {
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(START_FLOW.label)
            progressTracker.currentStep = START_FLOW
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputsCriteria = QueryCriteria.VaultQueryCriteria()
                .withRelevancyStatus(Vault.RelevancyStatus.RELEVANT)
            val inputs = serviceHub.vaultService.queryBy<UTXO>(inputsCriteria).states

            val myInputs = inputs
                .filter { it.state.data.owner == ourIdentity }
                .filter { it.state.data.asset == asset }
            if (myInputs.isEmpty())
                throw FlowException("No UTXO found for party $ourIdentity")
            myLog("found ${inputs.size} UTXO of $asset, ${myInputs.size} of them is owned by the party $ourIdentity")

            val coinsToLock = myInputs.filter { it.state.data.amount == amount }
            if (coinsToLock.isEmpty())
                throw FlowException("No UTXO of $asset found with exact amount $amount, try to obtain it first")
            if (coinsToLock.size > 1)
                myLog("found ${coinsToLock.size} UTXO with exact amount $amount, using first")

            // TODO: save secret, add more steps to track progress, also in other places
            // To save secret in separate table, I need DB service.
            // For now I will only memorize the secret in my head. Later on I'll add that service.
            val locktime = Instant.now().epochSecond + lockDuration
            val output = HTLC(ourIdentity, receiver, asset, amount, null, HTLC.hash(secret), locktime)

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Lock(), ourIdentity.owningKey, receiver.owningKey)
                .addInputState(coinsToLock.first())
                .addOutputState(output, UTXOContract.ID)

            return subFlow(SignFinalizeFlow(receiver, builder, flowLabel, progressTracker))
        }
    }

    @InitiatedBy(Initiator::class)
    class Acceptor(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {
        private fun myLog(msg: String) = WRAPPED_LOG(msg, flowLabel, ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    // IDK if I should add more checks here or in another initiating flow
                    // If tx is contractually valid, I should agree it, but then in another flow I can refuse to lock funds.
                    // However, why should I do this if I can check all of it here? Looks like I can't pass args here,
                    // so I'll have to basically validate tx and only then perform my flow.
                    myLog(TX_ADD_CHECKS.label)
                    val ltx = stx.toLedgerTransaction(serviceHub, false)
                    val inputs = ltx.inputs.map { it.state.data }
                    val outputs = ltx.outputs.map { it.data }

                    "Inputs must be UTXO state references" using (inputs.all { it is UTXO })
                    "Outputs must be HTLC states" using (outputs.all { it is HTLC })
                    "Coins can only be locked by their owner" using
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
