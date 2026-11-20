/* SPDX-License-Identifier: MPL-2.0 */
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include "libusb.h"

static const unsigned char descriptors[] = {
    18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 0, 0, 0, 1,
    9, 2, 25, 0, 1, 1, 0, 0x80, 50,
    9, 4, 0, 0, 1, 0xff, 0, 0, 0,
    7, 5, 0x01, 0x01, 32, 0, 1,
};

static int fixture_fd = -1;
static struct usbdevfs_urb *pending;
static bool ready;
static bool disconnected;
static bool busy;
static bool claimed;

static int fail(int error)
{
    errno = error;
    return -1;
}

int __wrap_ioctl(int fd, unsigned long request, ...)
{
    if (fd != fixture_fd || fd < 0)
        return fail(ENOTTY);

    va_list args;
    va_start(args, request);
    void *arg = va_arg(args, void *);
    va_end(args);

    switch (request) {
    case USBDEVFS_CONNECTINFO:
        ((struct usbdevfs_connectinfo *)arg)->devnum = 1;
        return 0;
    case USBDEVFS_GET_SPEED:
        return 2;
    case USBDEVFS_CONTROL: {
        struct usbdevfs_ctrltransfer *control = arg;
        assert(control->bRequest == LIBUSB_REQUEST_GET_CONFIGURATION);
        assert(control->wLength == 1);
        *(unsigned char *)control->data = 1;
        return 1;
    }
    case USBDEVFS_GET_CAPABILITIES:
        *(unsigned int *)arg = 0;
        return 0;
    case USBDEVFS_CLAIMINTERFACE:
        assert(*(unsigned int *)arg == 0 && !claimed);
        if (busy)
            return fail(EBUSY);
        claimed = true;
        return 0;
    case USBDEVFS_RELEASEINTERFACE:
        assert(*(unsigned int *)arg == 0 && claimed);
        claimed = false;
        return disconnected ? fail(ENODEV) : 0;
    case USBDEVFS_SUBMITURB: {
        struct usbdevfs_urb *urb = arg;
        assert(claimed && !pending && !ready);
        assert(urb->type == USBDEVFS_URB_TYPE_ISO && urb->endpoint == 1);
        assert(urb->number_of_packets == 2 && urb->buffer_length == 16);
        pending = urb;
        return 0;
    }
    case USBDEVFS_DISCARDURB:
        if (disconnected)
            return fail(ENODEV);
        assert(pending && arg == pending);
        if (ready)
            return fail(EINVAL);
        pending->status = -ENOENT;
        ready = true;
        return 0;
    case USBDEVFS_REAPURBNDELAY:
        if (!ready)
            return fail(EAGAIN);
        *(struct usbdevfs_urb **)arg = pending;
        pending = NULL;
        ready = false;
        return 0;
    default:
        assert(!"unexpected fixture ioctl");
        return fail(ENOTTY);
    }
}

int __real_poll(struct pollfd *fds, nfds_t count, int timeout);

int __wrap_poll(struct pollfd *fds, nfds_t count, int timeout)
{
    int result = __real_poll(fds, count, timeout);
    if (result > 0 && disconnected) {
        for (nfds_t index = 0; index < count; index++) {
            if (fds[index].fd == fixture_fd && fds[index].revents != 0)
                fds[index].revents = POLLERR;
        }
    }
    return result;
}

struct completion {
    int calls;
    enum libusb_transfer_status status;
    unsigned int lengths[2];
};

static void record_callback(struct libusb_transfer *transfer)
{
    struct completion *completion = transfer->user_data;
    completion->calls++;
    completion->status = transfer->status;
    for (int index = 0; index < 2; index++)
        completion->lengths[index] = transfer->iso_packet_desc[index].actual_length;
}

static void drain(libusb_context *context, struct completion *completion,
                  enum libusb_transfer_status expected)
{
    struct timeval timeout = {0};
    assert(completion->calls == 0);
    for (int attempt = 0; attempt < 5 && completion->calls == 0; attempt++)
        assert(libusb_handle_events_timeout(context, &timeout) == 0);
    assert(completion->calls == 1 && completion->status == expected);
    assert(libusb_handle_events_timeout(context, &timeout) == 0);
    assert(completion->calls == 1);
}

static struct libusb_transfer *make_transfer(libusb_device_handle *handle,
                                              unsigned char buffer[static 16],
                                              struct completion *completion)
{
    struct libusb_transfer *transfer = libusb_alloc_transfer(2);
    assert(transfer);
    libusb_fill_iso_transfer(transfer, handle, 1, buffer, 16, 2, record_callback, completion, 0);
    libusb_set_iso_packet_lengths(transfer, 8);
    return transfer;
}

