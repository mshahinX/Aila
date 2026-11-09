package dev.personal.phoneagent.agent

import java.util.UUID

/** Local-only capability. Never serialized to the backend or accepted from model output. */
data class ApprovalTicket(
    val nonce: String, val taskId: String, val actionId: String, val fingerprint: String,
    val recipient: String, val exactText: String, val createdAt: Long
)
class ApprovalGate(private val clock: () -> Long = System::currentTimeMillis) {
    private var pending: ApprovalTicket? = null
    @Synchronized fun issue(taskId: String, s: UiSnapshot, p: Proposal, d: Decision.Approval): ApprovalTicket =
        ApprovalTicket(UUID.randomUUID().toString(), taskId, p.actionId, s.fingerprint,d.recipient,d.exactText,clock()).also { pending = it }
    @Synchronized fun consume(ticket: ApprovalTicket, taskId: String, s: UiSnapshot, p: Proposal): Boolean {
        val current = pending
        pending = null // Invalidate even on a failed check; never reusable.
        return current == ticket && ticket.taskId == taskId && ticket.actionId == p.actionId &&
            ticket.fingerprint == s.fingerprint && clock() - ticket.createdAt in 0..60_000
    }
    @Synchronized fun invalidate() { pending = null }
}
