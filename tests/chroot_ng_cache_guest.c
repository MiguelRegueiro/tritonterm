#define _GNU_SOURCE
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/mman.h>
#include <unistd.h>
extern void cng_flush_icache(void *, void *);
extern uint64_t cng_flush_icache_test(void *, void *, uint64_t);

int main(void) {
    size_t page = sysconf(_SC_PAGESIZE);
    char *p = mmap(NULL, page * 2, PROT_READ | PROT_WRITE,
                   MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    assert(p != MAP_FAILED);
    for (unsigned bits = 0; bits < 4; bits++) {
        /* Deliberately unaligned range; test both 32B and 64B line geometry.
         * Synthetic skip bits are used only for nonexecuted data. */
        for (unsigned logline = 3; logline <= 4; logline++) {
            uint64_t ctr = logline | (uint64_t)logline << 16 |
                           (uint64_t)(bits & 1) << 28 | (uint64_t)(bits >> 1) << 29;
            uintptr_t start = (uintptr_t)p + 3, end = (uintptr_t)p + 153;
            unsigned line = 4u << logline;
            unsigned count = (end - 1) / line - start / line + 1;
            uint64_t got = cng_flush_icache_test((void *)start, (void *)end, ctr);
            assert((got >> 32) == (bits & 1 ? 0 : count));
            assert((uint32_t)got == (bits & 2 ? 0 : count));
            assert(cng_flush_icache_test(p, p, ctr) == 0);
            assert(cng_flush_icache_test(p + 8, p, ctr) == 0);
        }
    }
    /* Execute using real CTR_EL0, repeatedly modifying already executed code. */
    for (unsigned value = 1; value <= 100; value++) {
        assert(mprotect(p, page, PROT_READ | PROT_WRITE) == 0);
        ((uint32_t *)p)[0] = 0x52800000u | value << 5; /* mov w0,#value */
        ((uint32_t *)p)[1] = 0xd65f03c0u;
        cng_flush_icache(p, p + 8);
        assert(mprotect(p, page, PROT_READ | PROT_EXEC) == 0);
        assert(((unsigned (*)(void))p)() == value);
    }
    assert(munmap(p, page * 2) == 0);
    puts("PASS IDC/DIC combinations, cache-line coverage, empty ranges and self-modified code");
}
