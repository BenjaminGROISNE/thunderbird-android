package com.fsck.k9.ui.messagelist

import android.net.Uri
import app.k9mail.core.android.common.contact.Contact
import app.k9mail.core.android.common.contact.ContactRepository
import app.k9mail.legacy.mailstore.MessageDetailsAccessor
import app.k9mail.legacy.message.extractors.PreviewResult
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import com.fsck.k9.CoreResourceProvider
import com.fsck.k9.FakeLegacyAccount
import com.fsck.k9.TestCoreResourceProvider
import com.fsck.k9.helper.MessageHelper
import com.fsck.k9.mail.Address
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.testing.RobolectricTest
import net.thunderbird.core.common.mail.EmailAddress
import net.thunderbird.core.preference.display.visualSettings.message.list.DisplayMessageListSettings
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListPreferencesManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import org.junit.Test

private const val INBOX_FOLDER_ID = 1L
private const val OUTBOX_FOLDER_ID = 2L
private const val REGULAR_FOLDER_ID = 3L

class MessageListItemMapperTest : RobolectricTest() {
    private val account = FakeLegacyAccount.create().copy(inboxFolderId = INBOX_FOLDER_ID)
    private val outboxFolderManager = CountingOutboxFolderManager(OUTBOX_FOLDER_ID)
    private val preferencesManager = FakeMessageListPreferencesManager()

    private val testSubject = MessageListItemMapper(
        messageHelper = MessageHelper(FakeCoreResourceProvider(), NotUsedContactRepository(), preferencesManager),
        account = account,
        messageListPreferencesManager = preferencesManager,
        outboxFolderManager = outboxFolderManager,
        contactLetterBitmapCreator = null,
    )

    @Test
    fun `should look up the outbox folder only once for many messages in a regular folder`() {
        val messages = (1L..50L).map { id -> FakeMessage(id = id, folderId = REGULAR_FOLDER_ID) }

        val items = messages.map { testSubject.map(it) }

        assertThat(outboxFolderManager.syncCalls).isEqualTo(1)
        assertThat(items.map { it.displayName.toString() }.distinct()).containsExactly("Sender")
    }

    @Test
    fun `should not look up the outbox folder for messages in the inbox`() {
        val messages = (1L..5L).map { id -> FakeMessage(id = id, folderId = INBOX_FOLDER_ID) }

        messages.forEach { testSubject.map(it) }

        assertThat(outboxFolderManager.syncCalls).isEqualTo(0)
    }

    @Test
    fun `should show the recipient for messages in the outbox`() {
        val item = testSubject.map(FakeMessage(id = 1L, folderId = OUTBOX_FOLDER_ID))

        assertThat(item.displayName.toString()).isEqualTo("To: Recipient")
        assertThat(item.displayAddress).isEqualTo(RECIPIENT)
        assertThat(outboxFolderManager.syncCalls).isEqualTo(1)
    }

    private class CountingOutboxFolderManager(private val outboxFolderId: Long) : OutboxFolderManager {
        var syncCalls = 0
            private set

        override suspend fun getOutboxFolderId(accountId: AccountId, createIfMissing: Boolean): Long = outboxFolderId

        override fun getOutboxFolderIdSync(accountId: AccountId, createIfMissing: Boolean): Long {
            syncCalls++
            return outboxFolderId
        }

        override suspend fun createOutboxFolder(accountId: AccountId): Outcome<Long, Exception> =
            error("Not used in this test")

        override suspend fun hasPendingMessages(accountId: AccountId): Boolean = error("Not used in this test")
    }

    private class FakeCoreResourceProvider : CoreResourceProvider by TestCoreResourceProvider() {
        override fun contactDisplayNamePrefix() = "To:"
    }

    private class FakeMessageListPreferencesManager : MessageListPreferencesManager {
        private val settings = DisplayMessageListSettings(isChangeContactNameColor = false)

        override fun save(config: DisplayMessageListSettings) = error("Not used in this test")
        override fun getConfig(): DisplayMessageListSettings = settings
        override fun getConfigFlow(): Flow<DisplayMessageListSettings> = flowOf(settings)
    }

    private class NotUsedContactRepository : ContactRepository {
        override fun getContactFor(emailAddress: EmailAddress): Contact? = error("Not used in this test")
        override fun hasContactFor(emailAddress: EmailAddress): Boolean = error("Not used in this test")
        override fun hasAnyContactFor(emailAddresses: List<EmailAddress>): Boolean = error("Not used in this test")
        override fun getPhotoUri(emailAddress: String): Uri? = error("Not used in this test")
    }

    private data class FakeMessage(
        override val id: Long,
        override val folderId: Long,
    ) : MessageDetailsAccessor {
        override val messageServerId = "uid-$id"
        override val fromAddresses = listOf(SENDER)
        override val toAddresses = listOf(RECIPIENT)
        override val ccAddresses = emptyList<Address>()
        override val messageDate = 0L
        override val internalDate = 0L
        override val subject = "Subject $id"
        override val preview: PreviewResult = PreviewResult.none()
        override val isRead = false
        override val isStarred = false
        override val isAnswered = false
        override val isForwarded = false
        override val hasAttachments = false
        override val threadRoot = id
        override val threadCount = 0
    }

    private companion object {
        val SENDER = Address("sender@domain.example", "Sender")
        val RECIPIENT = Address("recipient@domain.example", "Recipient")
    }
}
