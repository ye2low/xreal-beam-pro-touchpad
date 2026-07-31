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

import android.util.Log

// Kotlin wrapper for the JNI functions in uinput_jni.cpp.
// Declared as an object (singleton) so the native library is loaded exactly once.
object UinputNative {
    private const val TAG = "UinputNative"

    // Loads libartouchpad.so when the class is first accessed.
    // Primary path: System.loadLibrary(), which works in the normal app process.
    // Fallback: When running as a Shizuku UserService (shell uid), the process
    // may not have the standard library search path configured, so we derive the
    // .so location from the APK path and call System.load() with the full path.
    init {
        var ok = false
        try {
            System.loadLibrary("artouchpad")
            ok = true
        } catch (e: UnsatisfiedLinkError) {
            // Shizuku service process may not have the standard library path set.
            // Derive the .so path from where our class was loaded.
            try {
                val src = UinputNative::class.java.protectionDomain?.codeSource?.location?.file
                if (src != null) {
                    // src is something like /data/app/.../base.apk — strip filename
                    val base = src.removeSuffix("/base.apk").removeSuffix("base.apk")
                    val lib = "$base/lib/arm64-v8a/libartouchpad.so"
                    System.load(lib)
                    ok = true
                    Log.i(TAG, "loaded via explicit path: $lib")
                }
            } catch (e2: Exception) {
                Log.e(TAG, "explicit load also failed: $e2")
            }
            if (!ok) Log.e(TAG, "loadLibrary failed: $e")
        }
        if (ok) Log.i(TAG, "native library loaded")
    }

    // Opens /dev/uinput; returns the fd (>= 0) on success or a negative value on failure.
    @JvmStatic external fun nOpen(): Int
    // Calls ioctl(fd, request, value); returns 0 on success or negative on error.
    @JvmStatic external fun nIoctl(request: Int, value: Int): Int
    // Writes the device name + USB identity struct to the uinput fd; returns bytes written.
    @JvmStatic external fun nWriteDevInfo(name: String): Int
    // Declares one absolute axis with its range and resolution in units per millimetre.
    // Only the modern creation path can carry resolution, and the gesture library scales
    // every threshold by it.
    @JvmStatic external fun nAbsSetup(code: Int, min: Int, max: Int, resolution: Int): Int
    // Creates the device through UI_DEV_SETUP, the path that pairs with nAbsSetup.
    @JvmStatic external fun nDevSetup(name: String): Int
    // Writes one input_event{type, code, value} to the uinput fd; returns bytes written.
    @JvmStatic external fun nWriteEvent(type: Int, code: Int, value: Int): Int
    // Sends UI_DEV_DESTROY and closes the uinput fd.
    @JvmStatic external fun nClose()
}
