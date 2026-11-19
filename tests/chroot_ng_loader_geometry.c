/* Link the actual probe against a minimal raw gate and libc for geometry
 * checks. A 16 KB logical geometry on a 4 KB host is not a 16 KB kernel test. */
#include "cng/loader_probe.h"
#include <assert.h>
#include <errno.h>
#include <stddef.h>
#include <stdio.h>
#include <unistd.h>
unsigned long cng_page_size;
__asm__(".text\n.global cng_syscall6\n.type cng_syscall6,%function\n"
        "cng_syscall6:\nmov x8,x6\nsvc #0\nret\n"
        ".size cng_syscall6,.-cng_syscall6\n");
long cng_write_all(int fd, const void *buffer, size_t size) {
    size_t done = 0;
    while (done < size) {
        ssize_t n = write(fd, (const char *)buffer + done, size - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return n < 0 ? -errno : -EIO;
        done += n;
    }
    return (long)done;
}
int main(void) {
    for (cng_page_size = 4096; cng_page_size <= 16384; cng_page_size *= 4) {
        for (int strategy = 0; strategy < CNG_LP_STRATEGIES; strategy++) {
            struct cng_loader_probe_result r = cng_loader_probe(strategy);
            if (r.stage != 9 || r.error || r.signal)
                fprintf(stderr, "geometry=%lu strategy=%d stage=%ld error=%ld signal=%ld\n",
                        cng_page_size, strategy, r.stage, r.error, r.signal);
            assert(r.stage == 9 && !r.error && !r.signal);
        }
    }
    puts("PASS 4 KB/16 KB logical geometry: real probe strategies and sealed-source integrity");
}
