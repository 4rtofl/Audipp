// SPDX-License-Identifier: MPL-2.0
package com.audipp.research.usbfd;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import java.io.File;

public final class UsbFdProbe extends Instrumentation {
    private static native int checkFailedWrap(int borrowedFd);

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            System.loadLibrary("audipp_usb_fd_probe");
            try (ParcelFileDescriptor owner = ParcelFileDescriptor.open(
                    new File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY)) {
                int check = checkFailedWrap(owner.getFd());
                if (check != 0 || Process.myUid() < Process.FIRST_APPLICATION_UID) {
                    throw new AssertionError("JNI result=" + check + ", uid=" + Process.myUid());
                }
            }
            status = Activity.RESULT_OK;
            result.putString("stream", "PASS API " + Build.VERSION.SDK_INT + " "
                    + Build.SUPPORTED_ABIS[0] + " uid=" + Process.myUid()
                    + " libusb 1.0.29 JNI wrap failure and FD cleanup\n");
        } catch (Exception | LinkageError | AssertionError failure) {
            result.putString("stream", "FAIL " + failure + "\n");
        }
        finish(status, result);
    }
}
