/* Exercise the actual candidate helper for kernel refusals and omitted TYPE.
 * Test-only pin/view substitutes are discarded with this standalone fixture. */
#define _GNU_SOURCE
#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../third_party/fake-chroot-ng/src/monitor/dispatch.c"

static struct cng_fs view;
struct cng_fs *cng_g_fs = &view;
static int calls, kind, retry, zone;
static unsigned seen_flags, seen_mask;
unsigned cng_fs_read_begin(const struct cng_fs **out) { *out = &view; return 7; }
int cng_fs_read_retry(unsigned seq) { assert(seq == 7); return retry; }
int cng_fs_translate_mnt(const struct cng_fs *v, const char *name, char *out,
                         size_t size, int *mount) {
    assert(v == &view && size > strlen(name)); strcpy(out, name);
    *mount = zone ? CNG_MOUNT_ROOTFS + 1 : CNG_MOUNT_ROOTFS; return 0;
}
long cng_pin_statx(const char *name, int flags, unsigned mask, void *out) {
    assert(!strcmp(name, "/inside/file")); calls++; seen_flags = (unsigned)flags; seen_mask = mask;
    if (kind == 1) return -ENOSYS;
    if (kind == 2) return -EACCES;
    memset(out, 0, 256);
    *(unsigned *)out = kind == 3 ? 0 : CNG_STATX_TYPE;
    *(unsigned short *)((char *)out + STATX_MODE_OFF) = kind == 4 ? 0120777 : 0100644;
    ((char *)out)[100] = 42;
    return 0;
}
long cng_pin_fstatat(const char *name, void *out, int flags) {
    (void)name; (void)flags; calls++; memset(out, 0, 128);
    *(unsigned *)((char *)out + STAT_MODE_OFF) = 0100644; return 0;
}
int main(void) {
    char path[4096], st[256]; unsigned flags = 0x4000u | CNG_AT_NO_AUTOMOUNT;
    for (kind = 0; kind < 5; kind++) {
        int before = calls;
        assert(stat_plain_absolute("/inside/file", path, sizeof path, st, 1, flags, 0xfffu) == (kind == 0));
        assert(calls == before + 1 && seen_flags == (flags | CNG_AT_SYMLINK_NOFOLLOW) && seen_mask == 0xfffu);
        if (!kind) assert(st[100] == 42);
    }
    kind = 0; retry = 1;
    assert(!stat_plain_absolute("/inside/file", path, sizeof path, st, 1, flags, 0xfffu)); retry = 0;
    const char *special[] = {"/proc/self/status", "/dev/null", "/inside/./file", "/inside/file/", "inside/file", "/.proot.l2s.file"};
    for (unsigned n = 0; n < sizeof special / sizeof *special; n++) {
        int before = calls;
        assert(!stat_plain_absolute(special[n], path, sizeof path, st, 1, flags, 0xfffu));
        assert(calls == before);
    }
    zone = 1; int before = calls;
    assert(!stat_plain_absolute("/inside/file", path, sizeof path, st, 1, flags, 0xfffu) && calls == before);
    puts("PASS real statx candidate: ENOSYS/EACCES, omitted TYPE, symlink, view changes and special paths fall back");
}
