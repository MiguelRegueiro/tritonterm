/* SPDX-License-Identifier: Apache-2.0 */
/* An explicitly requested capability experiment, never part of normal loading.
 * Native gate syscalls ensure a successful mmap is not our anonymous fallback.
 * The private child isolates ambient seccomp/SELinux faults from the guest. */
#include "cng/loader_probe.h"
#include "cng/loader.h"
#include "cng/rt.h"
#include "cng/syscall.h"
#include "cng/uapi.h"

static long execute_image(int fd, int patch, long *stage) {
    *stage = 5;
    void *memory = sys_mmap(0, cng_page_size, CNG_PROT_READ | CNG_PROT_WRITE,
                            CNG_MAP_PRIVATE, fd, 0);
    if (cng_is_err((long)memory)) return (long)memory;
    /* No persistent writable alias: patch this MAP_PRIVATE copy only. */
    if (patch) *(u32 *)memory = 0x52800560u; /* mov w0,#43 */
    *stage = 6;
    long error = sys_mprotect(memory, cng_page_size, CNG_PROT_READ | CNG_PROT_EXEC);
    if (!error) {
        cng_flush_icache(memory, (char *)memory + 8);
        *stage = patch ? 7 : 8;
        int got = ((int (*)(void))memory)();
        if (got != (patch ? 43 : 42)) error = -EINVAL;
    }
    sys_munmap(memory, cng_page_size);
    return error;
}

static struct cng_loader_probe_result experiment(void) {
    struct cng_loader_probe_result r = {1, 0, 0};
    long fd = sys_memfd_create("cng-loader-probe", 3 /* CLOEXEC|ALLOW_SEALING */);
    if (fd < 0) { r.error = fd; return r; }
    r.stage = 2;
    r.error = sys_ftruncate((int)fd, cng_page_size);
    if (r.error) goto done;
    r.stage = 3;
    u32 code[2] = {0x52800540u, 0xd65f03c0u}; /* mov w0,#42; ret */
    long count = sys_pwrite64((int)fd, code, sizeof code, 0);
    if (count != sizeof code) { r.error = count < 0 ? count : -EIO; goto done; }
    r.stage = 4;
    r.error = sys_fcntl((int)fd, 1033 /* F_ADD_SEALS */, 15 /* SEAL|SHRINK|GROW|WRITE */);
    if (r.error) goto done;
    r.stage = 10;
    if (sys_pwrite64((int)fd, code, sizeof code, 0) != -EPERM) {
        r.error = -EINVAL; goto done;
    }
    r.error = execute_image((int)fd, 1, &r.stage);
    if (r.error) goto done;
    r.error = execute_image((int)fd, 0, &r.stage);
    if (!r.error) r.stage = 9;
 done:
    sys_close((int)fd);
    return r;
}

struct cng_loader_probe_result cng_loader_probe(void) {
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
        r = experiment();
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
