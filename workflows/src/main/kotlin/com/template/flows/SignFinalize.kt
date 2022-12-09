package com.template.flows

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker

class SignFinalizeFlow(
    private val counterparty: Party,
    private val builder: TransactionBuilder,
    private val logPrefix: String,
    override val progressTracker: ProgressTracker = ProgressTracker(*BASIC_STEPS.toTypedArray()) // will it work good?
) : FlowLogic<SignedTransaction>() {

    private fun nextStep(step: ProgressTracker.Step) {
        WRAPPED_LOG(step.label, logPrefix, ourIdentity)
        progressTracker.currentStep = step
    }

    @Suspendable
    override fun call(): SignedTransaction {
        // Tx is verified 3 times: on signing, collecting signatures and on finalization
        // However, flows execution is continued when tx verification fails on signing: errors are shown in log
        // Later it tries to collect signatures and throws exception on verification, stopping flow
        // Moreover, if I use builder.verify(serviceHub), flow stops at this function
        // I don't need to use this verifier, because I'm sure that my flow is good
        nextStep(TX_SIGN)
        val partSignedTx = serviceHub.signInitialTransaction(builder)
        nextStep(INIT_SESSION)
        val session = initiateFlow(counterparty)
        nextStep(TX_COLLECTSIG)
        val fullSignedTx =
            subFlow(CollectSignaturesFlow(partSignedTx, setOf(session), TX_COLLECTSIG.childProgressTracker()))
        nextStep(TX_FINALIZE)
        return subFlow(FinalityFlow(fullSignedTx, setOf(session), TX_FINALIZE.childProgressTracker()))
    }
}
