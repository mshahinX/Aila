package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class PlannerTest {
    private val pitch="Anama 50 manat göndərmək istəyirəm, ona WhatsApp-da xəbər ver və sabah həkim qəbulumu xatırlat."

    @Test fun pitchSentenceBecomesThreeServiceSteps() {
        val steps=RequestPlanner.plan(pitch)
        assertEquals(listOf(StepKind.MONEY,StepKind.MESSAGE,StepKind.REMINDER),steps.map { it.kind })
        assertEquals("Anam",steps[0].recipient); assertEquals("50",steps[0].amount); assertEquals("AZN",steps[0].currency)
        assertEquals("Anam",steps[1].recipient)
        assertEquals("Salam, sənə 50 manat göndərdim.",steps[1].draft)
        assertTrue(steps[1].dependsOnMoney)
        assertEquals("Həkim qəbulu",steps[2].reminderTitle); assertEquals(1,steps[2].dayOffset); assertEquals("",steps[2].time)
    }
    @Test fun dativeNamesAreStripped() {
        assertEquals("Əli",RequestPlanner.recipient("Əliyə 20 manat göndər"))
        assertEquals("Leyla",RequestPlanner.recipient("Leylaya yaz"))
        assertEquals("Qardaşım",RequestPlanner.recipient("Qardaşıma 10 AZN köçür"))
        assertEquals("",RequestPlanner.recipient("ona WhatsApp-da xəbər ver"))
    }
    @Test fun reminderTimeAndDayParsed() {
        val s=RequestPlanner.plan("birigün saat 9:30 dərmanımı xatırlat").single()
        assertEquals(StepKind.REMINDER,s.kind); assertEquals(2,s.dayOffset); assertEquals("09:30",s.time)
    }
    @Test fun decimalAmountIsNotSplit() {
        val s=RequestPlanner.plan("Anama 12,50 manat göndər").single()
        assertEquals("12.50",s.amount)
    }
    @Test fun explicitMessageTextIsKeptAndIndependentOfMoney() {
        val steps=RequestPlanner.plan("Qızıma 5 manat göndər və ona yaz ki axşam gəlirəm")
        assertEquals("axşam gəlirəm",steps[1].draft); assertFalse(steps[1].dependsOnMoney)
    }
    @Test fun unknownRequestStaysOneAgentStep() {
        val steps=RequestPlanner.plan("Hava necədir")
        assertEquals(StepKind.AGENT,steps.single().kind); assertFalse(RequestPlanner.isServicePlan(steps))
    }
    @Test fun messageSkippedWhenMoneyNotSent() {
        val run=PlanRun(RequestPlanner.plan(pitch)).apply { start() }
        assertEquals(StepKind.MONEY,run.advance()!!.kind); run.set(StepState.NOT_DONE)
        assertEquals(StepKind.REMINDER,run.advance()!!.kind)
        assertEquals(StepState.SKIPPED,run.states[1])
    }
    @Test fun messageRunsWhenUserConfirmsMoney() {
        val run=PlanRun(RequestPlanner.plan(pitch)).apply { start() }
        run.advance(); run.set(StepState.USER_DONE)
        assertEquals(StepKind.MESSAGE,run.advance()!!.kind)
    }
    @Test fun stopSkipsEverythingUnfinished() {
        val run=PlanRun(RequestPlanner.plan(pitch)).apply { start() }
        run.advance(); run.set(StepState.USER_DONE); run.advance(); run.cancelRemaining()
        assertTrue(run.finished)
        assertEquals(listOf(StepState.USER_DONE,StepState.SKIPPED,StepState.SKIPPED),run.states)
    }
    @Test fun plannerNeverPutsCardNumbersIntoSteps() {
        // Identifier screening still happens in the coordinator; planner just must not invent amounts from them.
        val s=RequestPlanner.plan("4169 7388 1234 5678 kartına pul göndər").single()
        assertEquals(StepKind.MONEY,s.kind); assertEquals("",s.amount)
    }
    @Test fun matinIsFoundHoweverItIsTyped() {
        listOf("Matinə WhatsApp-da yaz ki salam","Mətinə yaz ki sabah gəlirəm","Matine yaz ki salam","Matin-ə mesaj yaz ki salam","matinə whatsappda salam yaz").forEach { request ->
            val step=RequestPlanner.plan(request).single()
            assertEquals(request,StepKind.MESSAGE,step.kind)
            assertTrue(request,step.recipient.equals("Matin",true) || step.recipient=="Mətin")
        }
        assertEquals("salam",RequestPlanner.plan("Matinə WhatsApp-da yaz ki salam").single().draft)
    }
}
