package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SafetyTest {
    private fun node(id: String, resource: String, text: String="", editable: Boolean=false, clickable: Boolean=false) =
        UiNode(id,"$WHATSAPP:id/$resource",text,editable=editable,clickable=clickable)
    private fun chat(text: String="hello", name: String="Mətin") = UiSnapshot(packageName=WHATSAPP,screen="chat",nodes=listOf(
        node("0.0","conversation_contact_name",name),node("0.1","entry",text,true),node("0.2","send",clickable=true)))
    private fun p(s: UiSnapshot,type: String="click",target: String="0.2",text: String="") = Proposal("action",s.snapshotId,type,target,text)
    private fun context(name: String?="Mətin") = TaskContext("Say hello",confirmedRecipient=name,mode="live")
    @Test fun sendRequiresExactLocalApproval() { val s=chat(); assertEquals(Decision.Approval("Mətin","hello"),ActionPolicy().assess(s,p(s),context())) }
    @Test fun recipientMustBeConfirmed() { val s=chat(); assertTrue(ActionPolicy().assess(s,p(s),context(null)) is Decision.Clarify) }
    @Test fun unknownBankClicksAlwaysManual() { val s=chat().copy(packageName=BANK); assertTrue(ActionPolicy().assess(s,p(s),context()) is Decision.Manual) }
    @Test fun unknownGovernmentSubmitManual() { val s=chat().copy(packageName=MYGOV); assertTrue(ActionPolicy().assess(s,p(s),context()) is Decision.Manual) }
    private fun gov(vararg nodes: UiNode) = UiSnapshot(packageName=MYGOV,screen="browse",nodes=nodes.toList())
    @Test fun myGovReadOnlyNavigationAllowed() {
        val s=gov(UiNode("0.1",clickable=true),UiNode("0.1.0",text="Sosial müdafiə və təminat"),UiNode("0.2",text="Pensiya kalkulyatoru",clickable=true),UiNode("0.3",scrollable=true))
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,target="0.1"),context()))
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,target="0.2"),context()))
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,"scroll","0.3"),context()))
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,"back",""),context()))
    }
    @Test fun myGovConsequentialControlsManual() {
        listOf("Müraciət et","Təsdiqlə","Ödə","Sil","Paylaş","Çıxış","Submit","Göndər").forEach { label ->
            val s=gov(UiNode("0.1",clickable=true),UiNode("0.1.0",text=label))
            assertTrue(label,ActionPolicy().assess(s,p(s,target="0.1"),context()) is Decision.Manual)
        }
    }
    @Test fun myGovTypesOnlyIntoSearch() {
        val s=gov(UiNode("0.1",description="Axtarış",editable=true),UiNode("0.2",description="FİN",editable=true))
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,"set_text","0.1","pensiya"),context()))
        assertTrue(ActionPolicy().assess(s,p(s,"set_text","0.2","ABC1234"),context()) is Decision.Manual)
    }
    @Test fun unlistedAppCannotClick() { val s=chat().copy(packageName="other.app"); assertTrue(ActionPolicy().assess(s,p(s),context()) is Decision.Manual) }
    @Test fun sensitiveScreenCannotLaunchOrFinish() { val s=chat().copy(sensitive=true); assertTrue(ActionPolicy().assess(s,p(s,"finish"),context()) is Decision.Manual) }
    @Test fun staleSnapshotRejected() { val s=chat(); assertTrue(ActionPolicy().assess(s,p(s).copy(snapshotId="old"),context()) is Decision.Manual) }
    @Test fun existingDraftNotOverwritten() { val s=chat("keep this"); assertTrue(ActionPolicy().assess(s,p(s,"set_text","0.1","new"),context()) is Decision.Manual) }
    @Test fun emptyDraftCanBeWrittenWithUnicode() { val s=chat(""); assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,"set_text","0.1","Əli, görüşərik!"),context())) }
    @Test fun unknownButtonNeverSafeFromLabel() { val s=chat().copy(nodes=listOf(node("0.2","message_text","Search",clickable=true))); assertTrue(ActionPolicy().assess(s,p(s),context()) is Decision.Manual) }
    @Test fun fakeChatsDescriptionNotNavigation() { val s=chat().copy(screen="chats",nodes=listOf(UiNode("0.2",description="Chats",clickable=true))); assertTrue(ActionPolicy().assess(s,p(s),context()) is Decision.Manual) }
    @Test fun modelCannotRequestSecurityCredentials() {
        val s=chat()
        listOf("Enter your OTP", "What is your password?", "PIN?", "CVV", "Şifrəni yazın").forEach { question ->
            assertTrue(ActionPolicy().assess(s,p(s,"ask_user").copy(question=question),context()) is Decision.Manual)
        }
    }
    /** Screen taps exist only for Köməkçi's own scripted flows; the model can never use them, even on an allowed row. */
    @Test fun modelCannotTap() {
        val s=chat()
        assertTrue(ActionPolicy().assess(s,p(s,type="tap"),context()) is Decision.Manual)
        assertTrue(TAP_TARGETS.all { it.startsWith("$WHATSAPP:id/contactpicker_row") })
        assertFalse("android" in SUPPORTED_PACKAGES)   // share sheet: scripted flows only
    }
    @Test fun approvalOneUse() { val s=chat(); val a=p(s); val g=ApprovalGate{1000}; val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); assertTrue(g.consume(t,"task",s,a)); assertFalse(g.consume(t,"task",s,a)) }
    @Test fun changingDraftInvalidatesApproval() { val s=chat(); val a=p(s); val g=ApprovalGate{1000}; val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); assertFalse(g.consume(t,"task",chat("changed"),a)) }
    @Test fun changingRecipientInvalidatesApproval() { val s=chat(); val a=p(s); val g=ApprovalGate{1000}; val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); assertFalse(g.consume(t,"task",chat(name="Other"),a)) }
    @Test fun approvalExpires() { var now=0L; val g=ApprovalGate{now}; val s=chat(); val a=p(s); val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); now=60001; assertFalse(g.consume(t,"task",s,a)) }
    @Test fun stopInvalidatesApproval() { val g=ApprovalGate{1000}; val s=chat(); val a=p(s); val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); g.invalidate(); assertFalse(g.consume(t,"task",s,a)) }
    @Test fun actionAndTaskAreBound() { val g=ApprovalGate{1000}; val s=chat(); val a=p(s); val t=g.issue("task",s,a,Decision.Approval("Mətin","hello")); assertFalse(g.consume(t,"other-task",s,a)) }
    @Test fun fingerprintIgnoresCaptureIdsNotText() { val s=chat(); assertEquals(s.fingerprint,s.copy(snapshotId="different",capturedAt=0).fingerprint); assertNotEquals(s.fingerprint,chat("different").fingerprint) }
    @Test fun fingerprintSeparatorsCannotCollide() { val a=UiSnapshot(packageName=WHATSAPP,nodes=listOf(UiNode("0",text="a~b",description="c"))); val b=a.copy(nodes=listOf(UiNode("0",text="a",description="b~c"))); assertNotEquals(a.fingerprint,b.fingerprint) }
    @Test fun maskedRawContentChangesStillInvalidateLocalFingerprint() {
        val a=UiSnapshot(packageName=WHATSAPP,nodes=listOf(UiNode("0",text="[identifier hidden]",localContentHash=digest("123456789"))))
        val b=a.copy(nodes=listOf(a.nodes.single().copy(localContentHash=digest("987654321"))))
        assertNotEquals(a.fingerprint,b.fingerprint)
        assertFalse(a.nodes.single().json().has("localContentHash"))
    }
    @Test fun cardAndAccountIdentifiersMasked() { assertFalse(Privacy.mask("1234 5678 9012 3456").contains("1234")); assertTrue(Privacy.containsSensitiveIdentifier("994501234567")); assertEquals("Mətin Əli şı",Privacy.mask("Mətin Əli şı")) }
    @Test fun allBankFlowsValidateWithoutCommitting() { BankFlow.entries.forEach { flow -> val plan=BankPreparation(flow,"Recipient label","Source","12.50","AZN","Provider"); assertNull(plan.validate()); assertTrue(plan.review().contains("not a bank transaction")) } }
    @Test fun bankIdentifiersRejected() { assertNotNull(BankPreparation(BankFlow.CARD,"1234567890123456","Source","12","AZN").validate()) }
    @Test fun bankAmountMustBePositiveAndPrecise() { listOf("0","-1","1.234","NaN").forEach { amount -> assertNotNull(BankPreparation(BankFlow.OWN,"Other","Source",amount,"AZN").validate()) } }
    @Test fun newMessageMustHaveOwnStatus() {
        val before=chat()
        val after=before.copy(nodes=before.nodes.map { if(it.resourceId.endsWith("/entry")) it.copy(text="") else it } + listOf(
            node("0.3.0","message_text","hello"),UiNode("0.3.1","$WHATSAPP:id/status",description="Delivered")))
        assertEquals("delivered",SendEvidence.whatsappStatus(before,after,"Mətin","hello"))
        val unrelated=after.copy(nodes=after.nodes.map { if(it.id=="0.3.1") it.copy(id="0.4.0") else it })
        assertNull(SendEvidence.whatsappStatus(before,unrelated,"Mətin","hello"))
        assertNull(SendEvidence.whatsappStatus(before,after,"Other","hello"))
        assertNull(SendEvidence.whatsappStatus(before,after.copy(nodes=after.nodes.filterNot { it.resourceId.endsWith("/entry") }),"Mətin","hello"))
    }
    @Test fun wideContainerCannotBorrowOtherMessageStatus() {
        val before=chat()
        val after=before.copy(nodes=before.nodes.map { if(it.resourceId.endsWith("/entry")) it.copy(text="") else it } + listOf(
            node("0.3.0.0","message_text","different"),node("0.3.1.0","message_text","hello"),
            UiNode("0.3.0.1","$WHATSAPP:id/status",description="Delivered")))
        assertNull(SendEvidence.whatsappStatus(before,after,"Mətin","hello"))
    }
    @Test fun wrappedLargeDurationRejected() {
        val j=JSONObject("""{"actionId":"a","snapshotId":"s","type":"wait","targetId":"","text":"","packageName":"","question":"","message":"","direction":"forward","durationMs":4294967297}""")
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(j) }
    }
    @Test fun approvalFieldsFromModelRejected() {
        val j=JSONObject("""{"actionId":"a","snapshotId":"s","type":"click","targetId":"0","text":"","packageName":"","question":"","message":"","direction":"forward","durationMs":0,"approved":true}""")
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(j) }
    }
    @Test fun fractionalDurationRejected() {
        val j=JSONObject("""{"actionId":"a","snapshotId":"s","type":"wait","targetId":"","text":"","packageName":"","question":"","message":"","direction":"forward","durationMs":1.5}""")
        assertThrows(IllegalArgumentException::class.java) { Proposal.parse(j) }
    }
    @Test fun pinnedChatIconIsNotAPinField() {
        assertFalse(Privacy.credentialResourceId("com.whatsapp:id/pin_indicator"))
        assertFalse(Privacy.credentialResourceId("com.whatsapp:id/pinned_messages"))
        listOf("com.bank:id/pin","com.bank:id/pin_entry","com.bank:id/login_pin_code","com.x:id/otp_input","com.x:id/password","com.x:id/cvv")
            .forEach { assertTrue(it,Privacy.credentialResourceId(it)) }
    }
    private fun results(vararg names: String) = UiSnapshot(packageName=WHATSAPP,screen="search",nodes=listOf(node("0.0","search_input","Matin",editable=true))+
        names.flatMapIndexed { i, n -> listOf(node("0.${i+1}","contact_row_container",clickable=true),node("0.${i+1}.0","conversations_row_contact_name",n)) })
    @Test fun namedRecipientIsChosenWithoutAskingOnlyWhenUnique() {
        val s=results("Matin","Leyla","Anam")
        assertEquals(Decision.Allow,ActionPolicy().assess(s,p(s,"click","0.1"),context("Matin")))
        assertTrue(ActionPolicy().assess(s,p(s,"click","0.2"),context("Matin")) is Decision.Clarify)
        val dup=results("Matin","Matin")
        assertTrue(ActionPolicy().assess(dup,p(dup,"click","0.1"),context("Matin")) is Decision.Clarify)
    }
    @Test fun zeroWidthCharactersAreStripped() { assertEquals("Matin",stripInvisible("\u200BMatin\uFEFF")) }
    @Test fun azerbaijaniLettersMatchButDifferentNamesDoNot() {
        assertTrue(sameName("Matin","Mətin")); assertTrue(sameName("Metin","Mətin")); assertTrue(sameName("ali","Əli"))
        assertTrue(sameName("\u200BSevinc ","sevinc")); assertFalse(sameName("Matin","Metin")); assertFalse(sameName("Matin",null))
    }
    @Test fun typedNameWithoutSchwaReachesApprovalForSavedSchwaName() {
        val empty=chat(""); assertEquals(Decision.Allow,ActionPolicy().assess(empty,p(empty,"set_text","0.1","salam"),context("Matin")))
        val drafted=chat("salam"); assertEquals(Decision.Approval("Mətin","salam"),ActionPolicy().assess(drafted,p(drafted),context("Matin")))
        val other=chat("salam",name="Metin"); assertTrue(ActionPolicy().assess(other,p(other),context("Matin")) is Decision.Clarify)
    }
}
