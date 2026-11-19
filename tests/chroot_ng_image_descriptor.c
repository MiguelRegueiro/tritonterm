/* Reproduce an Android-style denial of /proc/self/fd reopen without denying
 * use of the original memfd. The syscall gate is linked only into this test. */
#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>
#include "cng/image_cache.h"
#include "cng/profile.h"
#include "cng/syscall.h"
#include "cng/uapi.h"

unsigned long cng_page_size;
int cng_g_debug;
static int denied;
long cng_syscall6(long a, long b, long c, long d, long e, long f, long nr) {
    if (nr == __NR_openat && !strncmp((const char *)b, "/proc/self/fd/", 14)) {
        denied++; return -EACCES;
    }
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    register long x8 __asm__("x8") = nr;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5), "r"(x8) : "memory", "cc");
    return x0;
}
struct cng_profile_span cng_profile_begin(unsigned m) { (void)m; return (struct cng_profile_span){0,0}; }
void cng_profile_end(unsigned m, struct cng_profile_span s) { (void)m; (void)s; }
void cng_profile_add(unsigned m, unsigned long n) { (void)m; (void)n; }
int cng_hits_monitor(unsigned long a, unsigned long n) { (void)a; (void)n; return 0; }
size_t cng_snprintf(char *buf, size_t cap, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); int n = vsnprintf(buf, cap, fmt, ap); va_end(ap);
    return n < 0 ? 0 : (size_t)n;
}
void cng_dprintf(int fd, const char *fmt, ...) { (void)fd; (void)fmt; assert(0); }

int main(int argc, char **argv) {
    assert(argc == 2);
    cng_page_size = (unsigned long)sysconf(_SC_PAGESIZE);
    unsigned char data[512] = {0};
    Elf64_Ehdr *eh = (void *)data;
    memcpy(eh->e_ident, "\177ELF\002\001\001", 7);
    eh->e_type = ET_DYN; eh->e_machine = EM_AARCH64; eh->e_version = 1;
    eh->e_ehsize = sizeof *eh; eh->e_phoff = sizeof *eh;
    eh->e_phentsize = sizeof(Elf64_Phdr); eh->e_phnum = 1; eh->e_entry = 256;
    Elf64_Phdr *ph = (void *)(data + sizeof *eh);
    ph->p_type = PT_LOAD; ph->p_flags = PF_R | PF_X;
    ph->p_filesz = sizeof data; ph->p_memsz = cng_page_size; ph->p_align = cng_page_size;
    unsigned code[2] = {0x52800540u, 0xd65f03c0u};
    memcpy(data + 256, code, sizeof code);
    int source = open(argv[1], O_CREAT | O_TRUNC | O_RDWR | O_CLOEXEC, 0700);
    assert(source >= 0 && write(source, data, sizeof data) == sizeof data);
    close(source); source = open(argv[1], O_RDONLY | O_CLOEXEC); assert(source >= 0);
    char path[64]; snprintf(path, sizeof path, "/proc/self/fd/%d", source);
    assert(CNG_SYS(__NR_openat, AT_FDCWD, path, O_RDONLY | O_CLOEXEC, 0, 0, 0) == -EACCES);
    int before = denied;
    struct cng_bresp response = {0};
    int image = cng_image_cache_serve(source, &response);
    assert(image >= 0 && response.ret == 1 && denied == before);
    assert(fcntl(image, F_GET_SEALS) == 15);
    assert(pwrite(image, "x", 1, 0) == -1 && errno == EPERM);
    assert(ftruncate(image, 0) == -1 && errno == EPERM);
    assert(mmap(NULL, cng_page_size, PROT_READ | PROT_WRITE, MAP_SHARED, image, 0) == MAP_FAILED && errno == EPERM);
    void *rx = mmap(NULL, cng_page_size, PROT_READ | PROT_EXEC, MAP_PRIVATE, image, 0);
    assert(rx != MAP_FAILED && ((int (*)(void))((char *)rx + 256))() == 42);
    assert(munmap(rx, cng_page_size) == 0);
    int another = open(argv[1], O_RDONLY | O_CLOEXEC); assert(another >= 0);
    assert(cng_image_cache_serve(another, &response) == image && response.ret == 0);
    close(another); unlink(argv[1]);
    puts("PASS denied descriptor reopen: sealed cache miss/hit, immutable bytes and executable RX mapping");
}