int main(void)
{
    const struct libusb_version *version = libusb_get_version();
    assert(version->major == 1 && version->minor == 0 && version->micro == 29);
    assert(geteuid() != 0);
    const struct libusb_init_option option = {.option = LIBUSB_OPTION_NO_DEVICE_DISCOVERY};
    libusb_context *context = NULL;
    assert(libusb_init_context(&context, &option, 1) == 0);

    FILE *file = tmpfile();
    assert(file);
    int owner_fd = fileno(file);
    assert(fwrite(descriptors, 1, sizeof(descriptors), file) == sizeof(descriptors));
    assert(fflush(file) == 0);
    int duplicate = fcntl(owner_fd, F_DUPFD_CLOEXEC, 0);
    assert(duplicate >= 0 && duplicate != owner_fd);
    assert(fcntl(duplicate, F_GETFD) == FD_CLOEXEC);

    libusb_device_handle *handle = NULL;
    assert(libusb_wrap_sys_device(context, duplicate, &handle) == LIBUSB_ERROR_IO);
    assert(handle == NULL && fcntl(duplicate, F_GETFD) == FD_CLOEXEC);
    assert(close(duplicate) == 0 && fcntl(owner_fd, F_GETFD) >= 0);
    puts("PASS wrap failure retains caller FD");

    fixture_fd = fcntl(owner_fd, F_DUPFD_CLOEXEC, 0);
    assert(fixture_fd >= 0);
    assert(libusb_wrap_sys_device(context, fixture_fd, &handle) == 0);
    struct libusb_device_descriptor descriptor;
    assert(libusb_get_device_descriptor(libusb_get_device(handle), &descriptor) == 0);
    assert(descriptor.idVendor == 0x1234 && descriptor.idProduct == 0x5678);
    busy = true;
    assert(libusb_claim_interface(handle, 0) == LIBUSB_ERROR_BUSY);
    busy = false;
    assert(libusb_claim_interface(handle, 0) == 0);
    puts("PASS synthetic descriptor and EBUSY mapping");

    unsigned char buffer[16] = {0};
    struct completion completion = {0};
    struct libusb_transfer *transfer = make_transfer(handle, buffer, &completion);

    assert(libusb_submit_transfer(transfer) == 0);
    assert(libusb_cancel_transfer(transfer) == 0);
    drain(context, &completion, LIBUSB_TRANSFER_CANCELLED);
    puts("PASS cancel returns before one CANCELLED callback");

    completion = (struct completion){0};
    assert(libusb_submit_transfer(transfer) == 0);
    pending->iso_frame_desc[0].actual_length = 8;
    pending->iso_frame_desc[1].actual_length = 5;
    ready = true;
    assert(libusb_cancel_transfer(transfer) == LIBUSB_ERROR_NOT_FOUND);
    drain(context, &completion, LIBUSB_TRANSFER_COMPLETED);
    assert(completion.lengths[0] == 8 && completion.lengths[1] == 5);
    puts("PASS cancel race still reaps COMPLETED with lengths 8,5");

    libusb_free_transfer(transfer);
    assert(libusb_release_interface(handle, 0) == 0);
    libusb_close(handle);
    assert(fcntl(fixture_fd, F_GETFD) == FD_CLOEXEC);
    assert(close(fixture_fd) == 0 && fcntl(owner_fd, F_GETFD) >= 0);
    puts("PASS successful wrap borrows FD through libusb_close");

    fixture_fd = fcntl(owner_fd, F_DUPFD_CLOEXEC, 0);
    assert(fixture_fd >= 0);
    assert(libusb_wrap_sys_device(context, fixture_fd, &handle) == 0);
    assert(libusb_claim_interface(handle, 0) == 0);
    completion = (struct completion){0};
    transfer = make_transfer(handle, buffer, &completion);
    assert(libusb_submit_transfer(transfer) == 0);
    disconnected = true;
    assert(libusb_cancel_transfer(transfer) == LIBUSB_ERROR_NO_DEVICE);
    /* The fixture does not advertise REAP_AFTER_DISCONNECT. */
    pending = NULL;
    drain(context, &completion, LIBUSB_TRANSFER_NO_DEVICE);
    libusb_free_transfer(transfer);
    assert(libusb_release_interface(handle, 0) == LIBUSB_ERROR_NO_DEVICE);
    libusb_close(handle);
    assert(fcntl(fixture_fd, F_GETFD) == FD_CLOEXEC);
    assert(close(fixture_fd) == 0 && fcntl(owner_fd, F_GETFD) >= 0);
    libusb_exit(context);
    assert(fclose(file) == 0);
    puts("PASS simulated POLLERR drains one NO_DEVICE callback before teardown");
}
