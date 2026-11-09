package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class GovAgentTest {
    // ---- classification ----
    @Test fun ownDataIsRead() {
        listOf("mygovdan ne qeder pensiya kapitali yigildigimi Metine de", "mygovdan de ne qeder pensiya kapitalim var",
            "cərimələrimə bax", "mənim sənədlərim", "pensiya kapitalımı göstər").forEach {
            assertEquals(it, GovIntent.READ, GovIntentRules.classify(it))
        }
    }
    @Test fun generalQuestionIsInfo() {
        listOf("şəxsiyyət vəsiqəsini necə yeniləyəcəyimi öyrən", "pasport üçün hansı sənəd lazımdır", "pensiya kalkulyatoru nədir").forEach {
            assertEquals(it, GovIntent.INFO, GovIntentRules.classify(it))
        }
    }
    @Test fun doSomethingIsAction() {
        listOf("iş yeri üçün arayış sifariş et", "arayış al", "müraciət et doğum haqqında şəhadətnamə üçün").forEach {
            assertEquals(it, GovIntent.ACTION, GovIntentRules.classify(it))
        }
    }
    @Test fun ambiguousIsUnclear() {
        assertEquals(GovIntent.UNCLEAR, GovIntentRules.classify("necə müraciət edim"))
        assertEquals(GovIntent.UNCLEAR, GovIntentRules.classify("vəsiqəmin müddəti nə vaxt bitir"))
        assertEquals(GovIntent.UNCLEAR, GovIntentRules.classify("pensiya haqqında"))
    }
    @Test fun phoneRulesOverrideModelOnOwnDataAndActions() {
        assertEquals(GovIntent.READ, GovIntentRules.decide(GovIntent.READ, GovIntent.INFO))   // own data never to the bot
        assertEquals(GovIntent.ACTION, GovIntentRules.decide(GovIntent.ACTION, GovIntent.INFO))
        assertEquals(GovIntent.READ, GovIntentRules.decide(GovIntent.UNCLEAR, GovIntent.READ))
        assertEquals(GovIntent.INFO, GovIntentRules.decide(GovIntent.INFO, GovIntent.UNCLEAR))
        assertEquals(GovIntent.UNCLEAR, GovIntentRules.decide(GovIntent.INFO, GovIntent.ACTION)) // disagreement → owner
        assertEquals(GovIntent.READ, GovIntentRules.fromChoice(GovIntentRules.choices[1]))
    }

    // ---- routes + local reader ----
    @Test fun routeMatchesGoalAndIntent() {
        assertEquals("pension", GovRoutes.find("pensiya kapitalim", GovIntent.READ)?.id)
        assertEquals("certificate", GovRoutes.find("arayış sifariş et", GovIntent.ACTION)?.id)
        assertNull(GovRoutes.find("hava necədir", GovIntent.READ))
        // Only the pension path has been seen on the phone; the others must not claim it.
        assertEquals(listOf("pension"), GovRoutes.all.filter { it.calibrated }.map { it.id })
        val pension = GovRoutes.all.first { it.id == "pension" }
        assertEquals("Pensiya kalkulyatoru", pension.target)
        assertTrue(pension.avoid.contains("Əmək pensiyası üzrə məlumatlarım"))
    }
    @Test fun readerPairsLabelsWithValuesAndMasksIdentifiers() {
        val s = UiSnapshot(packageName = MYGOV, screen = "browse", nodes = listOf(
            UiNode("0.1", text = "Pensiya kalkulyatoru", bounds = "[0,100][1080,160]"),
            UiNode("0.2", text = "Yaşa görə pensiya kapitalı", bounds = "[0,200][1080,260]"),
            UiNode("0.3", text = "12 450,30 AZN", bounds = "[0,270][1080,330]"),
            UiNode("0.4", text = "FİN: 5ABC12D", bounds = "[0,400][1080,460]"),
            UiNode("0.5", text = "1.", bounds = "[0,500][100,560]")))
        val out = GovReader.read(s, GovRoutes.all.first { it.id == "pension" })
        assertTrue(out, out.contains("Yaşa görə pensiya kapitalı: 12 450,30 AZN"))
        assertFalse(out, out.contains("5ABC12D"))
        assertTrue(GoalCheck.answers("ne qeder pensiya kapitalim var", out))
    }
    @Test fun identifiersMasked() {
        val m = Privacy.maskIdentifiers("FİN 5ABC12D, vəsiqə AA1234567, tel +994 50 123 45 67, məbləğ 12 450 AZN, tarix 08.11.2026")
        assertFalse(m, m.contains("5ABC12D") || m.contains("1234567") || m.contains("123 45 67"))
        assertTrue(m, m.contains("12 450 AZN") && m.contains("08.11.2026"))
    }

    // ---- form (ACTION) policy ----
    private fun form(vararg nodes: UiNode) = UiSnapshot(packageName = MYGOV, screen = "browse", nodes = nodes.toList())
    private fun p(s: UiSnapshot, type: String, target: String, text: String = "") = Proposal("a", s.snapshotId, type, target, text)
    private fun ctx(command: String, vararg answers: String) = TaskContext(command, answers.toMutableList(), mode = "live", govIntent = GovIntent.ACTION)
    private val policy = ActionPolicy()
    @Test fun formFillsOnlyOwnerWords() {
        val s = form(UiNode("0.1", description = "Təqdim ediləcəyi yer", editable = true))
        val c = ctx("iş yeri üçün arayış sifariş et")
        assertEquals(Decision.Allow, policy.assess(s, p(s, "set_text", "0.1", "iş yeri"), c))
        assertTrue(policy.assess(s, p(s, "set_text", "0.1", "bank"), c) is Decision.Clarify)            // invented → ask
        assertEquals(Decision.Allow, policy.assess(s, p(s, "set_text", "0.1", "ABB bank"), ctx("arayış sifariş et", "ABB bank")))
    }
    @Test fun formNeverTypesIdentifiersOrOverwrites() {
        val s = form(UiNode("0.1", description = "FİN kod", editable = true), UiNode("0.2", description = "Mobil nömrə", editable = true),
            UiNode("0.3", description = "Qeyd", text = "myGov doldurdu", editable = true))
        val c = ctx("arayış sifariş et 5ABC12D qeyd")
        assertTrue(policy.assess(s, p(s, "set_text", "0.1", "5ABC12D"), c) is Decision.Manual)
        assertTrue(policy.assess(s, p(s, "set_text", "0.2", "qeyd"), c) is Decision.Manual)
        assertTrue(policy.assess(s, p(s, "set_text", "0.3", "qeyd"), c) is Decision.Manual)
    }
    @Test fun formStopsAtFinalButtonButOpensServices() {
        val c = ctx("arayış sifariş et")
        listOf("Müraciət et", "Göndər", "Təsdiqlə", "Sifarişi təsdiqlə", "İmzala", "Ödə").forEach { label ->
            val s = form(UiNode("0.1", text = label, clickable = true))
            val d = policy.assess(s, p(s, "click", "0.1"), c)
            assertTrue(label, d is Decision.Manual)
        }
        val s = form(UiNode("0.1", text = "Arayış sifarişi", clickable = true), UiNode("0.2", text = "Davam et", clickable = true), UiNode("0.3", text = "Hesabdan çıx", clickable = true))
        assertEquals(Decision.Allow, policy.assess(s, p(s, "click", "0.1"), c))
        assertEquals(Decision.Allow, policy.assess(s, p(s, "click", "0.2"), c))
        assertTrue(policy.assess(s, p(s, "click", "0.3"), c) is Decision.Manual)
        // Opening a service is navigation in READ mode too; its final button is what stays blocked.
        assertEquals(Decision.Allow, policy.assess(s, p(s, "click", "0.1"), TaskContext("x", mode = "live")))
    }
    @Test fun readModeOpensCategoryTilesButNotVerbButtons() {
        val read = TaskContext("pensiya kapitalim", mode = "live")
        // Real tile from the phone (2026-11-08): name + subtitle listing payments.
        val tile = form(UiNode("0.1", clickable = true), UiNode("0.1.0", text = "Pensiya və ödənişlər"), UiNode("0.1.1", text = "Kalkulyator, sığorta ödənişləri"))
        assertEquals(Decision.Allow, policy.assess(tile, p(tile, "click", "0.1"), read))
        listOf("Müraciətlərim", "Arayış sifarişi", "Ödənişlər tarixçəsi").forEach { label ->
            val s = form(UiNode("0.1", text = label, clickable = true))
            assertEquals(label, Decision.Allow, policy.assess(s, p(s, "click", "0.1"), read))
        }
        listOf("Bəli, göndər", "Ödəniş et", "Sil", "Müraciət et", "Yüklə").forEach { label ->
            val s = form(UiNode("0.1", clickable = true), UiNode("0.1.0", text = label))
            assertTrue(label, policy.assess(s, p(s, "click", "0.1"), read) is Decision.Manual)
        }
    }
    @Test fun myGovPinPadIsLoginButServicesScreenIsNot() {
        val pad = (0..9).map { UiNode("0.$it", text = "$it", clickable = true) }
        assertTrue(GovLogin.looksLikeLogin(pad + UiNode("0.20", text = "PİN kodu daxil edin")))
        assertTrue(GovLogin.looksLikeLogin(pad + UiNode("0.20", description = "Barmaq izi ilə daxil ol")))
        assertTrue(GovLogin.looksLikeLogin(listOf(UiNode("0.1", text = "PİN-kodu təkrar daxil edin"))))
        // Real Xidmətlər screen labels (2026-11-08).
        val services = listOf("Axtar", "Şəxsi", "Ailə", "Təhsil", "Məşğulluq", "Rabitə", "Nəqliyyat", "Kommunal", "Əmlak", "Sağlamlıq",
            "Sosial müdafiə və təminat", "Əsas", "Xidmətlər", "QR skan", "Ödənişlər", "Daha çox").mapIndexed { i, t -> UiNode("0.$i", text = t, clickable = true) }
        assertFalse(GovLogin.looksLikeLogin(services))
        assertFalse(GovLogin.looksLikeLogin((1..9).map { UiNode("0.$it", text = "$it") }))  // a calculator-like screen without auth words
    }
    @Test fun readerUsesSameColumnLabelOnRealCalculatorLayout() {
        // Layout of the owner's Pensiya kalkulyatoru screen (2026-11-08, values replaced).
        val s = UiSnapshot(packageName = MYGOV, screen = "browse", nodes = listOf(
            UiNode("0.1", text = "Pensiya kalkulyatoru", bounds = "[150,150][700,230]"),
            UiNode("0.2", text = "Hüququn yaranma tarixi:", bounds = "[88,570][520,620]"),
            UiNode("0.3", text = "01.01.2070", bounds = "[88,625][330,680]"),
            UiNode("0.4", text = "Pensiya yaşı:", bounds = "[88,735][320,790]"),
            UiNode("0.5", text = "65", bounds = "[88,795][140,845]"),
            UiNode("0.6", text = "Pensiya kapitalı", bounds = "[88,930][360,985]"),
            UiNode("0.7", text = "Yaşa görə pensiya məbləği", bounds = "[585,930][910,1040]"),
            UiNode("0.8", text = "1234.56₼", bounds = "[88,1130][400,1200]"),
            UiNode("0.9", text = "50.5₼", bounds = "[585,1130][760,1200]"),
            UiNode("0.10", text = "01.01.2006-01.07.2018-ci il dövrü üzrə fərdi şəxsi hesabınızda pensiya kapitalı qeyd olunmadığı üçün…", bounds = "[180,1340][950,1620]")))
        val out = GovReader.read(s, GovRoutes.all.first { it.id == "pension" })
        assertEquals("Pensiya kapitalı: 1234.56₼\nYaşa görə pensiya məbləği: 50.5₼", out)
        assertTrue(GoalCheck.answers("mygovdan ne qeder pensiya kapitalim var", out))
    }
    @Test fun myGovResultsAreShownInEnglish() {
        assertEquals("Pension capital: 10,425.73 ₼\nAge-based pension amount: 72.4 ₼",
            GovEnglish.show("Pensiya kapitalı: 10425.73₼\nYaşa görə pensiya məbləği: 72.4₼"))
        assertEquals("Pension calculator", GovEnglish.show("Pensiya kalkulyatoru"))
        assertEquals("Fines: 1,200 AZN", GovEnglish.show("Cərimələr: 1200 AZN"))
        assertEquals("Total: 5 ₼ on 01.01.2070", GovEnglish.show("Cəmi: 5₼ on 01.01.2070"))
    }
}
