/* Exercise the real report formatter at maximum counter width. These syscall,
 * formatter and image-state substitutes exist only in this standalone test. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdarg.h>
#include <stdio.h>
#include <unistd.h>
#include "../third_party/fake-chroot-ng/src/monitor/profile.c"

int cng_g_rewrite = 1;
long cng_syscall6(long a0, long a1, long a2, long a3, long a4, long a5, long nr) {
    long result = syscall(nr, a0, a1, a2, a3, a4, a5);
    return result == -1 ? -errno : result;
}
size_t cng_snprintf(char *buf, size_t cap, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); int n = vsnprintf(buf, cap, fmt, ap); va_end(ap);
    assert(n >= 0); return (size_t)n;
}
long cng_write_all(int fd, const void *buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t result = write(fd, (const char *)buf + done, n - done);
        if (result < 0) return -errno;
        done += (size_t)result;
    }
    return (long)done;
}
int cng_image_cache_enabled(void) { return 1; }
void cng_image_cache_diagnostic(int *blocked, long *stage, long *error) {
    *blocked = 0; *stage = 0; *error = 0;
}
int main(void) {
    mode = 2; owner_pid = getpid(); started = now(); entered = started;
    memcpy(program, "capacity", sizeof "capacity");
    for (unsigned i = 0; i < CNG_P_METRICS; i++) calls[i] = nanos[i] = ULONG_MAX;
    for (unsigned i = 0; i < PROFILE_NRS; i++) syscalls[i] = ULONG_MAX;
    report("exit", 0);
    return 0;
}
