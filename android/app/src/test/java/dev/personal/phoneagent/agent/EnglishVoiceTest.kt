package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

/** What the en-US recogniser writes has to reach the same safe paths as an Azerbaijani request. */
class EnglishVoiceTest {
    private fun spoken(text: String) = SpokenEnglish.normalize(text)

    @Test fun numberWordsBecomeDigitsForAmountsAndTimes() {
        assertEquals("send 5 manat to Sara",spoken("send five manat to Sara"))
        assertEquals("send 25 manat",spoken("send twenty-five manat"))
        assertEquals("send 150 manat",spoken("send one hundred and fifty manat"))
        assertEquals("send 100 manat",spoken("send a hundred manat"))
        assertEquals("send 2000 azn",spoken("send two thousand azn"))
        assertEquals("call at 5 pm tomorrow",spoken("call at five p.m. tomorrow"))
        assertEquals("20",spoken("twenty"))
    }
    @Test fun recogniserFormattingIsTidied() {
        assertEquals("send 20 dollars",spoken("send $20"))
        assertEquals("send 1500 dollars",spoken("send $1,500"))
        assertEquals("send 1000 manat",spoken("send 1,000 manat"))
        assertEquals("call at 5 pm tomorrow",spoken("call at 5 p.m. tomorrow"))
        assertEquals("call at 5 am",spoken("call at 5 a.m."))
    }
    @Test fun ambiguousNumbersAreNeverGuessedIntoAmounts() {
        assertEquals("send twelve fifty manat",spoken("send twelve fifty manat"))
        assertEquals(StepKind.AGENT,RequestPlanner.plan(spoken("send twelve fifty manat")).single().kind)
    }
    @Test fun ordinaryWordsAndAzerbaijaniAreLeftAlone() {
        listOf("tell Sara no one is home","look at one of them","Anama 12,50 manat göndər","Salam, necəsən?","birigün saat 9:30 dərmanımı xatırlat")
            .forEach { assertEquals(it,spoken(it)) }
    }

    @Test fun myGovLookAlikesAreCorrected() {
        assertEquals("Ask myGov how to renew my passport",spoken("Ask my gov how to renew my passport"))
        assertEquals("Ask myGov how to renew my passport",spoken("Ask my golf how to renew my passport"))
        assertEquals("check my pension on myGov",spoken("check my pension on my gulf"))
        assertEquals("open myGov",spoken("open migov"))
        assertEquals("myGov pension",spoken("my guv pension"))
    }
    @Test fun realPhrasesAreNotMistakenForMyGov() {
        listOf("remind me to clean my golf clubs","tell Sara I am at my golf club","oh my god","tell Sara I lost my glove").forEach { assertEquals(it,spoken(it)) }
    }
    @Test fun aLaterGuessThatSaysMyGovWins() {
        assertEquals("ask my gov",SpokenEnglish.best(listOf("ask my golf","ask my gov")))
        assertEquals("send 5 manat",SpokenEnglish.best(listOf("send 5 manat","send five man at")))
        assertNull(SpokenEnglish.best(emptyList()))
    }

