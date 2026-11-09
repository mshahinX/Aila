package dev.personal.phoneagent.agent

import dev.personal.phoneagent.adapters.AbbAdapter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FollowUpTest {
    private fun json(extra: Map<String, Any> = emptyMap()) = JSONObject(mapOf(
        "actionId" to "a", "snapshotId" to "s", "type" to "set_text", "targetId" to "0.1", "text" to "20",
        "packageName" to "", "question" to "", "message" to "", "direction" to "forward", "durationMs" to 0) + extra)

    @Test fun proposalAcceptsTheOptionalFollowUpOnlyWithTyping() {
        assertEquals("", Proposal.parse(json()).thenClickId)                       // older backend: no field
        assertEquals("0.2", Proposal.parse(json(mapOf("thenClickId" to "0.2"))).thenClickId)
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(json(mapOf("thenClickId" to "0.1"))) }
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(json(mapOf("type" to "click", "text" to "", "thenClickId" to "0.2"))) }
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(json(mapOf("unknown" to "x"))) }
    }

    private val field = UiNode("0.1", "$ABB:id/etAmountInput", "", editable = true, clickable = true)
    private val next = UiNode("0.2", "$ABB:id/btnNext", "Davam et", clickable = true)
    private val final = UiNode("0.3", "$ABB:id/btnMain", "Köçürmə et", clickable = true)
    private val before = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(field, next, final))

    @Test fun followUpTargetMustBeTheSameElementAfterTyping() {
        val typed = before.copy(snapshotId = "after", nodes = listOf(field.copy(text = "20"), next, final))
        assertEquals("0.2", sameFollowUpTarget(before, typed, "0.2")?.id)
        val moved = typed.copy(nodes = listOf(field.copy(text = "20"), next.copy(text = "Ləğv et"), final))
        assertNull(sameFollowUpTarget(before, moved, "0.2"))
        val disabled = typed.copy(nodes = listOf(field.copy(text = "20"), next.copy(enabled = false), final))
        assertNull(sameFollowUpTarget(before, disabled, "0.2"))
    }

    @Test fun policyNeverAllowsAFinalButtonAsAFollowUp() {
        val typed = before.copy(snapshotId = "after", nodes = listOf(field.copy(text = "20"), next, final))
        val task = TaskContext("ABB-də 20 manat köçür", mode = "live")
        val p = Proposal("a", before.snapshotId, "set_text", targetId = "0.1", text = "20", thenClickId = "0.3")
        assertTrue(ActionPolicy().assess(typed, p.followUp(typed), task) is Decision.Manual)
        assertTrue(AbbAdapter.isFinal("Köçürmə et"))
        val ok = p.copy(thenClickId = "0.2")
        assertEquals(Decision.Allow, ActionPolicy().assess(typed, ok.followUp(typed), task))
    }
}
