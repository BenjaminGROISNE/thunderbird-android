package net.thunderbird.feature.changelog.internal.ui

import app.k9mail.core.ui.compose.testing.mvi.assertThatAndMviTurbinesConsumed
import app.k9mail.core.ui.compose.testing.mvi.runMviTest
import app.k9mail.core.ui.compose.testing.mvi.turbinesWithInitialStateCheck
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import net.thunderbird.components.ui.testing.coroutines.MainDispatcherHelper
import net.thunderbird.core.common.appConfig.PlatformConfigProvider
import net.thunderbird.core.preference.GeneralSettings
import net.thunderbird.core.preference.GeneralSettingsManager
import net.thunderbird.feature.changelog.internal.ChangeLogManager
import net.thunderbird.feature.changelog.internal.ChangelogVersionHistory
import net.thunderbird.feature.changelog.internal.ui.RecentChangesContract.Event
import net.thunderbird.feature.changelog.internal.ui.RecentChangesContract.State
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

// ChangeLogManager and ChangelogVersionHistory are final classes that need a Context and storage, so they are mocked.
class RecentChangesViewModelTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mainDispatcher = MainDispatcherHelper(UnconfinedTestDispatcher())

    private val changelogFlow = MutableStateFlow(changelogAfterUpdate())
    private val changeLogManager = mock<ChangeLogManager> {
        on { changelogFlow } doReturn changelogFlow
    }
    private val generalSettingsManager = FakeGeneralSettingsManager()

    @BeforeTest
    fun setUp() {
        mainDispatcher.setUp()
    }

    @AfterTest
    fun tearDown() {
        mainDispatcher.tearDown()
    }

    @Test
    fun `should show the hint when recent changes get enabled after an update`() = runMviTest {
        // Arrange
        generalSettingsManager.setShowRecentChanges(false)
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State(showRecentChangesHint = false))

        // Act
        generalSettingsManager.setShowRecentChanges(true)

        // Assert
        assertThatAndMviTurbinesConsumed(actual = turbines.awaitStateItem(), turbines = turbines) {
            isEqualTo(State(showRecentChangesHint = true))
        }
    }

    @Test
    fun `should hide the hint when recent changes get disabled`() = runMviTest {
        // Arrange
        generalSettingsManager.setShowRecentChanges(true)
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State(showRecentChangesHint = true))

        // Act
        generalSettingsManager.setShowRecentChanges(false)

        // Assert
        assertThatAndMviTurbinesConsumed(actual = turbines.awaitStateItem(), turbines = turbines) {
            isEqualTo(State(showRecentChangesHint = false))
        }
    }

    @Test
    fun `should hide the hint on the first run ever`() = runMviTest {
        // Arrange
        generalSettingsManager.setShowRecentChanges(true)
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State(showRecentChangesHint = true))

        // Act
        changelogFlow.value = changelog(isFirstRun = true, isFirstRunEver = true)

        // Assert
        assertThatAndMviTurbinesConsumed(actual = turbines.awaitStateItem(), turbines = turbines) {
            isEqualTo(State(showRecentChangesHint = false))
        }
    }

    @Test
    fun `should hide the hint when the version did not change`() = runMviTest {
        // Arrange
        generalSettingsManager.setShowRecentChanges(true)
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State(showRecentChangesHint = true))

        // Act
        changelogFlow.value = changelog(isFirstRun = false, isFirstRunEver = false)

        // Assert
        assertThatAndMviTurbinesConsumed(actual = turbines.awaitStateItem(), turbines = turbines) {
            isEqualTo(State(showRecentChangesHint = false))
        }
    }

    @Test
    fun `should write the current version when the hint is dismissed`() = runMviTest {
        // Arrange
        generalSettingsManager.setShowRecentChanges(true)
        val testSubject = createTestSubject()
        val turbines = turbinesWithInitialStateCheck(testSubject, State(showRecentChangesHint = true))

        // Act
        testSubject.event(Event.OnRecentChangesHintDismissed)

        // Assert
        verify(changeLogManager).writeCurrentVersion()
        turbines.stateTurbine.ensureAllEventsConsumed()
        turbines.effectTurbine.ensureAllEventsConsumed()
    }

    private fun createTestSubject() = RecentChangesViewModel(
        generalSettingsManager = generalSettingsManager,
        changeLogManager = changeLogManager,
    )

    private fun changelogAfterUpdate() = changelog(isFirstRun = true, isFirstRunEver = false)

    private fun changelog(isFirstRun: Boolean, isFirstRunEver: Boolean): ChangelogVersionHistory {
        val firstRun = isFirstRun
        val firstRunEver = isFirstRunEver
        return mock {
            on { isFirstRun() } doReturn firstRun
            on { isFirstRunEver() } doReturn firstRunEver
        }
    }
}

private class FakeGeneralSettingsManager : GeneralSettingsManager {
    private val generalSettings =
        MutableStateFlow(GeneralSettings(platformConfigProvider = FakePlatformConfigProvider()))

    fun setShowRecentChanges(showRecentChanges: Boolean) {
        generalSettings.update { settings ->
            settings.copy(
                display = settings.display.copy(
                    miscSettings = settings.display.miscSettings.copy(showRecentChanges = showRecentChanges),
                ),
            )
        }
    }

    @Deprecated("Use PreferenceManager<GeneralSettings>.getConfig() instead")
    override fun getSettings(): GeneralSettings = generalSettings.value

    @Deprecated("Use PreferenceManager<GeneralSettings>.getConfigFlow() instead")
    override fun getSettingsFlow(): Flow<GeneralSettings> = generalSettings

    override fun save(config: GeneralSettings) {
        error("not implemented")
    }

    override fun getConfig(): GeneralSettings = generalSettings.value

    override fun getConfigFlow(): Flow<GeneralSettings> = generalSettings
}

private class FakePlatformConfigProvider : PlatformConfigProvider {
    override val isDebug: Boolean
        get() = true
}
