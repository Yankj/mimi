package com.heydusk.mimi.feature.parenting.ui

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.heydusk.mimi.data.account.LocalIdentityStore
import com.heydusk.mimi.data.ledger.AesGcmLedgerCipher
import com.heydusk.mimi.data.ledger.FileLedgerBlobStore
import com.heydusk.mimi.data.ledger.KeystoreLedgerKeyProvider
import com.heydusk.mimi.data.ledger.LedgerBlob
import com.heydusk.mimi.data.ledger.LedgerBlobStore
import com.heydusk.mimi.data.prefs.MimiDataStore
import com.heydusk.mimi.designsystem.MimiTheme
import com.heydusk.mimi.domain.account.AccountEpoch
import com.heydusk.mimi.domain.parenting.ParentingWorkspace
import com.heydusk.mimi.domain.theme.Appearance
import com.heydusk.mimi.domain.theme.Palette
import com.heydusk.mimi.domain.theme.ThemePreferences
import com.heydusk.mimi.feature.parenting.ParentingHomeScreen
import com.heydusk.mimi.feature.parenting.actions.ModelParentingActions
import com.heydusk.mimi.feature.parenting.composer.VoiceDeps
import com.heydusk.mimi.feature.parenting.model.ParentingSession
import com.heydusk.mimi.feature.parenting.model.ParentingWorkspaceModel
import java.io.IOException
import java.nio.file.Files
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/** 把读写按需变成失败的密文存放处：测「保存失败 → 输入保留 → 重试」。 */
class FlakyBlobStore(private val delegate: LedgerBlobStore) : LedgerBlobStore {
    @Volatile var failNextWrites = 0
    override fun read(key: String): LedgerBlob? = delegate.read(key)
    override fun delete(key: String) = delegate.delete(key)
    override fun writeIfUnchanged(key: String, expectedVersion: String?, bytes: ByteArray): String {
        if (failNextWrites > 0) {
            failNextWrites -= 1
            throw IOException("injected_ledger_write_failure")
        }
        return delegate.writeIfUnchanged(key, expectedVersion, bytes)
    }
}

/**
 * 真实栈：DataStore 身份 + AccountEpoch + AES-GCM 加密账本（文件）+ 会话 + 真实 [ParentingHomeScreen]。
 * 「冷启动」= 取消旧作用域、在同一目录与同一密钥库上新建会话并重建整棵 Compose 树；账本只能从磁盘上的密文读回。
 */
class JourneyHarness(
    /** 非 null = 装配本机提醒（假通知中心 + 内存设置）；null = 这个壳不发提醒（M2/M3 旧旅程）。 */
    val reminderCenter: com.heydusk.mimi.device.reminders.FakeReminderCenter? = null,
    private val reminderZone: java.time.ZoneId = ZoneOffset.UTC,
) {
    val reminderSettings = com.heydusk.mimi.device.reminders.InMemoryReminderSettingsStore()
    val reminderMemory = com.heydusk.mimi.device.reminders.InMemoryReminderMemory()
    var reminderOpen by mutableStateOf<com.heydusk.mimi.feature.parenting.reminders.ReminderOpenRequest?>(null)
    var signedOutReminders = 0; private set
    /** 界面请求打开的系统设置页（"notifications" / "exactAlarms" / "battery"）。 */
    val openedSettings = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val dir = Files.createTempDirectory("mimi-journey").toFile()
    private val keyStore = KeyStore.getInstance("PKCS12").apply { load(null, null) }
    private val password = "t".toCharArray()
    private val keys = KeystoreLedgerKeyProvider(
        keyStore,
        generate = { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() },
        store = { a, k -> keyStore.setEntry(a, KeyStore.SecretKeyEntry(k), KeyStore.PasswordProtection(password)) },
        keyPassword = password,
    )
    val blobs = FlakyBlobStore(FileLedgerBlobStore(dir.resolve("ledger")))
    private var scope: CoroutineScope? = null
    private var session by mutableStateOf<ParentingSession?>(null)
    private var generation by mutableIntStateOf(0)

    fun launch() {
        scope?.let { old -> runBlocking { old.coroutineContext[Job]!!.cancelAndJoin() } }
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        val identity = LocalIdentityStore(MimiDataStore.create(dir.resolve(MimiDataStore.FILE_NAME), sc))
        val factory = reminderCenter?.let { center ->
            ParentingSession.ReminderFactory { subject, isCurrent ->
                com.heydusk.mimi.feature.parenting.model.ReminderWiring(
                    scheduler = com.heydusk.mimi.device.reminders.ParentingReminderScheduler(
                        center, subject, { com.heydusk.mimi.domain.common.MimiCalendar(reminderZone) },
                        com.heydusk.mimi.device.reminders.ReminderCopy.PLAIN, reminderMemory, isCurrent,
                    ),
                    settings = reminderSettings,
                    onSignOut = { signedOutReminders += 1; center.remove(center.requests.map { it.id }); reminderSettings.erase() },
                )
            }
        }
        val next = ParentingSession(identity, AccountEpoch(), blobs, AesGcmLedgerCipher(keys), keys, sc, reminders = factory)
        next.start()
        session = next
        generation += 1
    }

    /** 回到前台 / 从系统设置回来。 */
    fun refreshReminders() { runBlocking { model()?.refreshReminders() } }

    fun signOut() { runBlocking { checkNotNull(session).signOutAndStartFresh() } }

    fun close() {
        scope?.let { runBlocking { it.coroutineContext[Job]!!.cancelAndJoin() } }
    }

    fun model(): ParentingWorkspaceModel? = (session?.phase?.value as? ParentingSession.Phase.Ready)?.model
    val workspace: ParentingWorkspace get() = model()?.workspace ?: ParentingWorkspace.EMPTY

    @Composable
    fun Host(
        zone: java.time.ZoneId = ZoneOffset.UTC,
        dark: Boolean = false,
        fontScale: Float = 1f,
        voice: VoiceDeps? = null,
        now: Instant? = null,
    ) {
        val current = session
        if (current != null) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                key(generation) { JourneyContent(current, zone, dark, voice, now, reminderOpen, openedSettings) { reminderOpen = null } }
            }
        }
    }

    /** 用真实账本命令布置测试数据（等价于「已经录过」）；任何一条被拒都立即失败。 */
    fun seed(vararg commands: com.heydusk.mimi.domain.parenting.ParentingCommand) {
        val m = checkNotNull(model()) { "ledger not loaded" }
        for (c in commands) check(runBlocking { m.apply(c) }) { "seed rejected: $c ${m.state.value.error}" }
    }
}

