/* SPDX-License-Identifier: MPL-2.0 */
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <unistd.h>

#include "libusb.h"

JNIEXPORT jint JNICALL Java_com_audipp_research_usbfd_UsbFdProbe_checkFailedWrap(
        JNIEnv *env, jclass type, jint owner_fd)
{
    (void)env;
    (void)type;
    const struct libusb_version *version = libusb_get_version();
    if (version->major != 1 || version->minor != 0 || version->micro != 29)
        return 1;

    const struct libusb_init_option option = {.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY};
    libusb_context *context = NULL;
    if (libusb_init_context(&context, &option, 1) != 0)
        return 2;

    jint result = 0;
    int duplicate = fcntl(owner_fd, F_DUPFD_CLOEXEC, 0);
    if (duplicate < 0) {
        result = 3;
    } else {
        libusb_device_handle *handle = NULL;
        int wrap = libusb_wrap_sys_device(context, (intptr_t)duplicate, &handle);
        if (wrap == 0)
            libusb_close(handle);
        if (wrap != LIBUSB_ERROR_IO || handle != NULL)
            result = 4;
        if (fcntl(duplicate, F_GETFD) != FD_CLOEXEC)
            result = 5;
        if (close(duplicate) != 0 || fcntl(duplicate, F_GETFD) != -1 || errno != EBADF)
            result = 6;
    }
    libusb_exit(context);
    if (fcntl(owner_fd, F_GETFD) < 0)
        result = 7;
    return result;
}
