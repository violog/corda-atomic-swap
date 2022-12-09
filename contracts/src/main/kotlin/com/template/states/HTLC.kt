package com.template.states

import com.template.contracts.UTXOContract
import net.corda.core.contracts.*
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import java.security.MessageDigest
import java.time.Instant
import java.util.*
import kotlin.math.pow

@BelongsToContract(UTXOContract::class)
data class HTLC(
    val sender: Party,
    val receiver: Party,
    override val asset: Asset,
    override val amount: Int,
    val secret: String?,
    val secretHash: String,
    val locktime: Long,
    override val linearId: UniqueIdentifier = UniqueIdentifier(),
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : LinearState, FungibleAsset {
    // Linear state is the best way: I need to know only a single unique field to unlock funds
    // This is simpler than using multiple fields to filter by

    companion object {
        fun hash(msg: String): String =
            MessageDigest.getInstance("SHA-256").digest(msg.toByteArray())
                .fold(StringBuilder()) { sb, it -> sb.append("%02x".format(it)) }
                .toString()

        internal val Party.org: String get() = this.name.organisation
    }

    init {
        requireThat {
            "Too few participants: given ${participants.size}, minimum is 2" using (participants.size >= 2)
            "Must be valid SHA-256 hash of secret" using (secretHash.matches(Regex("[0-9a-f]{64}")))
            "If secret is given, it must correspond the hash" using (secret == null || HTLC.hash(secret) == secretHash)
        }
    }

    fun withParticipants(p: List<AbstractParty>): HTLC {
        return copy(participants = participants + p)
    }

    fun withSecret(secret: String): HTLC {
        return copy(secret = secret)
    }

    fun withoutSecret(): HTLC {
        return copy(secret = null)
    }

    fun isSecretValid() = secret != null && HTLC.hash(secret) == secretHash

    override fun toString(): String {
        val vl = amount.toFloat() / 10f.pow(asset.decimals)
        val ltf = Instant.ofEpochSecond(locktime)
        val now = Instant.now()
        val part = "locker=${sender.org} unlocker=${receiver.org} value=$vl$asset locktime=$ltf secret_hash=$secretHash"
        if (isSecretValid()) {
            return "$part unlocked_by=SECRET secret=\"$secret\""
        }
        if (now.epochSecond >= locktime) {
            return "$part unlocked_by=LOCKTIME current_time=$now"
        }
        return part
    }
}