@Composable
private fun JourneyContent(
    current: ParentingSession, zone: java.time.ZoneId, dark: Boolean, voice: VoiceDeps?, now: Instant?,
    reminderOpen: com.heydusk.mimi.feature.parenting.reminders.ReminderOpenRequest?, openedSettings: MutableList<String>,
    onReminderOpenHandled: () -> Unit,
) {
    val phase by current.phase.collectAsState()
    val ready = phase as? ParentingSession.Phase.Ready
    val modelState = ready?.model?.state?.collectAsState()
    val st = modelState?.value
    val actions = remember(current) {
        ModelParentingActions({ (current.phase.value as? ParentingSession.Phase.Ready)?.model })
    }
    MimiTheme(palette = Palette.default, appearance = if (dark) Appearance.Dark else Appearance.Light) {
        ParentingHomeScreen(
            preferences = ThemePreferences(),
            onPreferencesChange = {},
            workspace = st?.workspace,
            loaded = st?.loaded == true,
            error = st?.error,
            onboardingDone = true,
            privacyShield = false,
            undoSeconds = if (st?.undo != null) 3 else null,
            versionName = "0.1.0",
            versionCode = 1,
            actions = actions,
            onCompleteOnboarding = {},
            onClearData = {},
            onSignOut = {},
            zone = zone,
            voice = voice,
            busy = st?.busy == true,
            now = now ?: Instant.now(),
            reminders = if (ready?.model?.remindersAvailable == true && st != null) {
                com.heydusk.mimi.feature.parenting.reminders.RemindersUi(st.reminderSettings, st.reminderStatus, st.busy, st.loaded)
            } else null,
            reminderActions = remember(current) {
                object : com.heydusk.mimi.feature.parenting.reminders.ModelRemindersActions({ (current.phase.value as? ParentingSession.Phase.Ready)?.model }) {
                    override fun openNotificationSettings() { openedSettings += "notifications" }
                    override fun openExactAlarmSettings() { openedSettings += "exactAlarms" }
                    override fun openBatterySettings() { openedSettings += "battery" }
                }
            },
            reminderOpen = reminderOpen,
            onReminderOpenHandled = onReminderOpenHandled,
        )
    }
}

/** 旅程测试的公共动作：按 tag 点 / 输 / 读，必要时先滚到可见；写入在 IO 线程，所以等待时反复放行主 Looper。 */
class UiDriver(private val rule: ComposeContentTestRule) {
    fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError("timed out waiting for: $what; tags=" + visibleTags())
    }

    private fun visibleTags(): String = rule.onAllNodes(SemanticsMatcher("any") { true }).fetchSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.joinToString(",")

    fun exists(tag: String): Boolean = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    fun count(tag: String): Int = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    fun countPrefix(prefix: String): Int =
        rule.onAllNodes(SemanticsMatcher("tag starts with $prefix") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
        }).fetchSemanticsNodes().size

    fun node(tag: String): SemanticsNodeInteraction = rule.onNodeWithTag(tag)

    fun reveal(tag: String): SemanticsNodeInteraction {
        val n = rule.onNodeWithTag(tag)
        runCatching { n.performScrollTo() }
        return n
    }

    fun click(tag: String) {
        await("tag $tag") { exists(tag) }
        reveal(tag).performClick()
        rule.waitForIdle()
    }

    fun type(tag: String, text: String) {
        await("tag $tag") { exists(tag) }
        reveal(tag).performTextReplacement(text)
    }

    fun text(tag: String): String {
        await("tag $tag") { exists(tag) }
        val config = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        config.getOrNull(SemanticsProperties.EditableText)?.let { return it.text }
        return config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: ""
    }

    fun top(tag: String): Float { await("tag $tag") { exists(tag) }; return rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top }
    fun bottom(tag: String): Float { await("tag $tag") { exists(tag) }; return rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom }

    fun clickable(tag: String): Boolean = exists(tag) &&
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null
}

internal fun nowInstant(): Instant = Instant.now()
