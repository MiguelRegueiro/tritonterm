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
    if (kind == 5) return -ENOENT;
    if (kind == 6) return -ENOTDIR;
    if (kind == 7) return -ELOOP;
    if (kind == 8) retry = 1;
    memset(out, 0, 256);
    *(unsigned *)out = kind == 3 ? 0 : CNG_STATX_TYPE;
    *(unsigned short *)((char *)out + STATX_MODE_OFF) = kind == 4 ? 0120777 : 0100644;
    ((char *)out)[100] = 42;
    return 0;
}
int cng_fs_untranslate(const struct cng_fs *v, const char *name, char *out, size_t size) {
    assert(v == &view && size > strlen(name)); strcpy(out, name); return 0;
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
    zone = 0;
    for (kind = 0; kind < 9; kind++) {
        retry = 0; before = calls;
        int expected = kind == 0 ? 1 : kind == 5 ? -ENOENT : kind == 6 ? -ENOTDIR : 0;
        assert(statx_plain_resolved("/inside/file", st, flags, 0xfffu, 7) == expected);
        assert(calls == before + 1 && seen_flags == (flags | CNG_AT_SYMLINK_NOFOLLOW) && seen_mask == 0xfffu);
        if (!kind) assert(st[100] == 42);
    }
    kind = 0; retry = 1; before = calls;
    assert(!statx_plain_resolved("/inside/file", st, flags, 0xfffu, 7) && calls == before);
    retry = 0; zone = 1;
    assert(!statx_plain_resolved("/inside/file", st, flags, 0xfffu, 7) && calls == before);
    zone = 0;
    const char *resolved_special[] = {"/proc/self/status", "/dev/null", "/inside/file/", "/.proot.l2s.file", "/.l2s.file"};
    for (unsigned n = 0; n < sizeof resolved_special / sizeof *resolved_special; n++)
        assert(!statx_plain_resolved(resolved_special[n], st, flags, 0xfffu, 7) && calls == before);
    puts("PASS real statx candidate: ENOSYS/EACCES, omitted TYPE, symlink, view changes and special paths fall back");
    puts("PASS resolved statx candidate: errors reused only for missing/non-directory names, same flags/mask and view checks");
}
