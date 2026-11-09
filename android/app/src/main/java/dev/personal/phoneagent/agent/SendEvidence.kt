package dev.personal.phoneagent.agent

/** Conservative local evidence: a new exact message and its own, isolated outgoing status. */
object SendEvidence {
    fun whatsappStatus(before: UiSnapshot, after: UiSnapshot, recipient: String, text: String): String? {
        if(before.packageName!=WHATSAPP || after.packageName!=WHATSAPP || after.sensitive || after.truncated) return null
        if(before.only("conversation_contact_name")?.text!=recipient || after.only("conversation_contact_name")?.text!=recipient) return null
        val entry=after.only("entry") ?: return null
        if(entry.text.isNotEmpty()) return null
        val old=before.withIdSuffix("message_text").count { it.text==text }
        val matches=after.withIdSuffix("message_text").filter { it.text==text }
        if(matches.size!=old+1) return null
        var path=matches.last().id.substringBeforeLast('.',"")
        while(path.isNotEmpty() && path!="0") {
            val row=after.nodes.filter { it.id==path || it.id.startsWith("$path.") }
            // Never borrow a status from a neighbouring message or the conversation container.
            if(row.count { it.resourceId=="$WHATSAPP:id/message_text" }>1) return null
            val statuses=row.filter { it.resourceId=="$WHATSAPP:id/status" }
            if(statuses.size==1) return statuses.single().description.lowercase().takeIf { it in setOf("sent","delivered","read") }
            if(statuses.size>1) return null
            path=path.substringBeforeLast('.',"")
        }
        return null
    }
}
