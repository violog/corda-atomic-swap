package com.template.flows

import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.identity.Party
import net.corda.core.utilities.ProgressTracker.Step
import java.security.MessageDigest

internal object TX_BUILD : Step("Building transaction")
internal object TX_SIGN : Step("Signing transaction")
internal object INIT_SESSION : Step("Initializing session with counterparty")
internal object TX_COLLECTSIG : Step("Requesting signature from counterparty") {
    override fun childProgressTracker() = CollectSignaturesFlow.tracker()
}

internal object TX_FINALIZE : Step("Transaction finalization") {
    override fun childProgressTracker() = FinalityFlow.tracker()
}

internal object TX_ADD_CHECKS : Step("Performing additional transaction checks before signing it")

internal val BASIC_STEPS = listOf(TX_BUILD, TX_SIGN, INIT_SESSION, TX_COLLECTSIG, TX_FINALIZE)

internal val WRAPPED_LOG =
    { msg: String, flow: String, party: Party -> println("[FLOW:$flow] <${party.name.organisation}> $msg") }

internal val HASH = { msg: String ->
    MessageDigest.getInstance("SHA-256")
        .digest(msg.toByteArray())
        .fold(StringBuilder()) { sb, it -> sb.append("%02x".format(it)) }
        .toString()
}