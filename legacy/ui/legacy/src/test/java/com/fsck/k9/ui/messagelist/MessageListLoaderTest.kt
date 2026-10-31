package com.fsck.k9.ui.messagelist

import android.net.Uri
import app.k9mail.core.android.common.contact.Contact
import app.k9mail.core.android.common.contact.ContactRepository
import app.k9mail.legacy.mailstore.MessageListChangedListener
import app.k9mail.legacy.mailstore.MessageListRepository
import app.k9mail.legacy.mailstore.MessageMapper
import app.k9mail.legacy.message.controller.MessageReference
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import com.fsck.k9.FakeLegacyAccount
import com.fsck.k9.TestCoreResourceProvider
import com.fsck.k9.contacts.ContactLetterBitmapCreator
import com.fsck.k9.helper.MessageHelper
import com.fsck.k9.mail.Address
import com.fsck.k9.mailstore.LocalFolder
import com.fsck.k9.mailstore.LocalStore
import com.fsck.k9.mailstore.LocalStoreProvider
import com.fsck.k9.mailstore.MessageColumns
import kotlinx.coroutines.flow.Flow
import net.thunderbird.components.core.logging.testing.TestLogger
import net.thunderbird.components.core.outcome.Outcome
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.SortType
import net.thunderbird.core.android.account.fake.FakeLegacyAccountManager
import net.thunderbird.core.common.exception.MessagingException
import net.thunderbird.core.common.mail.EmailAddress
import net.thunderbird.core.featureflag.FeatureFlagProvider
import net.thunderbird.core.featureflag.FeatureFlagResult
import net.thunderbird.core.preference.display.visualSettings.message.list.DisplayMessageListSettings
import net.thunderbird.core.preference.display.visualSettings.message.list.MessageListPreferencesManager
import net.thunderbird.feature.account.AccountId
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.feature.mail.folder.api.OutboxFolderManager
import net.thunderbird.feature.search.legacy.LocalMessageSearch
import net.thunderbird.feature.search.legacy.api.MessageSearchField
import net.thunderbird.feature.search.legacy.api.SearchAttribute
import net.thunderbird.feature.search.legacy.sql.SqlWhereClause
import net.thunderbird.legacy.logging.Log
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock

class MessageListLoaderTest {
    private val account = FakeLegacyAccount.create(id = ACCOUNT_ID)
    private val otherAccount = FakeLegacyAccount.create(id = OTHER_ACCOUNT_ID)
    private val messageListRepository = FakeMessageListRepository()
    private var localStoreProvider: LocalStoreProvider = mock()

    private val testSubject
        get() = MessageListLoader(
            accountManager = FakeLegacyAccountManager(listOf(account, otherAccount)),
            localStoreProvider = localStoreProvider,
            messageListRepository = messageListRepository,
            messageHelper = MessageHelper(
                TestCoreResourceProvider(),
                NotUsedContactRepository(),
                NotUsedMessageListPreferencesManager(),
            ),
            messageListPreferencesManager = NotUsedMessageListPreferencesManager(),
            outboxFolderManager = NotUsedOutboxFolderManager(),
            featureFlagProvider = FeatureFlagProvider { FeatureFlagResult.Disabled },
            // Concrete class that needs an Android Context; the loader only passes it on to the mapper.
            contactLetterBitmapCreator = mock<ContactLetterBitmapCreator>(),
        )

    @Before
    fun setUp() {
        Log.logger = TestLogger()
    }

    @Test
    fun `should load flat list with selection built from search conditions`() {
        val search = createSearch(account, FOLDER_ID, OTHER_FOLDER_ID)
        val whereClause = SqlWhereClause.Builder().withConditions(search.conditions).build()

        testSubject.getMessageList(createConfig(search))

        assertThat(messageListRepository.calls).containsExactly(
            RepositoryCall.Messages(
                accountId = ACCOUNT_ID,
                selection = whereClause.selection,
                selectionArgs = whereClause.selectionArgs,
                sortOrder = "${MessageColumns.DATE} DESC, ${MessageColumns.ID} DESC",
            ),
        )
    }

