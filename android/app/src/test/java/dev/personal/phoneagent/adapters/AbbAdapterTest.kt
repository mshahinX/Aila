package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*
import org.junit.Assert.*
import org.junit.Test

class AbbAdapterTest {
    private fun s(vararg nodes: UiNode) = UiSnapshot(packageName = ABB, screen = "manual", nodes = nodes.toList())
    private fun p(s: UiSnapshot, type: String, target: String, text: String = "") = Proposal("a", s.snapshotId, type, target, text)
    private val ctx = TaskContext("Nömrəmə 1 manat yüklə", mode = "live")
    @Test fun navigationAndFormFillAllowed() {
        val snap = s(UiNode("0.1", "$ABB:id/top_up", "Balans artır", clickable = true), UiNode("0.2", text = "", className = "android.widget.EditText", editable = true, clickable = true))
        assertEquals(Decision.Allow, ActionPolicy().assess(snap, p(snap, "click", "0.1"), ctx))
        assertEquals(Decision.Allow, ActionPolicy().assess(snap, p(snap, "set_text", "0.2", "1"), ctx))
    }
    @Test fun finalConfirmNeverClicked() {
        listOf("Təsdiqlə", "Ödə", "Göndər", "Confirm", "Pay now", "Köçür").forEach { label ->
            val snap = s(UiNode("0.1", text = label, clickable = true))
            assertTrue(label, ActionPolicy().assess(snap, p(snap, "click", "0.1"), ctx) is Decision.Manual)
        }
        // Label held by a child text view of the clickable container.
        val nested = s(UiNode("0.1", clickable = true), UiNode("0.1.0", text = "Təsdiqlə"))
        assertTrue(ActionPolicy().assess(nested, p(nested, "click", "0.1"), ctx) is Decision.Manual)
    }
    @Test fun finalConfirmWithAmountNeverClicked() {
        // ABB can show the amount inside the final button (slide 17: "Köçürmə et • 1.00 ₼").
        listOf("Köçürmə et • 1.00 ₼", "Köçürmə et 1,00 ₼", "Köçürmə et · 20 AZN", "1.00 ₼ Köçürmə et", "Ödə 5 manat", "Göndər!", "Pay 12.50 AZN").forEach { label ->
            val snap = s(UiNode("0.1", text = label, clickable = true))
            assertTrue(label, ActionPolicy().assess(snap, p(snap, "click", "0.1"), ctx) is Decision.Manual)
        }
        // Verb and amount held by two child views of one clickable button.
        val nested = s(UiNode("0.1", clickable = true), UiNode("0.1.0", text = "Köçürmə et"), UiNode("0.1.1", text = "1.00 ₼"))
        assertTrue(ActionPolicy().assess(nested, p(nested, "click", "0.1"), ctx) is Decision.Manual)
    }
    @Test fun amountTilesAndTabsNotMistakenForFinal() {
        listOf("Köçürmələr", "Kartdan karta köçürmə", "4.97 ₼", "Göndərən").forEach { assertFalse(it, AbbAdapter.isFinal(it)) }
    }
    @Test fun transfersTabStillNavigable() {
        val snap = s(UiNode("0.1", text = "Köçürmələr", clickable = true))
        assertEquals(Decision.Allow, ActionPolicy().assess(snap, p(snap, "click", "0.1"), ctx))
    }
    @Test fun noCardNumbersOrCredentialsTyped() {
        val snap = s(UiNode("0.2", className = "android.widget.EditText", editable = true))
        assertTrue(ActionPolicy().assess(snap, p(snap, "set_text", "0.2", "4169738812345678"), ctx) is Decision.Manual)
        assertTrue(ActionPolicy().assess(snap, p(snap, "set_text", "0.2", "PIN 1234"), ctx) is Decision.Manual)
    }
}
