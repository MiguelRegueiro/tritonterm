/* SPDX-License-Identifier: Apache-2.0 */
/* Explicit capability experiments, never normal loading. Raw gate syscalls
 * prevent execmap fallback from disguising a refused file mapping. Every
 * strategy gets a separate child, so a failure cannot mask the others. */
#include "cng/loader_probe.h"
#include "cng/loader.h"
#include "cng/rt.h"
#include "cng/syscall.h"
#include "cng/uapi.h"

static const u32 original[2] = {0x52800540u, 0xd65f03c0u}; /* mov w0,#42; ret */

static long original_intact(int fd, unsigned long offset, long *stage) {
    *stage = 8;
    u32 code[2];
    long n = sys_pread64(fd, code, sizeof code, (long)offset);
    if (n != sizeof code) return n < 0 ? n : -EIO;
    return memcmp(code, original, sizeof code) ? -EINVAL : 0;
}

static long execute_image(int fd, int strategy, long *stage) {
    int patch = strategy == CNG_LP_PRIVATE_PATCH;
    int direct = strategy == CNG_LP_DIRECT_RX;
    int protection = CNG_PROT_READ | (direct ? CNG_PROT_EXEC : 0) |
                     (patch ? CNG_PROT_WRITE : 0);
    *stage = 5;
    void *memory = sys_mmap(0, cng_page_size, protection, CNG_MAP_PRIVATE, fd, 0);
    if (cng_is_err((long)memory)) return (long)memory;
    if (patch) *(u32 *)memory = 0x52800560u; /* mov w0,#43 */
    long error = 0;
    if (!direct) {
        *stage = 6;
        error = sys_mprotect(memory, cng_page_size, CNG_PROT_READ | CNG_PROT_EXEC);
    }
    if (!error) {
        cng_flush_icache(memory, (char *)memory + 8);
        *stage = 7;
        int got = ((int (*)(void))memory)();
        if (got != (patch ? 43 : 42)) error = -EINVAL;
    }
    sys_munmap(memory, cng_page_size);
    if (!error) error = original_intact(fd, 0, stage);
    return error;
}

static long execute_hybrid(int fd, long *stage) {
    *stage = 5;
    unsigned long page = cng_page_size;
    char *memory = sys_mmap(0, 2 * page, CNG_PROT_READ | CNG_PROT_EXEC,
                            CNG_MAP_PRIVATE, fd, 0);
    if (cng_is_err((long)memory)) return (long)memory;
    cng_flush_icache(memory, memory + 24);
    cng_flush_icache(memory + page, memory + page + 8);
    /* The unchanged file-backed entry branches into the second page. */
    *stage = 19;
    long error = ((int (*)(void))memory)() == 42 ? 0 : -EINVAL;
    if (error) goto done;
    *stage = 17;
    void *patch = sys_mmap(memory + page, page, CNG_PROT_READ | CNG_PROT_WRITE,
        CNG_MAP_PRIVATE | CNG_MAP_ANONYMOUS | CNG_MAP_FIXED, -1, 0);
    if (cng_is_err((long)patch)) { error = (long)patch; goto done; }
    memcpy(patch, original, sizeof original);
    *(u32 *)patch = 0x52800560u; /* mov w0,#43 */
    *stage = 18;
    error = sys_mprotect(patch, page, CNG_PROT_READ | CNG_PROT_EXEC);
    if (error) goto done;
    cng_flush_icache(patch, (char *)patch + 8);
    *stage = 20;
    /* Shared code now enters the anonymous patched page. An independent
     * function in the unchanged shared page must still return 42. */
    if (((int (*)(void))memory)() != 43 ||
        ((int (*)(void))(memory + 16))() != 42) error = -EINVAL;
    if (!error) error = original_intact(fd, page, stage);
 done:
    sys_munmap(memory, 2 * page);
    return error;
}

