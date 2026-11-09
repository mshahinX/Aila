package dev.personal.phoneagent.agent

import dev.personal.phoneagent.adapters.AbbAdapter
import org.junit.Assert.*
import org.junit.Test

class TransferTest {
    @Test fun parsesRequest() {
        val r = TransferRequest.parse("Metinin WhatsApp mesajından kart nömrəsini götür, ABB-dən 20 manat göndər")!!
        assertEquals("Metinin", r.contactWord); assertEquals("Metin", r.contactGuess); assertEquals("20", r.amount)
        assertEquals("12.5", TransferRequest.parse("Leylanın whatsapp mesajındakı karta abb ilə 12,5 azn köçür")!!.amount)
        assertEquals("Leyla", TransferRequest.parse("Leylanın whatsapp mesajındakı karta abb ilə 12,5 azn köçür")!!.contactGuess)
        assertNull(TransferRequest.parse("Metinə WhatsApp-da yaz"))           // no card, no ABB → not a transfer
        assertEquals("", TransferRequest.parse("whatsapp mesajdaki karta abb ile pul gonder")!!.amount) // asked later
    }
    @Test fun guessIsOnlyAHint() {
        assertEquals("Metin", TransferRequest.guessStem("Metindən"))
        assertEquals("Metin", TransferRequest.guessStem("Metinə"))
        assertEquals("Leyla", TransferRequest.guessStem("Leylaya"))
    }
    @Test fun findsNewestLuhnValidCard() {
        val texts = listOf(100 to "Salam, kartım: 4111 1111 1111 1111", 300 to "yox bunu yaz 5555-5555-5555-4444 təşəkkürlər", 200 to "telefon 0501234567")
        val f = CardFinder.find(texts)!!
        assertEquals("5555555555554444", f.number); assertEquals(1, f.others)
        assertEquals("5555 **** **** 4444", CardFinder.masked(f.number))
        assertNull(CardFinder.find(listOf(1 to "1234 5678 9012 3456")))   // fails Luhn
        assertNull(CardFinder.find(listOf(1 to "41111111111111112")))     // 17 digits
    }
    @Test fun abbFormRecognised() {
        val form = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(
            UiNode("0.1", text = "Köçürmələr"), UiNode("0.2", text = "Digər karta", clickable = true),
            UiNode("0.3", "$ABB:id/cell_edit_text", editable = true, clickable = true)))
        assertTrue(AbbTransfer.onTransferScreen(form))
        assertFalse(AbbTransfer.onTransferScreen(form.copy(sensitive = true)))
        assertFalse(AbbTransfer.onTransferScreen(form.copy(packageName = WHATSAPP)))
    }
    @Test fun abbTypesOnlyTheOwnersAmountAndNeverOverwritesTheCard() {
        val s = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(
            UiNode("0.1", "$ABB:id/cell_edit_text", text = "[identifier hidden]", editable = true),
            UiNode("0.2", "$ABB:id/amount", editable = true)))
        val ctx = TaskContext("ABB-də ... məbləğ sahəsinə dəqiq 20 yaz", mode = "live")
        val p = { t: String, x: String -> Proposal("a", s.snapshotId, "set_text", t, x) }
        assertEquals(Decision.Allow, ActionPolicy().assess(s, p("0.2", "20"), ctx))
        assertEquals(Decision.Allow, ActionPolicy().assess(s, p("0.2", "20.00"), ctx))
        assertTrue(ActionPolicy().assess(s, p("0.2", "200"), ctx) is Decision.Manual)
        assertTrue(ActionPolicy().assess(s, p("0.2", "2"), ctx) is Decision.Manual)
        assertTrue(ActionPolicy().assess(s, p("0.1", "123"), ctx) is Decision.Manual)
        assertEquals(setOf("10", "100", "12.5"), AbbAdapter.numbers("10 100 12,50"))
    }
    @Test fun finalPressOnlyWhenAmountAndCardSeen() {
        val card = "5167000000003277"
        assertTrue(AbbTransfer.confirmReady(listOf("Alıcı", "5167 **** **** 3277", "Məbləğ", "1.00 AZN", "Komissiya 0.00 AZN"), "1", card))
        assertTrue(AbbTransfer.confirmReady(listOf("Kart: **3277", "Məbləğ:", "1"), "1", card))
        assertFalse(AbbTransfer.confirmReady(listOf("5167 **** **** 3277", "Məbləğ", "10.00 AZN"), "1", card))   // wrong amount
        assertFalse(AbbTransfer.confirmReady(listOf("5167 **** **** 9999", "1.00 AZN"), "1", card))             // other card
        assertFalse(AbbTransfer.confirmReady(listOf("1.00 AZN"), "1", card))                                    // card not shown
    }
    @Test fun finalButtonMustBeUnique() {
        val one = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(UiNode("0.1", clickable = true), UiNode("0.1.0", text = "Köçür"), UiNode("0.2", text = "Geri", clickable = true)))
        assertEquals("0.1", AbbTransfer.finalButton(one)?.id)
        val two = one.copy(nodes = one.nodes + UiNode("0.3", text = "Təsdiqlə", clickable = true))
        assertNull(AbbTransfer.finalButton(two))
        assertNull(AbbTransfer.finalButton(one.copy(sensitive = true)))
    }
    @Test fun onlyThePersonsOwnChat() {
        assertTrue(TransferRequest.isPersonChat("Mətin", "Metinin", "Metin"))
        assertTrue(TransferRequest.isPersonChat("Mətin Məmmədov", "Metinin", "Metin"))
        assertFalse(TransferRequest.isPersonChat("Yunex Outliers", "Metinin", "Metin"))   // real group, 2026-11-08
        assertFalse(TransferRequest.isPersonChat("Matin", "Metinin", "Metin"))            // different person
    }
    @Test fun searchesAzerbaijaniSpellingFirst() {
        assertEquals(listOf("Mətin", "Metin", "Metın"), TransferRequest.searchNames("Metin"))
        assertEquals(listOf("Elçin", "Əlçin", "Elcin", "Elçın"), TransferRequest.searchNames("Elçin"))
        assertEquals(listOf("Ləyla", "Leylə", "Ləylə", "Leyla"), TransferRequest.searchNames("Leyla"))
        assertEquals(listOf("Ənar", "Anər", "Ənər", "Anar"), TransferRequest.searchNames("Anar"))
        assertTrue(sameName("Mətin", "Metin"))
    }
    /** 2026-11-08: "Rahman" found nothing; the contact is saved as "Rəhman". */
    @Test fun searchesRahmanAsRehman() {
        assertEquals("Rəhman", NameSearch.spellings("Rahman").first())
        assertEquals(listOf("Rəhman", "Rahman", "Rehman", "Rəhmən"), NameSearch.spellings("Rəhman"))
        assertTrue(NameSearch.spellings("Rahman").all { sameName(it, "Rahman") || it == "Rahmən" || it == "Rəhmən" })
        assertTrue(TransferRequest.isPersonChat("Rəhman", "Rahman", "Rahman"))
        assertFalse(TransferRequest.isPersonChat("Rəhim", "Rahman", "Rahman"))
        assertTrue(NameSearch.spellings("Mehriban İsmayılova").size <= 8)
        assertEquals(emptyList<String>(), NameSearch.spellings("  "))
    }
    @Test fun findsHomeShortcutAndOtherCardTab() {
        val home = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(
            UiNode("0.1", "$ABB:id/top_up", clickable = true), UiNode("0.2", "$ABB:id/transfer", clickable = true), UiNode("0.3", "$ABB:id/pay", clickable = true)))
        assertEquals("0.2", AbbTransfer.transferButton(home)?.id)
        assertNull(AbbTransfer.transferButton(home.copy(sensitive = true)))
        val tabs = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(
            UiNode("0.1", clickable = true), UiNode("0.1.0", text = "Digər karta"), UiNode("0.2", text = "Nömrə ilə karta", clickable = true)))
        assertEquals("0.1", AbbTransfer.otherCardTab(tabs)?.id)
    }
    @Test fun ownCardsExcludeRecipient() {
        val raw = listOf("Kartdan", "ABB Visa **** 1234", "Balans", "•• 5678", "Alıcı **** 3277")
        assertEquals(listOf("1234", "5678"), AbbTransfer.ownCards(raw, "5167000000003277"))
    }
    @Test fun finalButtonFoundWithAmountInLabel() {
        // The label seen on the phone on 2026-11-08 (slide 17): the amount sits inside the button.
        val s = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(UiNode("0.1", text = "Köçürmə et • 1.00 ₼", clickable = true), UiNode("0.2", text = "Köçürmələr", clickable = true), UiNode("0.3", text = "4.97 ₼", clickable = true)))
        assertEquals("0.1", AbbTransfer.finalButton(s)?.id)
        assertTrue(ActionPolicy().assess(s, Proposal("a", s.snapshotId, "click", "0.1"), TaskContext("x", mode = "live")) is Decision.Manual)
    }
    @Test fun koecuermeEtIsTheFinalButton() {
        val s = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(UiNode("0.1", text = "Köçürmə et", clickable = true), UiNode("0.2", text = "Köçürmələr", clickable = true)))
        assertEquals("0.1", AbbTransfer.finalButton(s)?.id)
        val p = Proposal("a", s.snapshotId, "click", "0.1")
        assertTrue(ActionPolicy().assess(s, p, TaskContext("x", mode = "live")) is Decision.Manual)          // the AI loop never presses it
        assertEquals(Decision.Allow, ActionPolicy().assess(s, p.copy(targetId = "0.2"), TaskContext("x", mode = "live")))
    }
    @Test fun readsTheOwnersCardListAsSeenOnThePhone() {
        // Layout of ABB "Məhsullarım" / card list, 2026-11-08 (balances replaced).
        val raw = listOf(770 to "Mastercard", 760 to "1.00₼", 830 to "•••• 4179", 940 to "Mastercard", 940 to "2.00₼", 1000 to "•••• 8831",
            1110 to "VISA", 1110 to "3.00₼", 1165 to "•••• 7503")
        val cards = AbbTransfer.cardOptions(raw, "5167000000003277")
        assertEquals(listOf("4179", "8831", "7503"), cards.map { it.last4 })
        assertEquals("VISA •••• 7503 (3.00₼)", cards[2].choice)
    }
    @Test fun amountShownAsSeparateNumberAndCurrency() {
        // Amount screen 2026-11-08: "Kart", "4.97 ₼", "MATIN M*", "**** 3277", "1", "₼".
        val raw = listOf("Kart", "4.97 ₼", "MATIN M*", "**** 3277", "1", "₼", "Təyinat: Dosta köçürmə")
        assertTrue(AbbTransfer.confirmReady(raw, "1", "5167000000003277"))
        assertFalse(AbbTransfer.confirmReady(raw, "2", "5167000000003277"))
    }
    @Test fun findsSourceTileNotRecipient() {
        val s = UiSnapshot(packageName = ABB, screen = "browse", nodes = listOf(
            UiNode("0.1", clickable = true, bounds = "[50,250][500,430]"), UiNode("0.1.0", text = "Kart"), UiNode("0.1.1", text = "[məbləğ gizli]"),
            UiNode("0.2", clickable = true, bounds = "[560,250][1030,430]"), UiNode("0.2.0", text = "MATIN M*"), UiNode("0.2.1", text = "**** 3277"),
            UiNode("0.3", text = "Təyinat: Dosta köçürmə", clickable = true, bounds = "[240,720][830,810]")))
        assertEquals("0.1", AbbTransfer.sourceTile(s, "5167000000003277")?.id)
    }
    @Test fun readsGonderenSheet() {
        // ABB "Göndərən" sheet, 2026-11-08 (balances replaced).
        val raw = listOf(760 to "Göndərən", 1180 to "Kart • Debit • 7503", 1250 to "10.00 ₼", 1410 to "Kart • Debit • 8831", 1480 to "2.00 ₼",
            1640 to "Kart • Debit • 4179", 1710 to "3.00 ₼", 1990 to "Digər kart")
        val cards = AbbTransfer.cardOptions(raw, "5167000000003277")
        assertEquals(listOf("7503", "8831", "4179"), cards.map { it.last4 })
        assertEquals("Kart • Debit •••• 7503 (10.00 ₼)", cards[0].choice)
    }
    @Test fun homeExamplesRouteCorrectly() {
        val t = TransferRequest.parse("Mətinin WhatsApp mesajındakı karta ABB-dən 1 manat göndər.")!!
        assertEquals("Mətin", t.contactGuess); assertEquals("1", t.amount)
        assertEquals(GovIntent.READ, GovIntentRules.classify("myGov-dan pensiya kapitalıma bax."))
    }
}
