package com.r3.corda.sdk.token.workflow.flows.finality

import co.paralleluniverse.fibers.Suspendable
import com.r3.corda.sdk.token.contracts.commands.RedeemTokenCommand
import com.r3.corda.sdk.token.workflow.flows.internal.finality.TransactionRole
import com.r3.corda.sdk.token.workflow.utilities.ourSigningKeys
import com.r3.corda.sdk.token.workflow.utilities.participants
import com.r3.corda.sdk.token.workflow.utilities.requireSessionsForParticipants
import com.r3.corda.sdk.token.workflow.utilities.toWellKnownParties
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder

/**
 * This flow is a wrapper around [FinalityFlow] and properly handles broadcasting transactions to observers (those which
 * are not transaction participants) by amending the [StatesToRecord] level based upon the role. Those which are not
 * participants in any of the states must invoke [FinalityFlow] with [StatesToRecord] set to ALL_VISIBLE, otherwise they
 * will not store any of the states. Those which are participants record the transaction as usual. This does mean that
 * there is an "all or nothing" approach to storing outputs for observers, so if there are privacy concerns, then it is
 * best to split state issuance up for different token holders in separate flow invocations.
 *
 * @property transactionBuilder the transaction builder to finalise
 * @property signedTransaction TODO
 * @property allSessions a set of sessions for, at least, all the transaction participants and maybe observers
 */
class ObserverAwareFinalityFlow private constructor (
        val allSessions: List<FlowSession>,
        val signedTransaction: SignedTransaction? = null,
        val transactionBuilder: TransactionBuilder? = null
) : FlowLogic<SignedTransaction>() {
    constructor(transactionBuilder: TransactionBuilder, allSessions: List<FlowSession>): this(allSessions, null, transactionBuilder)
    constructor(signedTransaction: SignedTransaction, allSessions: List<FlowSession>): this(allSessions, signedTransaction)

    @Suspendable
    override fun call(): SignedTransaction {
        // Check there is a session for each participant, apart from the node itself.
        val ledgerTransaction: LedgerTransaction = transactionBuilder?.toLedgerTransaction(serviceHub) ?: signedTransaction!!.toLedgerTransaction(serviceHub, false)
        val participants: List<AbstractParty> = ledgerTransaction.participants
        val issuers: Set<Party> = ledgerTransaction.commands.filterIsInstance<RedeemTokenCommand<*>>().map { it.token.issuer }.toSet()
        val wellKnownParticipantsAndIssuers: Set<Party> = participants.toWellKnownParties(serviceHub).toSet() + issuers
        val wellKnownParticipantsApartFromUs: Set<Party> = wellKnownParticipantsAndIssuers - ourIdentity
        // We need participantSessions for all participants apart from us.
        requireSessionsForParticipants(wellKnownParticipantsApartFromUs, allSessions)
        val finalSessions = allSessions.filter { it.counterparty != ourIdentity }
        // Notify all session counterparties of their role. Observers store the transaction using
        // StatesToRecord.ALL_VISIBLE, participants store the transaction using StatesToRecord.ONLY_RELEVANT.
        finalSessions.forEach { session ->
            if (session.counterparty in wellKnownParticipantsAndIssuers) session.send(TransactionRole.PARTICIPANT)
            else session.send(TransactionRole.OBSERVER)
        }
        // Sign and finalise the transaction, obtaining the signing keys required from the LedgerTransaction.
        val ourSigningKeys = ledgerTransaction.ourSigningKeys(serviceHub)
        val stx = transactionBuilder?.let {
            serviceHub.signInitialTransaction(it, signingPubKeys = ourSigningKeys)
        } ?: signedTransaction!! // TODO get rid of null !!
        return subFlow(FinalityFlow(transaction = stx, sessions = finalSessions))
    }
}