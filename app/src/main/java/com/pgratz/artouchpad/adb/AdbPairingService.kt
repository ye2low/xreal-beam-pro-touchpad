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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Collects the six-digit pairing code through a notification rather than a screen of its
// own. Android only advertises the pairing service while its dialog is in the foreground —
// measured: the service disappears from mDNS the moment the dialog is backgrounded — so an
// app that raised its own window to ask for the code would kill the very thing it needs.
// A notification reply leaves the system dialog on screen. This is the same approach
// Shizuku takes, for the same reason.
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val code = intent
            ?.let { RemoteInput.getResultsFromIntent(it) }
            ?.getCharSequence(KEY_CODE)
            ?.toString()

        if (code.isNullOrBlank()) {
            startForeground(
                NOTIFICATION_ID,
                prompt(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            status("Связываю…", ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch {
            val paired = ShizukuBootstrap.pair(this@AdbPairingService, code.trim())
            if (paired.isSuccess) {
                notify(status("Готово, запускаю Shizuku…", ongoing = true))
                val result = ShizukuBootstrap.start(this@AdbPairingService)
                notify(status(describe(result), ongoing = false))
            } else {
                notify(status(paired.exceptionOrNull()?.message ?: "не вышло", ongoing = false))
            }
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun describe(result: ShizukuBootstrap.Result): String = when (result) {
        ShizukuBootstrap.Result.Started, ShizukuBootstrap.Result.AlreadyRunning ->
            "Shizuku работает. Больше сопрягать не нужно."
        ShizukuBootstrap.Result.WirelessDebuggingOff -> "отладка по Wi-Fi выключена"
        ShizukuBootstrap.Result.PairingRequired -> "adbd не принял ключ"
        ShizukuBootstrap.Result.ShizukuNotInstalled -> "Shizuku не установлен"
        is ShizukuBootstrap.Result.Failed -> "не вышло: ${result.reason}"
    }

    private fun prompt(): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("Шесть цифр")
            .build()
        val replyIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, AdbPairingService::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Ввести код",
            replyIntent,
        ).addRemoteInput(remoteInput).build()

        return builder()
            .setContentTitle("Сопряжение с отладкой по Wi-Fi")
            .setContentText("Открой «Подключение с помощью кода» и введи шесть цифр сюда")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Настройки → Для разработчиков → Отладка по Wi-Fi → " +
                        "Подключение с помощью кода. Оставь окно с кодом открытым и введи " +
                        "цифры прямо здесь — иначе система свернёт сопряжение."
                )
            )
            .addAction(action)
            .setOngoing(true)
            .build()
    }

    private fun status(text: String, ongoing: Boolean): Notification =
        builder().setContentTitle("Сопряжение").setContentText(text).setOngoing(ongoing).build()

    private fun builder() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
        .setPriority(NotificationCompat.PRIORITY_HIGH)

    private fun notify(notification: Notification) =
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)

    private fun createChannel() =
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Сопряжение", NotificationManager.IMPORTANCE_HIGH)
        )

    companion object {
        private const val CHANNEL = "adb_pairing"
        private const val NOTIFICATION_ID = 4711
        private const val KEY_CODE = "pairing_code"

        fun start(context: Context) =
            context.startForegroundService(Intent(context, AdbPairingService::class.java))
    }
}
