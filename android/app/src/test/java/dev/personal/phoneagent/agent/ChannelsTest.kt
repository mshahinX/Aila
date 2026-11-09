package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class ChannelsTest {
    private fun n(id: String, text: String = "", res: String = "", desc: String = "", click: Boolean = false, edit: Boolean = false, bounds: String = "[0,0][10,10]") =
        UiNode(id, res, text, desc, clickable = click, editable = edit, bounds = bounds)

    // myGov AI chat as calibrated: Compose, no IDs, EditText with an unlabeled clickable send to its right.
    private val mygovChat = UiSnapshot(packageName = MYGOV, nodes = listOf(
        n("0.0.1", "Xoş gördük", bounds = "[234,982][847,1073]"),
        n("0.0.2", edit = true, click = true, bounds = "[88,1323][904,1455]"),
        n("0.0.3", click = true, bounds = "[893,1322][1025,1454]"),
        n("0.0.4", click = true, bounds = "[39,109][171,241]")))

    @Test fun mygovSendIsTheControlRightOfInput() {
        val input = BotLocator.input(mygovChat)!!
        assertEquals("0.0.2", input.id)
        assertEquals("0.0.3", BotLocator.sendButton(mygovChat, BotApp.MYGOV_BOT, input)?.id)
    }
    @Test fun attachAndCameraAreNeverSend() {
        val s = UiSnapshot(packageName = MYGOV, nodes = listOf(
            n("0.1", edit = true, bounds = "[100,2000][700,2100]"),
            n("0.2", desc = "Attach", click = true, bounds = "[710,2000][800,2100]"),
            n("0.3", desc = "Camera", click = true, bounds = "[810,2000][900,2100]")))
        assertNull(BotLocator.sendButton(s, BotApp.MYGOV_BOT, s.nodes[0]))
    }
    @Test fun whatsappUsesCalibratedSendId() {
        val s = UiSnapshot(packageName = WHATSAPP, nodes = listOf(
            n("0.1", "hi", res = "$WHATSAPP:id/entry", edit = true, bounds = "[100,2000][700,2100]"),
            n("0.2", res = "$WHATSAPP:id/camera_btn", click = true, bounds = "[710,2000][800,2100]"),
            n("0.3", res = "$WHATSAPP:id/send", click = true, bounds = "[900,2000][1000,2100]")))
        assertEquals("0.3", BotLocator.sendButton(s, BotApp.META_AI, s.nodes[0])?.id)
    }
    @Test fun pickLastChoosesBottomNavDahaCox() {
        val s = UiSnapshot(packageName = MYGOV, nodes = listOf(n("0.1", "Daha çox", click = true, bounds = "[937,122][1069,254]"), n("0.2", "Daha çox", click = true, bounds = "[882,1990][1080,2110]")))
        assertEquals("0.2", BotLocator.find(s, BotApp.MYGOV_BOT.entry[0])?.id)
    }
    @Test fun clickTargetClimbsToClickableAncestorOnly() {
        val s = UiSnapshot(packageName = ABB, nodes = listOf(n("0.1", click = true), n("0.1.2.3", res = "$ABB:id/imageViewHelpCenter")))
        assertEquals("0.1", BotLocator.clickTarget(s, s.nodes[1])?.id)
        assertNull(BotLocator.clickTarget(UiSnapshot(packageName = ABB, nodes = listOf(n("0.1.2")))  , n("0.1.2")))
    }
    @Test fun riskInfoConfirmBlock() {
        assertEquals(BotRisk.Level.INFO, BotRisk.decide("Balans artırmanın komissiyası var?"))
        assertEquals(BotRisk.Level.INFO, BotRisk.decide("Şəxsiyyət vəsiqəsini necə yeniləyə bilərəm?"))
        assertEquals(BotRisk.Level.CONFIRM, BotRisk.decide("Nömrəmə 1 manat yüklə"))
        assertEquals(BotRisk.Level.CONFIRM, BotRisk.decide("Kartdan karta 10 AZN köçür"))
        assertEquals(BotRisk.Level.CONFIRM, BotRisk.decide("Mənim FIN kodum budur"))
        assertEquals(BotRisk.Level.CONFIRM, BotRisk.decide("Köçürməni təsdiqlə?"))
        assertEquals(BotRisk.Level.BLOCK, BotRisk.decide("OTP kodum 123456"))
        assertEquals(BotRisk.Level.BLOCK, BotRisk.decide("Kart 4169 7388 1234 5678"))
    }
    @Test fun replyIsOnlyNewStableTextNotMineOrChrome() {
        val before = UiSnapshot(packageName = MYGOV, nodes = listOf(n("0.1", "Xoş gördük", bounds = "[0,100][10,110]")))
        val after = UiSnapshot(packageName = MYGOV, nodes = listOf(
            n("0.1", "Xoş gördük", bounds = "[0,100][10,110]"),
            n("0.2", "Vəsiqəni necə yeniləyim?", bounds = "[0,200][10,210]"),
            n("0.3", "14:05", bounds = "[0,250][10,260]"),
            n("0.4", "ASAN xidmətə müraciət edin.", bounds = "[0,300][10,310]"),
            n("0.5", "Növbə: e-Növbə ilə.", bounds = "[0,400][10,410]")))
        val reply = ReplyDiff.reply(after, ReplyDiff.baseline(before), "Vəsiqəni necə yeniləyim?")
        assertEquals("ASAN xidmətə müraciət edin.\nNövbə: e-Növbə ilə.", reply)
        assertTrue(ReplyDiff.sentVisible(after, "Vəsiqəni necə yeniləyim?"))
    }
    @Test fun thinkingIndicatorIsNotAReply() {
        val before = UiSnapshot(packageName = MYGOV, nodes = listOf(n("0.1", "Xoş gördük")))
        val thinking = UiSnapshot(packageName = MYGOV, nodes = listOf(n("0.1", "Xoş gördük"), n("0.2", "Sual?", bounds = "[0,200][10,210]"), n("0.3", "Düşünür", bounds = "[0,300][10,310]")))
        assertEquals("", ReplyDiff.reply(thinking, ReplyDiff.baseline(before), "Sual?"))
        assertTrue(ReplyDiff.isPending("Düşünür..."))
        // ABB AI, as seen on device 2026-11-08.
        val abb = UiSnapshot(packageName = ABB, nodes = listOf(n("0.1", "Çat ID: 3f3b5664", bounds = "[0,100][10,110]"),
            n("0.2", "Bəli, komissiya karta görə dəyişir.", bounds = "[0,300][10,310]"),
            n("0.3", "Sorğu üzərində işləyirəm, çox çəkməyəcək.", bounds = "[0,400][10,410]")))
        assertEquals("Bəli, komissiya karta görə dəyişir.", ReplyDiff.reply(abb, emptySet(), "komissiya var?"))
    }
    @Test fun moneyMaskedForBankScreens() {
        assertEquals("Balans: [məbləğ gizli]", Privacy.maskMoney("Balans: 1 234,56 AZN"))
        assertEquals("[məbləğ gizli] qalıq", Privacy.maskMoney("₼ 25.00 qalıq"))
    }

    @Test fun goalCheckRejectsDirectionsOnlyReplies() {
        val goal="mygovdan de ne qeder pensiya kapitalim var"
        // Real replies seen on the phone 2026-11-08: directions, numbered steps, no value.
        assertFalse(GoalCheck.answers(goal,"Portalda \"Əmək pensiyası üzrə məlumatlarım\" bölümündə yalnız pensiya alan vətəndaşların məlumatları öz əksini tapır. \"Pensiya kalkulyatoru\" bölməsində isə vəsait əks olunur."))
        assertFalse(GoalCheck.answers(goal,"1.\nPensiya kapitalınızı yoxlamaq üçün mygov tədbiqinə daxil olub \"Xidmətlər\" bölümündən keçid etməlisiniz.\n2.\nAçılan siyahıdan \"Pensiya kalkulyatoru\" xidmətini seçməlisiniz.\n3.\nYeni pəncərədə görəcəksiniz."))
        assertFalse(GoalCheck.answers(goal,"To check your pension capital, log in to the mygov app and navigate to Services."))
    }
    @Test fun goalCheckAcceptsRealValues() {
        assertTrue(GoalCheck.answers("ne qeder pensiya kapitalim var","Yığılmış pensiya kapitalı: 12 450,30 AZN"))
        assertTrue(GoalCheck.answers("balans artırmanın komissiyası var?","Komissiya 1 manatdır."))
        assertTrue(GoalCheck.answers("şəxsiyyət vəsiqəsini necə yeniləyim","ASAN xidmətə müraciət edin; vəsiqə 5 iş günündə hazır olur."))
    }
}
