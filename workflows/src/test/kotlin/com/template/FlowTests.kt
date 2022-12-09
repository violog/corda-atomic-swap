package com.template

import com.template.contracts.BTC
import com.template.contracts.DASH
import com.template.flows.*
import com.template.states.Asset
import com.template.states.HTLC
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.flows.FlowLogic
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertEquals


class FlowTests {
    companion object {
        // this is not a functional way, again; I can convert it, but let it be so if I need to switch back to @BeforeEach
        private lateinit var network: MockNetwork
        private lateinit var a: StartedMockNode
        private lateinit var b: StartedMockNode

        private val StartedMockNode.party: Party get() = this.info.singleIdentity()
        private val StartedMockNode.name: String get() = this.party.name.organisation

        private fun myLog(msg: String) = println("[TEST] $msg")

        @BeforeClass
        @JvmStatic
        fun setup() {
            network = MockNetwork(
                MockNetworkParameters(
                    cordappsForAllNodes = listOf(
                        TestCordapp.findCordapp("com.template.contracts"),
                        TestCordapp.findCordapp("com.template.flows")
                    ),
                    notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Notary", "Kyiv", "UA"), false))
                )
            )
            a = network.createPartyNode(CordaX500Name("Alice", "London", "GB"))
            b = network.createPartyNode(CordaX500Name("Bob", "New York", "US"))
            network.runNetwork()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            network.stopNodes()
        }
    }

    @Test
    @Disabled("Coins are minted many times, no need to run separately")
    fun `happy mint flow of BTC and DASH`() {
        return // for some reason @Disabled didn't actually disable the test, so I did it myself
        /*
                val btc = runMint(BTC, 75000000).outputs.single().data
                myLog("minted coins: $btc")
                val dash = runMint(DASH, 420000000).outputs.single().data
                myLog("minted coins: $dash")
        */
    }

    @Test
    fun `happy burn flow of minted BTC`() {
        runMint(BTC, 4000000)
        val ltx = runFlow(a, BurnFlow.Initiator(b.party, BTC))
        myLog("Burned UTXO: ${ltx.inputStates}")
    }

    @Test
    fun `happy transfer flow of minted DASH`() {
        runMint(DASH, 130400000)
        val ltx = runFlow(a, TransferFlow.Initiator(b.party, DASH, 100400000))
        myLog("Transfer inputs: ${ltx.inputStates}")
        myLog("Transfer outputs: ${ltx.outputStates}")
    }

    @Test
    fun `happy lock, unlock and convert DASH before locktime`() {
        val amountToLock: Long = 2108400000
        val secret = "before_locktime"
        runMint(DASH, amountToLock)
        val lockTx = runLock(DASH, amountToLock, 10, HTLC.hash(secret))
        myLog("Lock input: ${lockTx.inputStates.single()}")
        myLog("Lock output: ${lockTx.outputStates.single()}")

        val htlcID = lockTx.outputsOfType<HTLC>().single().linearId
        val unlockTx = runUnlock(htlcID, secret)
        myLog("Unlock input: ${unlockTx.inputStates.single()}")
        myLog("Unlock output: ${unlockTx.outputStates.single()}")

        val convTx = runConvert(htlcID)
        myLog("Convert input: ${convTx.inputStates.single()}")
        myLog("Convert output: ${convTx.outputStates.single()}")
    }

    @Test
    fun `happy lock and convert BTC after locktime`() {
        val amountToLock: Long = 81000000
        val lockSecretHash = HTLC.hash("after_locktime")
        val lockDuration = 2
        runMint(BTC, amountToLock)
        val lockTx = runLock(BTC, amountToLock, lockDuration, lockSecretHash)
        myLog("lock inputs: ${lockTx.inputStates}")
        myLog("lock outputs: ${lockTx.outputStates}")
        myLog("sleeping to wait for reaching locktime...")
        Thread.sleep((lockDuration * 1000).toLong())

        val htlcID = lockTx.outputsOfType<HTLC>().single().linearId
        val convTx = runFlow(a, ConvertFlow.Initiator(b.party, htlcID))
        myLog("convert inputs: ${convTx.inputStates}")
        myLog("convert outputs: ${convTx.outputStates}")
    }

