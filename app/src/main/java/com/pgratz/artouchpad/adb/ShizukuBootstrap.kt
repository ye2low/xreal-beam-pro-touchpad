// Copyright 2026 Paul Gratz
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.pgratz.artouchpad.adb

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Starts Shizuku without a computer.
//
// Shizuku hands out shell-uid privileges, but it can only be started *by* something that
// already has them, so after every reboot it is off and the touchpad has no cursor.
// Shizuku's own "start on boot" needs root. The way out is the one the phone already
// offers: adbd is listening on this very device, and an app that holds a paired key may
// talk to it over loopback like any other ADB client and run the Shizuku starter.
//
// Pairing is one-time — adbd keeps the public key in /data/misc/adb/adb_keys across
// reboots, and refreshes its timestamp on every connection, so it never expires while the
// app keeps using it.
object ShizukuBootstrap {

    private const val TAG = "ShizukuBootstrap"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PREFS = "adb_bootstrap"
    private const val KEY_PAIRED = "paired"

    sealed interface Result {
        data object Started : Result
        data object AlreadyRunning : Result
        // Wireless debugging is off; only the user can switch it on.
        data object WirelessDebuggingOff : Result
        // No paired key yet, or adbd rejected the one we have.
        data object PairingRequired : Result
        data object ShizukuNotInstalled : Result
        data class Failed(val reason: String) : Result
    }

    fun isPaired(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAIRED, false)

    fun isWirelessDebuggingOn(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    // Finds the port adbd advertises for pairing. It is only published while the
    // "Pair device with pairing code" dialog is open, and it differs from the connect port.
    private suspend fun discoverPort(context: Context, serviceType: String, timeoutMs: Long): Int =
        withContext(Dispatchers.IO) {
            var found = -1
            val latch = CountDownLatch(1)
            val mdns = AdbMdns(context, serviceType) { _, port ->
                if (port > 0 && found < 0) {
                    found = port
                    latch.countDown()
                }
            }
            mdns.start()
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                mdns.stop()
            }
            found
        }

    // One-time step: the user opens "Pair device with pairing code" in developer options and
    // types the six digits here. On success adbd trusts this app's key from now on.
    suspend fun pair(context: Context, pairingCode: String): kotlin.Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val port = discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 60_000)
                require(port > 0) { "не нашёл службу сопряжения — открыт ли диалог с кодом?" }
                val manager = AdbConnectionManager.getInstance(context)
                // The pairing server binds the Wi-Fi address, not loopback.
                val host = AndroidUtils.getHostIpAddress(context)
                check(manager.pair(host, port, pairingCode)) { "код не подошёл" }
                prefs(context).edit().putBoolean(KEY_PAIRED, true).apply()
            }
        }

    // The whole point of the class: get Shizuku running, unattended.
    suspend fun start(context: Context): Result = withContext(Dispatchers.IO) {
        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return@withContext Result.AlreadyRunning
        }

        val shizuku = try {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return@withContext Result.ShizukuNotInstalled
        }

        // Only the user can switch wireless debugging on; it is a secure setting. It does
        // survive reboots on this phone, so in practice this check passes silently.
        if (!isWirelessDebuggingOn(context)) return@withContext Result.WirelessDebuggingOff

        if (!isPaired(context)) return@withContext Result.PairingRequired

        val manager = AdbConnectionManager.getInstance(context)
        try {
            val connected = try {
                withTimeoutOrNull(45_000) { manager.autoConnect(context, 30_000) } ?: false
            } catch (e: AdbPairingRequiredException) {
                // adbd has forgotten our key — the only case where pairing again is the
                // right advice. A plain connection failure is not: it usually means adbd
                // has not finished publishing its port yet, and telling the user to re-pair
                // would send them off to fix something that is not broken.
                return@withContext Result.PairingRequired
            }
            if (!connected) return@withContext Result.Failed("adb не отозвался")

            // Exactly what Shizuku's own starter runs; --apk lets the shell-side process
            // load Shizuku's classes out of the installed APK.
            val starter = "${shizuku.nativeLibraryDir}/libshizuku.so --apk=${shizuku.sourceDir}"
            val output = exec(manager, starter)
            Log.i(TAG, "starter output: $output")

            if (output.contains("Enjoy") || output.contains("info: shizuku_starter exit with 0")) {
                Result.Started
            } else {
                Result.Failed(output.takeLast(300).ifBlank { "starter не ответил" })
            }
        } catch (e: Exception) {
            Log.w(TAG, "bootstrap failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { manager.close() }
        }
    }

    private fun exec(manager: AdbConnectionManager, command: String): String =
        manager.openStream("shell:$command").use { stream ->
            stream.openInputStream().bufferedReader().readText()
        }

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