    @Test
    fun `should load threaded list when showing threads`() {
        val search = createSearch(account)
        val whereClause = SqlWhereClause.Builder().withConditions(search.conditions).build()

        testSubject.getMessageList(createConfig(search, showingThreadedList = true))

        assertThat(messageListRepository.calls).containsExactly(
            RepositoryCall.ThreadedMessages(
                accountId = ACCOUNT_ID,
                selection = whereClause.selection,
                selectionArgs = whereClause.selectionArgs,
                sortOrder = "${MessageColumns.DATE} DESC, ${MessageColumns.ID} DESC",
            ),
        )
    }

    @Test
    fun `should load a single thread when search has a thread ID condition`() {
        val search = createSearch(account).apply {
            and(MessageSearchField.THREAD_ID, "42", SearchAttribute.EQUALS)
        }

        testSubject.getMessageList(createConfig(search, showingThreadedList = true))

        assertThat(messageListRepository.calls).containsExactly(
            RepositoryCall.Thread(
                accountId = ACCOUNT_ID,
                threadId = 42L,
                sortOrder = "${MessageColumns.DATE} DESC, ${MessageColumns.ID} DESC",
            ),
        )
    }

    @Test
    fun `should always select the active message of the same account`() {
        val search = createSearch(account, FOLDER_ID, OTHER_FOLDER_ID)
        val whereClause = SqlWhereClause.Builder().withConditions(search.conditions).build()
        val activeMessage = MessageReference(ACCOUNT_ID, folderId = 7L, uid = "uid-1")

        testSubject.getMessageList(createConfig(search, activeMessage = activeMessage))

        val call = messageListRepository.calls.single() as RepositoryCall.Messages
        assertThat(call.selection).isEqualTo(
            "(${MessageColumns.UID} = ? AND ${MessageColumns.FOLDER_ID} = ?) OR (${whereClause.selection})",
        )
        assertThat(call.selectionArgs).isEqualTo(listOf("uid-1", "7") + whereClause.selectionArgs)
    }

    @Test
    fun `should ignore the active message of another account`() {
        val search = createSearch(account, FOLDER_ID, OTHER_FOLDER_ID)
        val whereClause = SqlWhereClause.Builder().withConditions(search.conditions).build()
        val activeMessage = MessageReference(OTHER_ACCOUNT_ID, folderId = 7L, uid = "uid-1")

        testSubject.getMessageList(createConfig(search, activeMessage = activeMessage))

        val call = messageListRepository.calls.single() as RepositoryCall.Messages
        assertThat(call.selection).isEqualTo(whereClause.selection)
        assertThat(call.selectionArgs).isEqualTo(whereClause.selectionArgs)
    }