    @Test
    fun `happy swap of BTC and DASH`() {
        val bitcoinAmount: Long = 45000000
        val dashAmount: Long = 12900000000
        val initiatorSecret = "swap_btc_dash" // we assume that responder doesn't know it
        val secretHash = HTLC.hash(initiatorSecret)

        val mintTx1 = runMint(BTC, bitcoinAmount)
        val mintTx2 = runFlow(b, MintFlow.Initiator(a.party, DASH, dashAmount))
        myLog("Parties have minted coins:\n${mintTx1.outputStates.single()}\n${mintTx2.outputStates.single()}")

        val lockTx1 = runLock(BTC, bitcoinAmount, 60, secretHash)
//        myLog("Locked coins: ${lockTx1.outputStates.single()}")
        // Parties should check that everything is correct before locking his coins, I'll skip it and other checks for now
        val lockTx2 =
            runFlow(b, LockFlow.Initiator(a.party, DASH, dashAmount, 50, secretHash))
        myLog("Parties have locked their coins:\n${lockTx1.outputStates.single()}\n${lockTx2.outputStates.single()}")
        val linearID1 = lockTx1.outputsOfType<HTLC>().single().linearId
        val linearID2 = lockTx2.outputsOfType<HTLC>().single().linearId

        val unlockTx1 = runFlow(a, UnlockFlow.Initiator(b.party, linearID2, initiatorSecret))
        // Party 2 can get the secret from its vault; however, it can also reject tx, get the secret and try to unlock
        // initiator's coins; think about it
        val unlockOut1 = unlockTx1.outputsOfType<HTLC>().single()
        myLog("${a.name} has unlocked the coins: $unlockOut1")
        assertEquals(initiatorSecret, unlockOut1.secret)
        val unlockTx2 = runFlow(b, UnlockFlow.Initiator(a.party, linearID1, unlockOut1.secret!!))
        myLog("${b.name} has unlocked the coins: ${unlockTx2.outputStates.single()}")

        val convTx1 = runFlow(a, ConvertFlow.Initiator(b.party, linearID2))
        val convTx2 = runFlow(b, ConvertFlow.Initiator(a.party, linearID1))
        myLog("Parties have converted their coins:\n${convTx1.outputStates.single()}\n${convTx2.outputStates.single()}")
    }

    //    private fun runMint(asset: Asset, amount: Long, initiator: StartedMockNode = a, responder: StartedMockNode = b): LedgerTransaction =
//        runFlow(initiator, MintFlow.Initiator(responder.party, asset, amount))
    // hard to implement, it is easier without this
    private fun runMint(asset: Asset, amount: Long): LedgerTransaction =
        runFlow(a, MintFlow.Initiator(b.party, asset, amount))

    private fun runLock(asset: Asset, amount: Long, duration: Int, hash: String): LedgerTransaction =
        runFlow(a, LockFlow.Initiator(b.party, asset, amount, duration, hash))

    private fun runUnlock(linearId: UniqueIdentifier, secret: String): LedgerTransaction =
        runFlow(b, UnlockFlow.Initiator(a.party, linearId, secret))

    private fun runConvert(linearId: UniqueIdentifier): LedgerTransaction =
        runFlow(b, ConvertFlow.Initiator(a.party, linearId))

    private fun runFlow(initiator: StartedMockNode, flow: FlowLogic<SignedTransaction>): LedgerTransaction {
        val future = initiator.startFlow(flow)
        network.runNetwork()
        val stx = future.getOrThrow()
        checkTx(stx)
        return stx.toLedgerTransaction(a.services, true)
    }

    private fun checkTx(stx: SignedTransaction) {
        // There are very many things to assert. I'll evaluate some of them, but no exceptions means that test is passed.
        // flow records a transaction in both parties' transaction storages
        assertEquals(stx, a.services.validatedTransactions.getTransaction(stx.id))
        assertEquals(stx, b.services.validatedTransactions.getTransaction(stx.id))
        // SignedTransaction returned by the flow is signed by all parties
        stx.verifyRequiredSignatures()
        // check that states were updated correctly
        // damn, I need queryable state for this, I'll skip it for now
        // listOf(a, b).forEach{
        // val states=it.services.vaultService.queryBy<UTXO>().states
        // }
    }
}
