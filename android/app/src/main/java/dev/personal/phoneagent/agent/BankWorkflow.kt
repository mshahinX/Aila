package dev.personal.phoneagent.agent

import java.math.BigDecimal

enum class BankFlow(val label: String) { BILL("Utility bill"), CARD("Card to card"), OWN("Between my own accounts") }

data class BankPreparation(val flow: BankFlow, val destinationAlias: String, val sourceAlias: String,
    val amount: String, val currency: String, val provider: String = "") {
    fun validate(): String? {
        if(destinationAlias.isBlank()) return "Write who it goes to (a name or nickname)."
        if(sourceAlias.isBlank()) return "Write which card or account it comes from (e.g. Main card)."
        if(listOf(destinationAlias,sourceAlias,provider).any { Privacy.containsSensitiveIdentifier(it) })
            return "Write only a name. Enter full card and account numbers directly in the bank app."
        val number=runCatching { BigDecimal(amount.replace(',','.')) }.getOrNull() ?: return "Enter a valid amount."
        if(number<=BigDecimal.ZERO || number.scale()>2 || number>BigDecimal("999999.99")) return "The amount must be positive with at most two decimals."
        if(currency !in setOf("AZN","USD","EUR")) return "Choose the currency."
        if(flow==BankFlow.BILL && provider.isBlank()) return "Write the service provider."
        return null
    }
    fun review(): String = "${flow.label}\nFrom: $sourceAlias\nTo: $destinationAlias\nAmount: $amount $currency"+
        (if(provider.isBlank()) "" else "\nProvider: $provider")+
        "\n\nThis is a checklist on your phone, not a bank transaction.\n\nIn Kapital Bank, check the recipient, account, amount, currency and fee yourself. Aila has not checked these bank details. You do the final confirmation."
}
