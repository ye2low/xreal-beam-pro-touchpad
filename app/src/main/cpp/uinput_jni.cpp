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

#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <linux/uinput.h>
#include <linux/input.h>
#include <string.h>
#include <errno.h>
#include <sys/time.h>
#include <android/log.h>

#define TAG  "UinputJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// Persistent file descriptor for /dev/uinput, shared across all JNI calls.
// A single global is safe because MouseService is a singleton Shizuku UserService —
// only one instance exists per process lifetime.
static int g_fd = -1;

extern "C" {

// Opens /dev/uinput for writing in non-blocking mode and stores the fd in g_fd.
// Shell uid (granted by Shizuku) is in the `input` group which has rw access to
// /dev/uinput, so no additional permissions are needed.
// Returns: the fd on success (>= 0), or a negative errno on failure.
// Must be called before any other nXxx functions.
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nOpen(JNIEnv*, jclass) {
    g_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (g_fd < 0) LOGE("open /dev/uinput failed errno=%d", errno);
    else          LOGI("opened /dev/uinput fd=%d", g_fd);
    return g_fd;
}

// Calls ioctl(g_fd, request, value) to configure the uinput device before creation.
// Used by MouseService.initUinput() for UI_SET_EVBIT / UI_SET_KEYBIT / UI_SET_RELBIT /
// UI_DEV_CREATE. This JNI bridge exists because Android 16 removed the generic
// Os.ioctl(fd, req, value) variant — only ioctlInt(fd, req) (no value arg) remains.
// Returns: the ioctl return value (0 on success, negative on error).
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nIoctl(JNIEnv*, jclass, jint request, jint value) {
    int ret = ioctl(g_fd, (unsigned long)request, (int)value);
    if (ret < 0) LOGE("ioctl(0x%x, %d) failed errno=%d", request, value, errno);
    return ret;
}

// Writes a uinput_user_dev struct to g_fd, setting the device name and a synthetic
// USB bus/vendor/product identity. Must be called after all capability ioctls
// (UI_SET_EVBIT etc.) and before the UI_DEV_CREATE ioctl.
// name: device name visible in /proc/bus/input/devices and to Android InputReader.
// Returns: bytes written on success (sizeof(uinput_user_dev)), negative on failure.
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nWriteDevInfo(JNIEnv* env, jclass, jstring jname) {
    struct uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    const char* name = env->GetStringUTFChars(jname, nullptr);
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    env->ReleaseStringUTFChars(jname, name);
    dev.id.bustype = BUS_USB;
    dev.id.vendor  = 0x1234;
    dev.id.product = 0x5678;
    dev.id.version = 1;
    ssize_t n = write(g_fd, &dev, sizeof(dev));
    if (n < 0) LOGE("write dev info failed errno=%d", errno);
    return (jint)n;
}

// Declares the range and physical resolution of one absolute axis, for a touchpad-class
// device. The legacy uinput_user_dev path above cannot express resolution at all — its
// struct has no field for it — and resolution is what the gesture library scales every
// threshold by. Without it Android assumes 32 units per millimetre and every distance the
// gestures are judged against is wrong.
// resolution: units per millimetre.
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nAbsSetup(
        JNIEnv*, jclass, jint code, jint minimum, jint maximum, jint resolution) {
    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));
    abs.code = (uint16_t)code;
    abs.absinfo.minimum    = minimum;
    abs.absinfo.maximum    = maximum;
    abs.absinfo.resolution = resolution;
    int ret = ioctl(g_fd, UI_ABS_SETUP, &abs);
    if (ret < 0) LOGE("UI_ABS_SETUP(code=%d) failed errno=%d", code, errno);
    return ret;
}

// Creates the device through the modern UI_DEV_SETUP path, which is the one that must be
// paired with UI_ABS_SETUP — the two cannot be mixed with the legacy write().
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nDevSetup(JNIEnv* env, jclass, jstring jname) {
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    const char* name = env->GetStringUTFChars(jname, nullptr);
    strncpy(setup.name, name, UINPUT_MAX_NAME_SIZE - 1);
    env->ReleaseStringUTFChars(jname, name);
    // BUS_VIRTUAL is honest about what this is, and Android's classification does not
    // depend on the bus type.
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor  = 0x1234;
    setup.id.product = 0x5679;
    setup.id.version = 1;
    int ret = ioctl(g_fd, UI_DEV_SETUP, &setup);
    if (ret < 0) LOGE("UI_DEV_SETUP failed errno=%d", errno);
    return ret;
}

// Hot-path function: writes a single struct input_event{type, code, value} to g_fd.
// Timestamps each event with gettimeofday so the kernel input layer sees valid timing.
// Called once per EV_REL/EV_KEY event and once per EV_SYN/SYN_REPORT flush.
// type/code/value: Linux input subsystem constants (EV_REL, REL_X, pixel delta, etc.).
// Returns: bytes written on success (sizeof(input_event)), negative on failure.
JNIEXPORT jint JNICALL
Java_com_pgratz_artouchpad_UinputNative_nWriteEvent(JNIEnv*, jclass, jint type, jint code, jint value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    ev.time    = tv;
    ev.type    = (uint16_t)type;
    ev.code    = (uint16_t)code;
    ev.value   = (int32_t)value;
    ssize_t n  = write(g_fd, &ev, sizeof(ev));
    if (n < 0) LOGE("writeEvent(%d,%d,%d) failed errno=%d", type, code, value, errno);
    return (jint)n;
}

// Sends UI_DEV_DESTROY to unregister the virtual device from the Linux input subsystem,
// then closes g_fd. Safe to call when g_fd is already -1 (no-op guard).
JNIEXPORT void JNICALL
Java_com_pgratz_artouchpad_UinputNative_nClose(JNIEnv*, jclass) {
    if (g_fd >= 0) {
        ioctl(g_fd, UI_DEV_DESTROY);
        close(g_fd);
        g_fd = -1;
        LOGI("uinput device destroyed");
    }
}

} // extern "C"
