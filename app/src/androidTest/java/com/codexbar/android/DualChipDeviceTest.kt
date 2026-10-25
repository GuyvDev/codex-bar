package com.codexbar.android

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.monitoring.*
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DualChipDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun browserLoginModelSurvivesActivityRecreation() {
        androidx.test.core.app.ActivityScenario.launch(PhoneAccountsActivity::class.java).use { scenario ->
            var before: PhoneLoginViewModel? = null
            scenario.onActivity { before = androidx.lifecycle.ViewModelProvider(it)[PhoneLoginViewModel::class.java] }
            scenario.recreate()
            scenario.onActivity {
                assertSame(before, androidx.lifecycle.ViewModelProvider(it)[PhoneLoginViewModel::class.java])
            }
        }
    }

    @Test fun encryptionAndLogoutRejectLateRenewalAndAccountIdReuse() {
        val prefs = EncryptedPrefsManager(context)
        prefs.deleteAllCredentials()
        val old = Credential.ClaudeCredential("synthetic-old-access", "synthetic-old-refresh")
        prefs.saveCredential(AiService.CLAUDE, "device-test", "Claude", old)
        val snapshot = prefs.loadCredentialAccount(AiService.CLAUDE, "device-test")!!
        Thread.sleep(300)
        val xml = java.io.File(context.applicationInfo.dataDir, "shared_prefs/codexbar_secure_prefs.xml")
        assertFalse(xml.readText().contains("synthetic-old-access"))
        prefs.deleteCredential(AiService.CLAUDE, "device-test")
        assertFalse(prefs.saveRefreshed(snapshot, old.copy(accessToken = "late")))
        assertNull(prefs.loadCredentialAccount(AiService.CLAUDE, "device-test"))
        prefs.saveCredential(AiService.CLAUDE, "device-test", "Replacement", old.copy(accessToken = "replacement"))
        assertFalse(prefs.saveRefreshed(snapshot, old.copy(accessToken = "late")))
        assertEquals("replacement", prefs.loadCredentialAccount(AiService.CLAUDE, "device-test")!!.credential.accessToken)
        prefs.deleteAllCredentials()
    }

    @Test fun combinedChipKeepsBothQuotasVisibleAndLogoutCannotRepostIt() {
        dualNotificationsHaveIndependentIdentityAndPromotionRequests()
        val prefs = EncryptedPrefsManager(context)
        val chips = ChipNotifications(context, prefs).apply { enabled = true }
        val openai = prefs.loadCredentialAccounts(AiService.CODEX).first()
        val claude = prefs.loadCredentialAccounts(AiService.CLAUDE).first()
        fun reading(account: CredentialAccount, used: Double) = AccountReading(account,
            QuotaInfo(account.service, account.id, account.label, listOf(UsageWindow("5-Hour", used, null)), null, fetchedAt = Instant.now()))
        val readings = listOf(reading(openai, .20), reading(claude, .35))
        prefs.setCombinedChip(true)
        val notification = chips.combinedNotification(readings[0], readings[1])
        if (Build.VERSION.SDK_INT >= 36) {
            assertEquals("80/65", notification.shortCriticalText)
            assertTrue(notification.hasPromotableCharacteristics())
        }
        chips.publish(readings)
        // Also prove that a stale snapshot cannot restore a logged-out account notification.
        prefs.deleteAllCredentials()
        chips.publish(readings)
        Thread.sleep(300)
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == ChipNotifications.ACCOUNT_ID })
        // Recreate only synthetic readings for visual inspection of the fallback.
        prefs.saveCredential(AiService.CODEX, openai.id, "OpenAI demo", openai.credential)
        prefs.saveCredential(AiService.CLAUDE, claude.id, "Claude demo", claude.credential)
        chips.publish(listOf(reading(prefs.loadCredentialAccounts(AiService.CODEX).first(), .20),
            reading(prefs.loadCredentialAccounts(AiService.CLAUDE).first(), .35)))
    }

    @Test fun loopbackRejectsWrongStateAndRemainsCancellable() {
        val client = com.codexbar.android.core.security.PhoneLoginClient()
        val opened = java.util.concurrent.CompletableFuture<String>()
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val job = pool.submit { runCatching { client.claude { opened.complete(it) } } }
        try {
            val url = opened.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(url.startsWith("https://claude.ai/oauth/authorize?"))
            java.net.Socket("127.0.0.1", 53692).use { socket ->
                socket.soTimeout = 5000
                socket.getOutputStream().write("GET /callback?code=synthetic&state=wrong HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().contains("400 Bad Request"))
            }
            assertFalse(job.isDone)
        } finally { client.cancel(); job.get(5, java.util.concurrent.TimeUnit.SECONDS); pool.shutdownNow() }
    }

    @Test fun dualNotificationsHaveIndependentIdentityAndPromotionRequests() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val prefs = EncryptedPrefsManager(context)
        prefs.deleteAllCredentials()
        prefs.saveCredential(AiService.CODEX, "proof-openai", "OpenAI demo", Credential.CodexCredential("synthetic", "synthetic"))
        prefs.saveCredential(AiService.CLAUDE, "proof-claude", "Claude demo", Credential.ClaudeCredential("synthetic", "synthetic"))
        prefs.setCombinedChip(false)
        val chips = ChipNotifications(context, prefs).apply { enabled = true }
        val accounts = listOf(AiService.CODEX, AiService.CLAUDE).map { prefs.loadCredentialAccounts(it).first() }
        val readings = accounts.mapIndexed { index, account ->
            AccountReading(account, QuotaInfo(account.service, account.id, account.label,
                windows = listOf(UsageWindow("5-Hour", if (index == 0) .20 else .35, null)),
                extraUsage = null, fetchedAt = Instant.now()))
        }
        for (reading in readings) {
            val notification = chips.accountNotification(reading)
            assertNull(notification.group)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(notification.hasPromotableCharacteristics())
            }
        }
        chips.publish(readings)
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 5000
        while (manager.activeNotifications.count { it.id == ChipNotifications.ACCOUNT_ID } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val active = manager.activeNotifications.filter { it.id == ChipNotifications.ACCOUNT_ID }
        assertEquals(2, active.size)
        assertEquals(2, active.map { it.tag }.distinct().size)
        assertTrue(active.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("80%") })
        assertTrue(active.any { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("65%") })
        // Leave synthetic notifications for an external screenshot; no real account is used.
    }
}
