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
//    val secretHash: String = if(secret!=null) hash(secret) else throw Exception("must be set up"), // govnina
    val secretHash: String,
    private val lockDuration: Int,
    override val linearId: UniqueIdentifier = UniqueIdentifier(),
    override val participants: List<AbstractParty> = listOf(sender, receiver)
) : LinearState, FungibleAsset {
    // Linear state is the best way: I need to know only a single unique field to unlock funds
    // This is simpler than using multiple fields to filter by
    val locktime: Long = Instant.now().epochSecond + lockDuration

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
            "Duration must be positive" using (lockDuration > 0)
            "If secret is given, it must correspond the hash" using (secret == null || HTLC.hash(secret) == secretHash)
            // It won't work, dummy, because you call copy() and it will validate everything again
//            "Secret must be non-null; to delete it after hashing, call withoutSecret" using (secret != null)
//            "Secret length must be >= $MIN_SECRET_LENGTH" using (secret!!.length >= MIN_SECRET_LENGTH)
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

    fun toStateAndRef(notary: Party, constraint: AttachmentConstraint, ref: StateRef): StateAndRef<HTLC> =
        StateAndRef(TransactionState(this, UTXOContract.ID, notary, null, constraint), ref)

    override fun toString(): String =
        "\"${sender.org}\" locked ${amount.toFloat() / 10f.pow(asset.decimals)} of $asset for " +
                "\"${receiver.org}\" due to ${Instant.ofEpochSecond(locktime)} (duration=${lockDuration}s) with " +
                "secret=${if (secret != null) "\"$secret\"" else null} and hash=$secretHash"
}