    @Test
    fun `should build SQL sort order for each sort type`() {
        val expectedSortOrders = mapOf(
            SortType.SORT_DATE to "${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_ARRIVAL to "${MessageColumns.INTERNAL_DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_SUBJECT to
                "${MessageColumns.SUBJECT} COLLATE NOCASE ASC, ${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_SENDER to
                "${MessageColumns.SENDER_LIST} ASC, ${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_UNREAD to "${MessageColumns.READ} ASC, ${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_FLAGGED to
                "(${MessageColumns.FLAGGED} != 1) ASC, ${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
            SortType.SORT_ATTACHMENT to
                "(${MessageColumns.ATTACHMENT_COUNT} < 1) ASC, ${MessageColumns.DATE} ASC, ${MessageColumns.ID} DESC",
        )

        val sortOrders = SortType.entries.associateWith { sortType ->
            messageListRepository.calls.clear()
            val config = createConfig(
                createSearch(account),
                sortType = sortType,
                sortAscending = true,
                sortDateAscending = true,
            )
            testSubject.getMessageList(config)
            (messageListRepository.calls.single() as RepositoryCall.Messages).sortOrder
        }

        assertThat(sortOrders).isEqualTo(expectedSortOrders)
    }

    @Test
    fun `should use descending secondary date sort when date sort is descending`() {
        val config = createConfig(createSearch(account), sortType = SortType.SORT_SUBJECT, sortDateAscending = false)

        testSubject.getMessageList(config)

        val call = messageListRepository.calls.single() as RepositoryCall.Messages
        assertThat(call.sortOrder).isEqualTo(
            "${MessageColumns.SUBJECT} COLLATE NOCASE DESC, ${MessageColumns.DATE} DESC, ${MessageColumns.ID} DESC",
        )
    }

    @Test
    fun `should merge messages of all accounts sorted by date`() {
        messageListRepository.messagesByAccount[ACCOUNT_ID] = listOf(
            createItem(account, databaseId = 1, messageDate = 100),
            createItem(account, databaseId = 2, messageDate = 300),
        )
        messageListRepository.messagesByAccount[OTHER_ACCOUNT_ID] = listOf(
            createItem(otherAccount, databaseId = 3, messageDate = 200),
        )

        val result = testSubject.getMessageList(createConfig(LocalMessageSearch()))

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(2L, 3L, 1L)
        assertThat(result.hasMoreMessages).isFalse()
    }

    @Test
    fun `should sort by arrival date ascending`() {
        givenMessages(
            createItem(account, databaseId = 1, internalDate = 300),
            createItem(account, databaseId = 2, internalDate = 100),
        )

        val result = testSubject.getMessageList(
            createConfig(createSearch(account), sortType = SortType.SORT_ARRIVAL, sortAscending = true),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(2L, 1L)
    }

    @Test
    fun `should sort by subject ignoring case, then by date`() {
        givenMessages(
            createItem(account, databaseId = 1, subject = "beta", messageDate = 100),
            createItem(account, databaseId = 2, subject = "Alpha", messageDate = 100),
            createItem(account, databaseId = 3, subject = "alpha", messageDate = 200),
            createItem(account, databaseId = 4, subject = null, messageDate = 100),
        )

        val result = testSubject.getMessageList(
            createConfig(
                createSearch(account),
                sortType = SortType.SORT_SUBJECT,
                sortAscending = true,
                sortDateAscending = true,
            ),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(4L, 2L, 3L, 1L)
    }

    @Test
    fun `should sort by sender descending, then by date descending`() {
        givenMessages(
            createItem(account, databaseId = 1, displayName = "anna", messageDate = 100),
            createItem(account, databaseId = 2, displayName = "Bob", messageDate = 100),
            createItem(account, databaseId = 3, displayName = "bob", messageDate = 200),
        )

        val result = testSubject.getMessageList(
            createConfig(
                createSearch(account),
                sortType = SortType.SORT_SENDER,
                sortAscending = false,
                sortDateAscending = false,
            ),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `should sort unread first and apply read state overrides`() {
        val readMessage = createItem(account, databaseId = 1, isRead = true, messageDate = 300)
        val unreadMessage = createItem(account, databaseId = 2, isRead = false, messageDate = 200)
        val markedReadMessage = createItem(account, databaseId = 3, isRead = false, messageDate = 100)
        givenMessages(readMessage, unreadMessage, markedReadMessage)
        val sortOverrides = mapOf(
            markedReadMessage.messageReference to MessageSortOverride(isRead = true, isStarred = false),
        )

        val result = testSubject.getMessageList(
            createConfig(
                createSearch(account),
                sortType = SortType.SORT_UNREAD,
                sortAscending = true,
                sortOverrides = sortOverrides,
            ),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(2L, 1L, 3L)
    }

    @Test
    fun `should sort starred first and apply star overrides`() {
        val starredMessage = createItem(account, databaseId = 1, isStarred = true, messageDate = 100)
        val plainMessage = createItem(account, databaseId = 2, isStarred = false, messageDate = 300)
        val newlyStarredMessage = createItem(account, databaseId = 3, isStarred = false, messageDate = 200)
        givenMessages(starredMessage, plainMessage, newlyStarredMessage)
        val sortOverrides = mapOf(
            newlyStarredMessage.messageReference to MessageSortOverride(isRead = false, isStarred = true),
        )

        val result = testSubject.getMessageList(
            createConfig(
                createSearch(account),
                sortType = SortType.SORT_FLAGGED,
                sortAscending = true,
                sortOverrides = sortOverrides,
            ),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(3L, 1L, 2L)
    }

    @Test
    fun `should sort starred last when flagged sort is descending`() {
        givenMessages(
            createItem(account, databaseId = 1, isStarred = true, messageDate = 100),
            createItem(account, databaseId = 2, isStarred = false, messageDate = 200),
        )

        val result = testSubject.getMessageList(
            createConfig(createSearch(account), sortType = SortType.SORT_FLAGGED, sortAscending = false),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(2L, 1L)
    }

    @Test
    fun `should sort messages with attachments first, then by database ID when dates are equal`() {
        givenMessages(
            createItem(account, databaseId = 1, hasAttachments = false),
            createItem(account, databaseId = 2, hasAttachments = true),
            createItem(account, databaseId = 3, hasAttachments = true),
        )

        val result = testSubject.getMessageList(
            createConfig(createSearch(account), sortType = SortType.SORT_ATTACHMENT, sortAscending = true),
        )

        assertThat(result.messageListItems.map { it.databaseId }).containsExactly(3L, 2L, 1L)
    }

    @Test
    fun `should report more messages from the local folder when showing a single folder`() {
        givenLocalFolder(mock { on { hasMoreMessages() } doReturn true })

        val result = testSubject.getMessageList(createConfig(createSearch(account, FOLDER_ID)))

        assertThat(result.hasMoreMessages).isTrue()
    }

    @Test
    fun `should return empty list when the displayed folder cannot be opened`() {
        givenMessages(createItem(account, databaseId = 1))
        givenLocalFolder(
            mock { on { open() } doThrow MessagingException("LocalFolder.open(): Folder not found: null (3)") },
        )

        val result = testSubject.getMessageList(createConfig(createSearch(account, FOLDER_ID)))

        assertThat(result.messageListItems).isEmpty()
        assertThat(result.hasMoreMessages).isFalse()
    }

    @Test
    fun `should return empty list when loading messages fails`() {
        messageListRepository.failure = IllegalStateException("database error")

        val result = testSubject.getMessageList(createConfig(createSearch(account)))

        assertThat(result.messageListItems).isEmpty()
        assertThat(result.hasMoreMessages).isFalse()
    }

    private fun givenMessages(vararg items: MessageListItem) {
        messageListRepository.messagesByAccount[ACCOUNT_ID] = items.toList()
    }

    private fun givenLocalFolder(localFolder: LocalFolder) {
        val localStore = mock<LocalStore> { on { getFolder(FOLDER_ID) } doReturn localFolder }
        localStoreProvider = mock { on { getInstance(ACCOUNT_ID) } doReturn localStore }
    }

    private fun createSearch(account: LegacyAccount, vararg folderIds: Long) = LocalMessageSearch().apply {
        addAccountId(account.id)
        folderIds.forEach { addAllowedFolder(it) }
    }

    @Suppress("LongParameterList")
    private fun createConfig(
        search: LocalMessageSearch,
        showingThreadedList: Boolean = false,
        sortType: SortType = SortType.SORT_DATE,
        sortAscending: Boolean = false,
        sortDateAscending: Boolean = false,
        activeMessage: MessageReference? = null,
        sortOverrides: Map<MessageReference, MessageSortOverride> = emptyMap(),
    ) = MessageListConfig(
        search = search,
        showingThreadedList = showingThreadedList,
        sortType = sortType,
        sortAscending = sortAscending,
        sortDateAscending = sortDateAscending,
        activeMessage = activeMessage,
        sortOverrides = sortOverrides,
    )

    @Suppress("LongParameterList")
    private fun createItem(
        account: LegacyAccount,
        databaseId: Long,
        subject: String? = "subject",
        displayName: String = "sender",
        messageDate: Long = 0L,
        internalDate: Long = 0L,
        isRead: Boolean = false,
        isStarred: Boolean = false,
        hasAttachments: Boolean = false,
    ) = MessageListItem(
        account = account,
        subject = subject,
        threadCount = 0,
        messageDate = messageDate,
        internalDate = internalDate,
        displayName = displayName,
        displayAddress = Address.parse("sender@domain.example").first(),
        displayMessageDateTime = "12:34",
        previewText = "",
        isMessageEncrypted = false,
        isRead = isRead,
        isStarred = isStarred,
        isAnswered = false,
        isForwarded = false,
        hasAttachments = hasAttachments,
        uniqueId = databaseId,
        folderId = FOLDER_ID,
        messageUid = "uid-$databaseId",
        databaseId = databaseId,
        threadRoot = databaseId,
        contactColor = -1,
    )

    private sealed interface RepositoryCall {
        data class Messages(
            val accountId: AccountId,
            val selection: String,
            val selectionArgs: List<String>,
            val sortOrder: String,
        ) : RepositoryCall

        data class ThreadedMessages(
            val accountId: AccountId,
            val selection: String,
            val selectionArgs: List<String>,
            val sortOrder: String,
        ) : RepositoryCall

        data class Thread(val accountId: AccountId, val threadId: Long, val sortOrder: String) : RepositoryCall
    }

    private class FakeMessageListRepository : MessageListRepository {
        val calls = mutableListOf<RepositoryCall>()
        val messagesByAccount = mutableMapOf<AccountId, List<MessageListItem>>()
        var failure: Exception? = null

        override fun addListener(listener: MessageListChangedListener) = error("Not used in this test")

        override fun addListener(
            accountId: AccountId,
            listener: MessageListChangedListener,
        ) = error("Not used in this test")

        override fun removeListener(listener: MessageListChangedListener) = error("Not used in this test")

        override fun notifyMessageListChanged(accountId: AccountId) = error("Not used in this test")

        override fun <T> getMessages(
            accountId: AccountId,
            selection: String,
            selectionArgs: Array<String>,
            sortOrder: String,
            messageMapper: MessageMapper<T>,
        ): List<T> {
            calls.add(RepositoryCall.Messages(accountId, selection, selectionArgs.toList(), sortOrder))
            return messagesFor(accountId)
        }

        override fun <T> getThreadedMessages(
            accountId: AccountId,
            selection: String,
            selectionArgs: Array<String>,
            sortOrder: String,
            messageMapper: MessageMapper<T>,
        ): List<T> {
            calls.add(RepositoryCall.ThreadedMessages(accountId, selection, selectionArgs.toList(), sortOrder))
            return messagesFor(accountId)
        }

        override fun <T> getThread(
            accountId: AccountId,
            threadId: Long,
            sortOrder: String,
            messageMapper: MessageMapper<T>,
        ): List<T> {
            calls.add(RepositoryCall.Thread(accountId, threadId, sortOrder))
            return messagesFor(accountId)
        }

        // The loader always passes a MessageListItemMapper, so T is MessageListItem.
        @Suppress("UNCHECKED_CAST")
        private fun <T> messagesFor(accountId: AccountId): List<T> {
            failure?.let { throw it }
            return messagesByAccount[accountId].orEmpty() as List<T>
        }
    }

    private class NotUsedContactRepository : ContactRepository {
        override fun getContactFor(emailAddress: EmailAddress): Contact? = error("Not used in this test")
        override fun hasContactFor(emailAddress: EmailAddress): Boolean = error("Not used in this test")
        override fun hasAnyContactFor(emailAddresses: List<EmailAddress>): Boolean = error("Not used in this test")
        override fun getPhotoUri(emailAddress: String): Uri? = error("Not used in this test")
    }

    private class NotUsedMessageListPreferencesManager : MessageListPreferencesManager {
        override fun save(config: DisplayMessageListSettings) = error("Not used in this test")
        override fun getConfig(): DisplayMessageListSettings = error("Not used in this test")
        override fun getConfigFlow(): Flow<DisplayMessageListSettings> = error("Not used in this test")
    }

    private class NotUsedOutboxFolderManager : OutboxFolderManager {
        override suspend fun getOutboxFolderId(accountId: AccountId, createIfMissing: Boolean): Long =
            error("Not used in this test")

        override suspend fun createOutboxFolder(accountId: AccountId): Outcome<Long, Exception> =
            error("Not used in this test")

        override suspend fun hasPendingMessages(accountId: AccountId): Boolean = error("Not used in this test")
    }

    private companion object {
        val ACCOUNT_ID: AccountId = AccountIdFactory.of("6b84207b-25de-4dab-97c3-953bbf03fec6")
        val OTHER_ACCOUNT_ID: AccountId = AccountIdFactory.of("d8b2b5f0-6a4c-4ba1-9a52-0a3b1a2c3d4e")
        const val FOLDER_ID = 1L
        const val OTHER_FOLDER_ID = 2L
    }
}
