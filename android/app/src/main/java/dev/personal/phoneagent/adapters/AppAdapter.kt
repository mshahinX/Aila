package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

interface AppAdapter {
    val packageName: String
    fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision
}

class AdapterRegistry {
    private val adapters = listOf(WhatsAppAdapter(), MyGovAdapter(), KapitalBankAdapter(), AbbAdapter(), FixtureAdapter())
        .associateBy { it.packageName }
    fun adapter(packageName: String) = adapters[packageName]
    val capabilities = listOf(
        "WhatsApp · restricted navigation/draft/send; native approval required; device testing pending",
        "mygov · launch; unverified screens require manual handling",
        "Kapital Bank · bill / card / own-account preparation; manual final confirmation",
        "ABB · AI chat (bot channel); top-up/transfer form filling, owner taps final confirm"
    )
}
