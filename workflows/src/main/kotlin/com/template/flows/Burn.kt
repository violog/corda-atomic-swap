package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import com.template.contracts.UTXOContract
import com.template.states.Asset
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

// Now it burns all the initiator's coins, improve it later
object BurnFlow {
    @InitiatingFlow
    @StartableByRPC
    class Initiator(
        private val counterparty: Party,
        private val asset: Asset // try to do like in transfer, but choose minimal amount
    ) : FlowLogic<SignedTransaction>() {
        override val progressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray())
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "BURN", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            myLog(TX_BUILD.label)
            progressTracker.currentStep = TX_BUILD
            val notary = serviceHub.networkMapCache.notaryIdentities.single()

            val inputsCriteria = QueryCriteria.VaultQueryCriteria()
                .withStatus(Vault.StateStatus.UNCONSUMED)
                .withRelevancyStatus(Vault.RelevancyStatus.RELEVANT)
            val inputs = serviceHub.vaultService.queryBy<UTXO>(inputsCriteria).states
            // oh shit, it will burn all the UTXO on the network, if I don't check owner in builder
            // I can use queryable state to apply attribute's filters, can't I?
            //  Probably not, because not StateAndRef may be returned
            val myInputs = inputs
                .filter { it.state.data.owner == ourIdentity }
                .filter { it.state.data.asset == asset }
            if (myInputs.isEmpty())
                throw FlowException("No UTXO of $asset is owned by the party $ourIdentity")
            myLog("found ${inputs.size} UTXO of $asset, ${myInputs.size} of them is owned by the party $ourIdentity")

            val builder = TransactionBuilder(notary)
                .addCommand(UTXOContract.Commands.Burn(), ourIdentity.owningKey, counterparty.owningKey)
            // builder has MutableList, this is not the functional way :(
            myInputs.forEach { builder.addInputState(it) }

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
        private fun myLog(msg: String) = WRAPPED_LOG(msg, "BURN", ourIdentity)

        @Suspendable
        override fun call(): SignedTransaction {
            val signTransactionFlow = object : SignTransactionFlow(counterpartySession) {
                override fun checkTransaction(stx: SignedTransaction) = requireThat {
                    myLog(TX_ADD_CHECKS.label)
                    val inputs = stx.toLedgerTransaction(serviceHub, false).inputStates
                    "Inputs must be UTXO state reference" using (inputs.all { it is UTXO })
                    "Coins can only be burned by their owner" using
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
