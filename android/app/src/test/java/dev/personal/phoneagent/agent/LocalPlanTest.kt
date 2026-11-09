package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class LocalPlanTest {
    @Test fun abbQuestionGoesToItsChatbotWithTheQuestionAsFirstMessage() {
        val s = LocalPlan.single("ABB-dən soruş, balans artırmanın komissiyası var?")!!
        assertEquals(listOf("abb", "bot", "info"), listOf(s.app, s.channel, s.intent))
        assertEquals("balans artırmanın komissiyası var?", s.firstMessage)
    }
    @Test fun abbTopUpIsTheFormStepWithoutAChatMessage() {
        val s = LocalPlan.single("ABB ilə nömrəmə 1 manat yüklə")!!
        assertEquals(listOf("abb", "ui", "action", ""), listOf(s.app, s.channel, s.intent, s.firstMessage))
    }
    @Test fun myGovUsesThePhoneIntentRules() {
        assertEquals("read", LocalPlan.single("myGov-dan pensiya kapitalımı öyrən")!!.intent)
        val info = LocalPlan.single("myGov-dan soruş: pasport almaq üçün hansı sənəd lazımdır?")!!
        assertEquals(listOf("bot", "info"), listOf(info.channel, info.intent))
        assertEquals("pasport almaq üçün hansı sənəd lazımdır?", info.firstMessage)
        // "How do I renew it?" is a question and a request at once: the owner is asked, as with a cloud plan.
        assertEquals("unclear", LocalPlan.single("myGov-dan soruş: şəxsiyyət vəsiqəsini necə yeniləmək olar?")!!.intent)
    }
    @Test fun anythingWithASecondStepOrAPersonGoesToTheCloudPlan() {
        for (text in listOf(
            "ABB-dən soruş, balans artırmanın komissiyası var? Sonra nömrəmə 1 manat yüklə.",
            "myGov-dan şəxsiyyət vəsiqəsini necə yeniləyəcəyimi öyrən və cavabı Anama WhatsApp-da göndər.",
            "mygovdan ne qeder pensiya kapitali yigildigimi Metine de",
            "ABB və myGov-dan soruş",
            "ABB",                       // neither a question nor a payment
            "Azercell balansımı öyrən",  // no calibrated local path
            "myGov-da cərimə ödə",       // myGov payment: never local
        )) assertNull(text, LocalPlan.single(text))
    }
}