    @Test fun oneEnglishSentenceBecomesThreeServiceSteps() {
        val steps=RequestPlanner.plan(spoken("Send fifty manat to my mom and tell her on WhatsApp and remind me about the doctor tomorrow at five p.m."))
        assertEquals(listOf(StepKind.MONEY,StepKind.MESSAGE,StepKind.REMINDER),steps.map { it.kind })
        assertEquals("Mom",steps[0].recipient); assertEquals("50",steps[0].amount); assertEquals("AZN",steps[0].currency)
        assertEquals("Mom",steps[1].recipient); assertTrue(steps[1].dependsOnMoney)
        assertEquals("The doctor",steps[2].reminderTitle); assertEquals(1,steps[2].dayOffset); assertEquals("17:00",steps[2].time)
    }
    @Test fun englishNamesAreNotCutLikeAzerbaijaniDatives() {
        assertEquals("Sara",RequestPlanner.recipient("Send 20 manat to Sara"))
        assertEquals("Sara",RequestPlanner.recipient("Send a WhatsApp message to Sara"))
        assertEquals("Sara",RequestPlanner.recipient("Message sara on WhatsApp"))
        assertEquals("Ali",RequestPlanner.recipient("Tell Ali I am late"))
        assertEquals("",RequestPlanner.recipient("Please send money"))
    }
    @Test fun spokenMessageTextBecomesTheDraft() {
        assertEquals("I will be late",RequestPlanner.plan("Message Sara saying I will be late").single().draft)
        assertEquals("I am coming",RequestPlanner.plan("Tell Sara that I am coming").single().draft)
    }
    @Test fun andOnlySplitsBeforeANewAction() {
        val s=RequestPlanner.plan("Remind me to buy bread and milk at 9 am").single()
        assertEquals("Buy bread and milk",s.reminderTitle); assertEquals("09:00",s.time)
    }
    @Test fun aReminderAboutMoneyIsNotAPayment() {
        val s=RequestPlanner.plan("Remind me to pay 20 manat tomorrow").single()
        assertEquals(StepKind.REMINDER,s.kind); assertEquals("",s.amount); assertEquals(1,s.dayOffset)
    }
    @Test fun englishDaysAndClockTimes() {
        assertEquals(2,RequestPlanner.plan("Remind me to call Ali the day after tomorrow").single().dayOffset)
        assertEquals("12:00",RequestPlanner.plan("Remind me to eat at 12 pm").single().time)
        assertEquals("00:00",RequestPlanner.plan("Remind me to sleep at 12 am").single().time)
        assertEquals("17:30",RequestPlanner.plan("Remind me to eat at 5:30 pm").single().time)
        assertEquals("",RequestPlanner.plan("Remind me to eat at 13 pm").single().time)
        assertEquals("Reminder",RequestPlanner.plan("Remind me tomorrow").single().reminderTitle)
    }

    @Test fun cardTransferInEnglish() {
        val r=TransferRequest.parse("Take the card from Metin's WhatsApp message and send 20 manat from ABB")!!
        assertEquals("Metin",r.contactWord); assertEquals("Metin",r.contactGuess); assertEquals("20",r.amount)
        // A name taken from "'s" has no case ending, so the stem guess must not shorten it.
        assertEquals("Martin",TransferRequest.parse("Get the card from Martin's message and send 5 azn with ABB")!!.contactGuess)
        assertEquals("Metin",TransferRequest.parse("Take the card Metin sent on WhatsApp and pay 5 manat with ABB")!!.contactWord)
        assertEquals("",TransferRequest.parse("Use the card from my WhatsApp message and send 5 azn with ABB")!!.contactWord)
        assertFalse(TransferRequest.matches("Send 5 manat to Sara on WhatsApp"))
    }

    @Test fun documentShareInEnglish() {
        val passport="Download my passport from myGov and send it to Rahman on WhatsApp"
        assertTrue(PassportShare.matches(passport)); assertEquals(GovDoc.PASSPORT,PassportShare.document(passport)); assertEquals("Rahman",PassportShare.recipient(passport))
        assertEquals("Sara",PassportShare.recipient("Send my ID card to Sara"))
        assertEquals(GovDoc.ID_CARD,PassportShare.document("Share my identity card with my mom on WhatsApp"))
        assertEquals("Mom",PassportShare.recipient("Share my identity card with my mom on WhatsApp"))
        assertEquals("",PassportShare.recipient("Share my passport on WhatsApp"))
    }

    @Test fun ownMyGovDataInEnglishStaysOnThePhoneRoute() {
        assertEquals(GovIntent.READ,GovIntentRules.classify("Show my pension capital"))
        assertEquals(GovIntent.READ,GovIntentRules.classify("Check my traffic fines"))
        assertEquals("pension",GovRoutes.find("Show my pension capital",GovIntent.READ)?.id)
        assertEquals("fines",GovRoutes.find("Check my traffic fines",GovIntent.READ)?.id)
        assertEquals("certificate",GovRoutes.find("Order a certificate",GovIntent.ACTION)?.id)
    }
}
