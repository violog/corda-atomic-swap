package com.template

import com.template.contracts.BTC
import com.template.contracts.DASH
import com.template.flows.BurnFlow
import com.template.flows.MintFlow
import com.template.flows.TransferFlow
import com.template.states.Asset
import com.template.states.UTXO
import net.corda.core.identity.CordaX500Name
import net.corda.core.transactions.SignedTransaction
import net.corda.core.utilities.getOrThrow
import net.corda.testing.core.singleIdentity
import net.corda.testing.node.*
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals


class FlowTests {
    companion object {
        // this is not a functional way, again; I can convert it, but let it be so if I need to switch back to @BeforeEach
        private lateinit var network: MockNetwork
        private lateinit var a: StartedMockNode
        private lateinit var b: StartedMockNode
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
                    notarySpecs = listOf(MockNetworkNotarySpec(CordaX500Name("Revan", "Star Forge", "GB")))
                )
            )
            // This shit doesn't allow custom countries
            a = network.createPartyNode(CordaX500Name("T3-M4", "Dromund-Kaas", "US"))
            b = network.createPartyNode(CordaX500Name("HK-47", "Mandalor", "GB"))
//            a = network.createPartyNode(CordaX500Name("Alice", "New Your", "US"))
//            b = network.createPartyNode(CordaX500Name("Bob", "London", "GB"))
            network.runNetwork()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            network.stopNodes()
        }
    }

    @Test
    fun `happy mint flow of BTC and DASH`() {
        val btc = runMint(BTC, 75000000)
            .toLedgerTransaction(a.services, true)
            .outputs.single().data
        myLog("minted coins: $btc")
        val dash = runMint(DASH, 420000000)
            .toLedgerTransaction(a.services, true)
            .outputs.single().data
        myLog("minted coins: $dash")
    }

    @Test
    fun `happy burn flow of minted BTC`() {
        runMint(BTC, 4000000)
        val flow = BurnFlow.Initiator(b.info.singleIdentity(), BTC)
        val future = a.startFlow(flow)
        network.runNetwork()
        val stx = future.getOrThrow()
        checkTx(stx)
        val burned = stx.toLedgerTransaction(a.services, true).inputStates
        myLog("burned coins: $burned")
    }

    @Test
    fun `happy transfer flow of minted DASH`() {
        runMint(DASH, 130400000)
        val flow = TransferFlow.Initiator(b.info.singleIdentity(), DASH, 100400000)
        val future = a.startFlow(flow)
        network.runNetwork()
        val stx = future.getOrThrow()
        checkTx(stx)

        val inputs = stx.toLedgerTransaction(a.services, true).inputStates
        myLog("transfer inputs: $inputs")
        val outputs = stx.toLedgerTransaction(a.services, true).outputsOfType<UTXO>()
        myLog("transfer outputs: $outputs")
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

    private fun runMint(asset: Asset, amount: Int): SignedTransaction {
        val flow = MintFlow.Initiator(b.info.singleIdentity(), asset, amount)
        val futureMint = a.startFlow(flow)
        network.runNetwork()
        val stx = futureMint.getOrThrow()
        checkTx(stx)
        return stx
    }
}
