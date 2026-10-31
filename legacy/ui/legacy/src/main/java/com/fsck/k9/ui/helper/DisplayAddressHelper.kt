package com.fsck.k9.ui.helper

import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager

object DisplayAddressHelper {
    fun shouldShowRecipients(
        outboxFolderManager: OutboxFolderManager,
        account: LegacyAccount,
        folderId: Long,
    ): Boolean {
        return shouldShowRecipients(account, folderId) {
            outboxFolderManager.getOutboxFolderIdSync(account.id)
        }
    }

    /**
     * Same as the variant taking an [OutboxFolderManager], but lets the caller provide the Outbox folder ID.
     *
     * [outboxFolderId] is only called when [folderId] is not one of the account's special folders. Callers that
     * check many messages can cache the value, instead of looking it up once per message.
     */
    inline fun shouldShowRecipients(
        account: LegacyAccount,
        folderId: Long,
        outboxFolderId: () -> Long,
    ): Boolean {
        return when (folderId) {
            account.inboxFolderId -> false
            account.archiveFolderId -> false
            account.spamFolderId -> false
            account.trashFolderId -> false
            account.sentFolderId -> true
            account.draftsFolderId -> true
            outboxFolderId() -> true
            else -> false
        }
    }
}
