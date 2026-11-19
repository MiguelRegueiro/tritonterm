/* Exercise the real loader with a deterministic raw-gate mmap refusal.
 * QEMU transforms host mmap permissions, so a host PROT_EXEC filter cannot
 * reliably inject this guest error. No fault hooks enter the shipped engine. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>
#include "../third_party/fake-chroot-ng/src/loader/elf.c"

unsigned long cng_page_size;
int cng_g_rewrite = 1, cng_g_image_cache = 1, cng_g_debug;
static int cache_fd, supplied = -1, refuse = 1, denials;
static long failure_stage, failure_error;
void cng_image_cache_failure(long stage, long error) { failure_stage = stage; failure_error = error; }
static void *reservation;
static unsigned long reservation_size;

long cng_syscall6(long a, long b, long c, long d, long e, long f, long nr) {
    if (refuse && nr == __NR_mmap && e >= 0 && (c & CNG_PROT_EXEC) && (d & CNG_MAP_FIXED)) {
        denials++; return -EACCES;
    }
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    register long x8 __asm__("x8") = nr;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    if (refuse && nr == __NR_mmap && e == -1 && x0 >= 0) {
        reservation = (void *)x0; reservation_size = (unsigned long)b;
    }
    return x0;
}
int cng_image_cache_enabled(void) { return refuse; }
int cng_image_cache_get(int source, const Elf64_Ehdr *eh, const Elf64_Phdr *ph,
                        unsigned long span, unsigned long lo, struct cng_image_meta *m) {
    (void)source; (void)eh; (void)ph; (void)span; (void)lo;
    memset(m, 0, sizeof *m);
    supplied = fcntl(cache_fd, F_DUPFD_CLOEXEC, 3); assert(supplied >= 0);
    return supplied;
}
struct cng_profile_span cng_profile_begin(unsigned m) { (void)m; return (struct cng_profile_span){0,0}; }
void cng_profile_end(unsigned m, struct cng_profile_span s) { (void)m; (void)s; }
void cng_profile_add(unsigned m, unsigned long n) { (void)m; (void)n; }
int cng_code_ranges(int fd, struct cng_code_ranges *cr) { (void)fd; cr->n = 0; return 0; }
int cng_rewrite_seg(unsigned long a, unsigned long b, unsigned long c,
                    const struct cng_code_ranges *d, unsigned long e, unsigned long f, unsigned long *g) {
    (void)a;(void)b;(void)c;(void)d;(void)e;(void)f;(void)g; assert(0); return 0;
}
int cng_rewrite_index(unsigned long a, unsigned long b, unsigned long c,
    const struct cng_code_ranges *d, unsigned long e, const unsigned long *f,
    unsigned long g, unsigned long h, unsigned long i, unsigned long *j) {
    (void)a;(void)b;(void)c;(void)d;(void)e;(void)f;(void)g;(void)h;(void)i;(void)j; assert(0); return 0;
}
/* Error formatting is not reached by successful anonymous fallback. */
void cng_dprintf(int fd, const char *format, ...) { (void)fd; (void)format; assert(0); }

int main(void) {
    cng_page_size = (unsigned long)sysconf(_SC_PAGESIZE);
    cache_fd = memfd_create("fallback-fixture", MFD_CLOEXEC); assert(cache_fd >= 0);
    u32 code[2] = {0x52800540u, 0xd65f03c0u};
    assert(ftruncate(cache_fd, cng_page_size) == 0 && pwrite(cache_fd, code, sizeof code, 0) == sizeof code);
    Elf64_Ehdr header = {0}; header.e_phnum = 1;
    Elf64_Phdr ph = {0}; ph.p_type = PT_LOAD; ph.p_flags = PF_R | PF_X;
    ph.p_filesz = sizeof code; ph.p_memsz = cng_page_size;
    unsigned char copied[MAX_PHDR] = {1}; unsigned long bias, length;
    assert(map_hybrid(cache_fd, &header, &ph, 1, 0, cng_page_size, 0, &bias, &length, copied) == -1);
    assert(failure_stage == 31 && failure_error == -EACCES);
    assert(denials == 1 && reservation && reservation_size == cng_page_size + CNG_TRAMP_POOL);
    assert(fcntl(supplied, F_GETFD) == -1 && errno == EBADF);
    unsigned char vec;
    assert(mincore(reservation, cng_page_size, &vec) == -1 && errno == ENOMEM);
    for (unsigned i = 0; i < sizeof copied; i++) assert(copied[i] == 0);
    refuse = 0; cng_g_rewrite = 0;
    assert(map_anon(cache_fd, &header, &ph, 1, 0, cng_page_size, 0, &bias, &length, copied) == CNG_LOAD_OK);
    assert(((int (*)(void))bias)() == 42);
    assert(munmap((void *)bias, length) == 0); close(cache_fd);
    puts("PASS file mmap EACCES: real hybrid cleanup and executable anonymous fallback");
}
