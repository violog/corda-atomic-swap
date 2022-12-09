package com.template.flows

import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker.Step

internal object START_FLOW : Step("Flow started")
internal object TX_SIGN : Step("Signing transaction")
internal object INIT_SESSION : Step("Initializing session with counterparty")
internal object TX_COLLECTSIG : Step("Requesting signature from counterparty") {
    override fun childProgressTracker() = CollectSignaturesFlow.tracker()
}

internal object TX_FINALIZE : Step("Transaction finalization") {
    override fun childProgressTracker() = FinalityFlow.tracker()
}

internal object TX_ADD_CHECKS : Step("Performing additional transaction checks before signing it")

internal val BASIC_STEPS = listOf(START_FLOW, TX_SIGN, INIT_SESSION, TX_COLLECTSIG, TX_FINALIZE)

internal val WRAPPED_LOG =
    { msg: String, flow: String, party: Party -> println("[FLOW:$flow] <${party.name.organisation}> $msg") }
