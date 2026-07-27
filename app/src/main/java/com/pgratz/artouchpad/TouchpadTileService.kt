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

package com.pgratz.artouchpad

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService

// A quick-settings tile that opens the touchpad.
//
// It exists because the home screen can go blank: a window that moves to the glasses may
// leave its task behind on the phone, where it keeps covering everything with an empty
// frame. Icons are then unreachable and the notification shade is the only thing left, so
// the shade is where the way back in has to be.
class TouchpadTileService : TileService() {

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // startActivityAndCollapse is the only route that both closes the shade and is
        // allowed to start an activity from a tile on Android 14.
        startActivityAndCollapse(pending)
    }
}
