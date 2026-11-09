package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

class KapitalBankAdapter : AppAdapter {
    override val packageName = BANK
    override fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision =
        Decision.Manual("Kapital Bank screens have not been calibrated. Complete the preparation checklist and use the bank manually. Financial confirmation, PINs, and OTPs are never automated.")
}
