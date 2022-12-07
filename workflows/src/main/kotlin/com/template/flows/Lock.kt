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

object LockFlow {
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
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "LOCK", ourIdentity)

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

            val coinsToLock = myInputs.filter { it.state.data.amount == amount }
            if (coinsToLock.isEmpty())
                throw FlowException("No UTXO of $asset found with exact amount $amount, try to obtain it first")
            if (coinsToLock.size > 1)
                myLog("found ${coinsToLock.size} UTXO with exact amount $amount, using first")

            // todo: save secret! And add more steps to track progress, also in other places
            // To save secret in separate table, I need DB service.
            // For now I will only memorize the secret in my head. Later on I'll add that service.
            // Queryable state will be enough, it is required anyway.
            val output = HTLC(ourIdentity, receiver, asset, amount, null, HASH(secret), lockDuration)

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Lock(), ourIdentity.owningKey, receiver.owningKey)
                .addInputState(coinsToLock.first())
                .addOutputState(output, UTXOContract.ID)

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
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "LOCK", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
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
