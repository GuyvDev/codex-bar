package com.codexbar.android

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.monitoring.ChipNotifications
import com.codexbar.android.core.monitoring.LiveMonitorService
import com.codexbar.android.core.monitoring.LiveQuotaState
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.PhoneLoginClient
import com.codexbar.android.core.security.LoginHttpError
import com.codexbar.android.core.workmanager.RefreshScheduler
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*

@AndroidEntryPoint
class PhoneAccountsActivity : ComponentActivity() {
    @Inject lateinit var prefs: EncryptedPrefsManager
    @Inject lateinit var chips: ChipNotifications
    @Inject lateinit var readings: LiveQuotaState
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var login: PhoneLoginClient? = null
    private var loginJob: Job? = null
    private var copiedCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        render()
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized && loginJob?.isActive != true) render() }

    private fun text(value: String, size: Float = 16f): TextView = TextView(this).apply {
        text = value; textSize = size; setPadding(0, 12, 0, 12)
    }
    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }

    private fun render() {
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 64, 32, 40) }
        content.addView(text("AI Quota", 30f))
        content.addView(text("OpenAI + Claude · on this phone", 18f))
        content.addView(text("Each chip shows the remaining quota of the first usage window (usually 5 hours). Weekly limits are shown in the dashboard. Android controls chip visibility and may hide text when space is limited."))
        for (service in listOf(AiService.CODEX, AiService.CLAUDE)) {
            val account = prefs.loadCredentialAccounts(service).firstOrNull()
            val provider = if (service == AiService.CODEX) "OpenAI / Codex" else "Claude"
            content.addView(text("$provider — ${account?.label ?: "not connected"}", 21f))
            content.addView(button(if (account == null) "Connect $provider" else "Reconnect $provider") {
                if (service == AiService.CLAUDE) {
                    AlertDialog.Builder(this).setTitle("Connect Claude on this phone")
                        .setMessage("Sign in on claude.ai in your browser. AI Quota requests the profile permission to read usage and stores its own access/refresh tokens encrypted on this phone. This is an unofficial integration using the public Claude OAuth client; Anthropic may reject or change it. No computer or API key is used.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> connect(service) }.show()
                } else connect(service)
            })
            if (account != null) content.addView(button("Disconnect $provider") {
                AlertDialog.Builder(this).setTitle("Disconnect $provider?")
                    .setMessage("Remove local credentials and cached usage for this provider. This does not revoke credentials on the provider's server.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Disconnect") { _, _ ->
                        login?.cancel(); loginJob?.cancel()
                        prefs.deleteCredential(service)
                        chips.clear()
                        chips.publish(readings.readings())
                        render()
                    }.show()
            })
        }
        content.addView(text("Live monitoring", 22f))
        content.addView(text("About once per minute while the monitor is running. Provider rate limits, Doze, lost connectivity and GrapheneOS network permissions can delay updates. No push feed is available. A ~ prefix means an old or failed reading."))
        content.addView(button("Start live monitoring") { startLive() })
        content.addView(button("Stop live monitoring") { stopService(Intent(this, LiveMonitorService::class.java)); chips.clear(); status.text = "Live monitoring stopped" })
        content.addView(button("Allow status-bar chips") {
            val normal = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            if (Build.VERSION.SDK_INT >= 36) {
                runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                    .onFailure { startActivity(normal) }
            } else startActivity(normal)
        })
        content.addView(button("Usage dashboard & account names") { startActivity(Intent(this, MainActivity::class.java)) })
        content.addView(button("Cancel sign-in") { login?.cancel(); loginJob?.cancel(); clearCopiedCode(); status.text = "Sign-in cancelled" })
        status = text("No developer server, analytics or Google Play services are required.")
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun startLive() {
        if (listOf(AiService.CODEX, AiService.CLAUDE).none { prefs.hasCredential(it) }) { status.text = "Connect an account first"; return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 18)
            status.text = "Allow notifications, then tap Start live monitoring again"; return
        }
        val intent = Intent(this, LiveMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        val promoted = Build.VERSION.SDK_INT < 36 || getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
        status.text = if (promoted) "Live monitor started. Open another app to see eligible chips." else "Monitor started. Tap Allow status-bar chips to enable promoted notifications."
    }

    private fun connect(service: AiService) {
        if (loginJob?.isActive == true) { status.text = "Finish or cancel the current sign-in first"; return }
        val epoch = prefs.sessionEpoch()
        val client = PhoneLoginClient().also { login = it }
        status.text = "Complete sign-in in the official provider page in your browser"
        loginJob = lifecycleScope.launch {
            try {
                val credential = withContext(Dispatchers.IO) {
                    if (service == AiService.CLAUDE) client.claude { url ->
                        runOnUiThread { openBrowser(url) }
                    } else client.codex { code, url ->
                        runOnUiThread {
                            status.text = "OpenAI one-time code: $code"
                            AlertDialog.Builder(this@PhoneAccountsActivity).setTitle("OpenAI device sign-in")
                                .setMessage("One-time code: $code\n\nVerify auth.openai.com in the browser. Enter this code only for the sign-in you just started.")
                                .setNegativeButton("Cancel") { _, _ -> client.cancel() }
                                .setPositiveButton("Copy code and open sign-in") { _, _ ->
                                    val clip = ClipData.newPlainText("One-time sign-in code", code)
                                    if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                                    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                                    copiedCode = code
                                    openBrowser(url)
                                }.show()
                        }
                    }
                }
                ensureActive()
                check(prefs.saveNewIfEpoch(service, credential, epoch)) { "Accounts changed while signing in; retry" }
                clearCopiedCode()
                RefreshScheduler.enqueueManualQuotaRefresh(this@PhoneAccountsActivity, "phone_signin")
                render()
                status.text = "Connected. Start live monitoring to read and display usage."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status.text = when (e) {
                    is LoginHttpError -> "Provider rejected sign-in (HTTP ${e.status}). No account was connected."
                    is java.net.BindException -> "Another sign-in is using the local callback port. Cancel it and retry."
                    else -> "Sign-in did not complete. Check connectivity and retry. No response body or credential is shown."
                }
            } finally { clearCopiedCode(); client.cancel(); login = null }
        }
    }

    private fun openBrowser(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { login?.cancel(); status.text = "No browser is available" }
    }

    private fun clearCopiedCode() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (copiedCode != null && clipboard.primaryClip?.getItemAt(0)?.text?.toString() == copiedCode) {
            if (Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        copiedCode = null
    }

    override fun onDestroy() { login?.cancel(); clearCopiedCode(); super.onDestroy() }
}
