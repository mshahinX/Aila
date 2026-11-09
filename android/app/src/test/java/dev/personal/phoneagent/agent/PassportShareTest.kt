package dev.personal.phoneagent.agent

import org.junit.Assert.*
import org.junit.Test

class PassportShareTest {
    @Test fun recognisesPassportRequests() {
        assertTrue(PassportShare.matches("myGov-dan ümumvətəndaş pasportumu yüklə və WhatsApp-da Rəhmana göndər."))
        assertTrue(PassportShare.matches("xarici pasportumu Rahmana whatsappda at"))
        assertTrue(PassportShare.matches("umumvetendas pasportumu Rehmana gonder"))
        assertFalse(PassportShare.matches("myGov-dan pensiya kapitalıma bax."))
        assertFalse(PassportShare.matches("pasportumun müddəti nə vaxt bitir?"))
        assertFalse(TransferRequest.matches("myGov-dan ümumvətəndaş pasportumu yüklə və WhatsApp-da Rəhmana göndər."))
    }
    @Test fun choosesIdCardOrPassport() {
        val id = "myGov-dan şəxsiyyət vəsiqəmi yüklə və WhatsApp-da Rəhmana göndər."
        assertTrue(PassportShare.matches(id))
        assertEquals(GovDoc.ID_CARD, PassportShare.document(id))
        assertEquals("Rəhman", PassportShare.recipient(id))
        assertEquals(GovDoc.ID_CARD, PassportShare.document("sexsiyyet vesiqemi Rahmana whatsappda gonder"))
        assertEquals("Rahman", PassportShare.recipient("sexsiyyet vesiqemi Rahmana whatsappda gonder"))
        assertEquals("Rəhman", PassportShare.recipient("Şəxsiyyət vəsiqəmi Rəhmana göndər"))
        assertEquals(GovDoc.PASSPORT, PassportShare.document("xarici pasportumu Rahmana whatsappda gonder"))
        assertTrue(GovDoc.ID_CARD.card.matches("Şəxsiyyət vəsiqəsi"))
        assertFalse(GovDoc.ID_CARD.card.matches("Ümumvətəndaş pasportu"))
        assertTrue(GovDoc.PASSPORT.row!!.matches("ÜMUMVƏTƏNDAŞ"))
        assertFalse(PassportShare.matches("şəxsiyyət vəsiqəmin müddəti nə vaxt bitir?"))
    }
    @Test fun findsRecipient() {
        assertEquals("Rəhman", PassportShare.recipient("myGov-dan ümumvətəndaş pasportumu yüklə və WhatsApp-da Rəhmana göndər."))
        assertEquals("Rahman", PassportShare.recipient("xarici pasportumu Rahmana whatsappda at"))
        assertEquals("Rahman", PassportShare.recipient("Pasportu Rahman-a göndər"))
        assertEquals("Leyla", PassportShare.recipient("Pasportumu Leylaya göndər"))
        assertEquals("", PassportShare.recipient("pasportumu yüklə və whatsappda göndər"))
    }
    @Test fun picksOnlyTheWhatsAppAppInShareSheet() {
        assertTrue(PassportShare.isWhatsAppTarget(listOf("WhatsApp")))
        assertTrue(PassportShare.isWhatsAppTarget(listOf("WhatsApp", " WhatsApp ")))
        assertFalse(PassportShare.isWhatsAppTarget(listOf("Performance Team 🚀", "WhatsApp")))
        assertFalse(PassportShare.isWhatsAppTarget(listOf("WhatsApp Business")))
    }
}