static struct cng_loader_probe_result experiment(int strategy) {
    struct cng_loader_probe_result r = {1, 0, 0};
    long fd = sys_memfd_create("cng-loader-probe", 3 /* CLOEXEC|ALLOW_SEALING */);
    if (fd < 0) { r.error = fd; return r; }
    int hybrid = strategy == CNG_LP_HYBRID;
    r.stage = 2;
    r.error = sys_ftruncate((int)fd, cng_page_size * (hybrid ? 2 : 1));
    if (r.error) goto done;
    r.stage = 3;
    long count = sys_pwrite64((int)fd, original, sizeof original, 0);
    if (count != sizeof original) { r.error = count < 0 ? count : -EIO; goto done; }
    if (hybrid) {
        /* B +page at entry, plus an unchanged MOV/RET function at +16. */
        u32 branch = 0x14000000u | (u32)(cng_page_size / 4);
        count = sys_pwrite64((int)fd, &branch, sizeof branch, 0);
        if (count != sizeof branch) { r.error = count < 0 ? count : -EIO; goto done; }
        count = sys_pwrite64((int)fd, original, sizeof original, 16);
        if (count == sizeof original)
            count = sys_pwrite64((int)fd, original, sizeof original, cng_page_size);
        if (count != sizeof original) { r.error = count < 0 ? count : -EIO; goto done; }
    }
    r.stage = 4;
    r.error = sys_fcntl((int)fd, 1033 /* F_ADD_SEALS */, 15 /* SEAL|SHRINK|GROW|WRITE */);
    if (r.error) goto done;
    r.stage = 10;
    if (sys_pwrite64((int)fd, original, sizeof original, 0) != -EPERM) {
        r.error = -EINVAL; goto done;
    }
    r.error = hybrid ? execute_hybrid((int)fd, &r.stage)
                     : execute_image((int)fd, strategy, &r.stage);
    if (!r.error) r.stage = 9;
 done:
    sys_close((int)fd);
    return r;
}

struct cng_loader_probe_result cng_loader_probe(int strategy) {
    struct cng_loader_probe_result r = {11, 0, 0};
    int pipe[2];
    r.error = CNG_SYS(__NR_pipe2, pipe, CNG_O_CLOEXEC, 0, 0, 0, 0);
    if (r.error) return r;
    r.stage = 12;
    long child = sys_fork_quiet();
    /* qemu-user only accepts clone(SIGCHLD), as in broker_fork. Native
     * kernels support the quiet child; the emulator fallback is visible. */
    if (child == -EINVAL) child = sys_fork();
    if (child == 0) {
        sys_close(pipe[0]);
        unsigned long zero[2] = {0, 0};
        CNG_SYS(__NR_prlimit64, 0, 4 /* RLIMIT_CORE */, zero, 0, 0, 0);
        r = experiment(strategy);
        cng_write_all(pipe[1], &r, sizeof r);
        CNG_SYS(__NR_exit_group, 0, 0, 0, 0, 0, 0);
        __builtin_unreachable();
    }
    sys_close(pipe[1]);
    if (child < 0) { r.error = child; sys_close(pipe[0]); return r; }
    /* No guest SIGCHLD and no guest wait() can consume this quiet child.
     * Bound diagnostics to about two seconds; do not block shell startup. */
    long waited = 0;
    int status = 0;
    for (int attempt = 0; attempt < 200; attempt++) {
        waited = sys_wait4((int)child, &status, CNG_WALL | 1 /* WNOHANG */, 0);
        if (waited == child || (waited < 0 && waited != -EINTR)) break;
        struct cng_timespec pause = {0, 10000000};
        CNG_SYS(__NR_nanosleep, &pause, 0, 0, 0, 0, 0);
    }
    if (waited != child) {
        r.stage = waited < 0 ? 13 : 14;
        r.error = waited < 0 ? waited : -ETIMEDOUT;
        if (waited >= 0 || waited == -EINTR) {
            CNG_SYS(__NR_kill, child, 9 /* SIGKILL */, 0, 0, 0, 0);
            while (sys_wait4((int)child, &status, CNG_WALL, 0) == -EINTR) {}
        }
    } else if (status & 127) {
        r.stage = 15; r.error = 0; r.signal = status & 127;
    } else {
        struct cng_loader_probe_result payload;
        long n = sys_read(pipe[0], &payload, sizeof payload);
        if (n == sizeof payload && !(status >> 8)) r = payload;
        else { r.stage = 16; r.error = n < 0 ? n : -EIO; }
    }
    sys_close(pipe[0]);
    return r;
}
