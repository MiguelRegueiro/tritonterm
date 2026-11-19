/* SPDX-License-Identifier: Apache-2.0 */
/* Broker-owned immutable virtual ELF images. No guest paths, ownership or
 * patched process addresses are cached. Every lookup carries an opened source
 * fd and validates its kernel stamp; holding that fd prevents inode recycling.
 * Entries and byte accounting belong exclusively to the single-threaded broker. */
#include "cng/image_cache.h"
#include "cng/profile.h"
#include "cng/rt.h"
#include "cng/syscall.h"
#include "cng/uapi.h"

#define IMAGE_MAGIC 0x434e47494d473031UL
int cng_g_image_cache;
static int exec_disabled, blocked;
static long failure_stage, failure_error;
void cng_image_cache_exec(char *const *envp) {
    const char *host = cng_broker_env("CNG_IMAGE_CACHE");
    cng_g_image_cache = host && !strcmp(host, "1");
    exec_disabled = 0;
    for (char *const *p = envp; p && *p; p++) {
        if (!strcmp(*p, "TRITONTERM_CNG_IMAGE_CACHE=0")) exec_disabled = 1;
        if (!strcmp(*p, "TRITONTERM_CNG_IMAGE_CACHE=1") && cng_g_image_cache) blocked = 0;
    }
    if (!blocked) failure_stage = failure_error = 0;
}
int cng_image_cache_enabled(void) { return cng_g_image_cache && !exec_disabled && !blocked; }
void cng_image_cache_failure(long stage, long error) {
    failure_stage = stage; failure_error = error;
    /* Never latch file changes/layout mismatches or resource pressure.
     * Stable platform refusals and an unavailable broker are optional-cache
     * failures, not reasons to repeatedly slow every inherited command. */
    int platform = stage == 12 || stage == 16 || stage == 18 || stage == 23 || stage == 31;
    if (stage == 1 || (platform && (error == -EPERM || error == -EACCES || error == -ENOSYS)))
        blocked = 1;
}
void cng_image_cache_diagnostic(int *out_blocked, long *stage, long *error) {
    *out_blocked = blocked; *stage = failure_stage; *error = failure_error;
}
int cng_image_stamp(int fd, unsigned char stamp[128]) {
    memset(stamp, 0, 128);
    /* Match the established loader's Android-safe descriptor stat path. */
    return CNG_SYS(__NR_newfstatat, fd, "", stamp, CNG_AT_EMPTY_PATH, 0, 0) == 0 &&
        ((*(unsigned *)(stamp + 16) & 0170000) == 0100000) ? 0 : -1;
}
int cng_image_same(const unsigned char a[128], const unsigned char b[128]) {
    /* Exclude atime: our own source reads may update it. Include dev/inode,
     * size, mode, ownership, link count, mtime/ctime with nanoseconds. */
    return !memcmp(a, b, 72) && !memcmp(a + 88, b + 88, 32);
}
struct entry {
    int source, image, live, watch;
    unsigned long bytes, age;
    unsigned char stamp[128];
};
static struct entry entries[CNG_IMAGE_SLOTS];
static unsigned long bytes, age, epoch;
static int notify = -1;
static void drop(struct entry *e) {
    if (!e->live) return;
    if (notify >= 0) CNG_SYS(__NR_inotify_rm_watch, notify, e->watch, 0, 0, 0, 0);
    sys_close(e->source); sys_close(e->image);
    bytes -= e->bytes;
    memset(e, 0, sizeof *e);
}
/* Kernel events cover same-size writes even when filesystem timestamp
 * granularity gives two mutations the same stat stamp. Overflow/lost watches
 * evict conservatively. Never retain a reusable image without a live watch. */
static int drain(void) {
    if (notify < 0) return 0;
    char buffer[4096];
    for (int batch = 0; batch < 64; batch++) {
        long n = sys_read(notify, buffer, sizeof buffer);
        if (n == -EINTR) continue;
        if (n == -EAGAIN) return 0;
        if (n <= 0) break;
        epoch++;
        for (long off = 0; off + 16 <= n;) {
            int wd; unsigned mask, length;
            memcpy(&wd, buffer + off, 4); memcpy(&mask, buffer + off + 4, 4);
            memcpy(&length, buffer + off + 12, 4);
            if (length > (unsigned long)(n - off - 16) || (mask & 0x4000)) goto lost;
            for (unsigned i = 0; i < CNG_IMAGE_SLOTS; i++)
                if (entries[i].live && entries[i].watch == wd) drop(&entries[i]);
            off += 16 + length;
        }
    }
 lost:
    for (unsigned i = 0; i < CNG_IMAGE_SLOTS; i++) drop(&entries[i]);
    sys_close(notify); notify = -1;
    return -1;
}

static int read_full(int fd, void *memory, unsigned long size, unsigned long offset) {
    unsigned long done = 0;
    while (done < size) {
        long n = sys_pread64(fd, (char *)memory + done, size - done, offset + done);
        if (n == -EINTR) continue;
        if (n <= 0) return -1;
        done += (unsigned long)n;
    }
    return 0;
}

int cng_image_cache_serve(int source, struct cng_bresp *r) {
    unsigned char stamp[128], after[128];
    r->key = 10; r->ret = -EBADF;
    if (source < 0 || cng_image_stamp(source, stamp)) return -1;
    r->key = 11; r->ret = -EAGAIN;
    if (drain()) return -1;
    for (unsigned i = 0; i < CNG_IMAGE_SLOTS; i++) {
        struct entry *e = &entries[i];
        if (e->live && cng_image_same(stamp, e->stamp)) {
            unsigned long seen = epoch;
            if (drain() || seen != epoch || !e->live) { r->key = 21; r->ret = -EAGAIN; return -1; }
            e->age = ++age;
            r->key = 0; r->ret = 0; r->size = e->bytes;
            return e->image;
        }
    }
    /* Retire an older generation of this inode before installing its watch. */
    for (unsigned i = 0; i < CNG_IMAGE_SLOTS; i++)
        if (entries[i].live && !memcmp(stamp, entries[i].stamp, 16)) drop(&entries[i]);
    if (drain()) return -1;
    if (notify < 0) {
        long n = CNG_SYS(__NR_inotify_init1, CNG_O_NONBLOCK | CNG_O_CLOEXEC, 0, 0, 0, 0, 0);
        if (n < 0) { r->key = 12; r->ret = (int)n; return -1; }
        notify = (int)n;
    }
    char source_path[64];
    cng_snprintf(source_path, sizeof source_path, "/proc/self/fd/%d", source);
    long watch = CNG_SYS(__NR_inotify_add_watch, notify, source_path,
        0x2 | 0x4 | 0x8 | 0x400 | 0x800 | 0x2000, 0, 0, 0);
    if (watch < 0) { r->key = 13; r->ret = (int)watch; return -1; }
    unsigned long seen = epoch;
    struct cng_elf_plan plan;
    struct cng_loaded loaded;
    r->key = 14; r->ret = -ENOEXEC;
    if (cng_elf_plan_fd(source, &plan, &loaded) != CNG_LOAD_OK) goto unwatch;
    unsigned long span = plan.hi - plan.lo;
    r->key = 15; r->ret = -EFBIG;
    if (!span || span > CNG_IMAGE_LIMIT) goto unwatch;
    unsigned long length = span + cng_page_up(sizeof(struct cng_image_meta));
    long fd = sys_memfd_create("cng-image", 3 /* CLOEXEC|ALLOW_SEALING */);
    r->key = 16; r->ret = (int)fd;
    if (fd < 0) goto unwatch;
    r->key = 17; r->ret = (int)sys_ftruncate((int)fd, length);
    if (r->ret != 0) { sys_close((int)fd); goto unwatch; }
    char *p = sys_mmap(0, length, CNG_PROT_READ | CNG_PROT_WRITE, CNG_MAP_SHARED, (int)fd, 0);
    r->key = 18;
    if (cng_is_err((long)p)) { r->ret = (int)(long)p; sys_close((int)fd); goto unwatch; }
    r->key = 19; r->ret = -EIO;
    int ok = 1;
    for (int i = 0; i < plan.eh.e_phnum; i++) {
        const Elf64_Phdr *ph = &plan.ph[i];
        if (ph->p_type == PT_LOAD && ph->p_filesz &&
            read_full(source, p + ph->p_vaddr - plan.lo, ph->p_filesz, ph->p_offset)) { ok = 0; break; }
    }
    struct cng_image_meta *m = (void *)(p + span);
    m->magic = IMAGE_MAGIC; m->span = span; m->lo = plan.lo;
    memcpy(&m->header, &plan.eh, sizeof plan.eh);
    memcpy(m->ph, plan.ph, plan.eh.e_phnum * sizeof plan.ph[0]);
    cng_code_ranges(source, &m->code);
    /* Index executable bytes only, in virtual-address order. Do not fault
     * sparse BSS or data pages just to look for instruction words. */
    unsigned long next = 0;
    int first = 1;
    for (int pass = 0; ok && pass < plan.eh.e_phnum; pass++) {
        const Elf64_Phdr *segment = 0;
        for (int i = 0; i < plan.eh.e_phnum; i++) {
            const Elf64_Phdr *candidate = &plan.ph[i];
            if (candidate->p_type != PT_LOAD || !(candidate->p_flags & PF_X) || !candidate->p_filesz ||
                (!first && candidate->p_vaddr <= next)) continue;
            if (!segment || candidate->p_vaddr < segment->p_vaddr) segment = candidate;
        }
        if (!segment) break;
        first = 0; next = segment->p_vaddr;
        unsigned long start = segment->p_vaddr - plan.lo;
        unsigned long end = start + segment->p_filesz;
        for (unsigned long at = (start + 3) & ~3UL; at + 4 <= end; at += 4) {
            if (*(u32 *)(p + at) != 0xd4000001u) continue;
            if (m->nsites == CNG_IMAGE_SITES) { r->key = 20; r->ret = -E2BIG; ok = 0; break; }
            m->sites[m->nsites++] = at;
        }
    }
    if (cng_image_stamp(source, after) || !cng_image_same(stamp, after) || drain() || seen != epoch) {
        r->key = 21; r->ret = -EAGAIN; ok = 0;
    }
    /* Establish coherence once before publishing an immutable image. */
    if (ok) for (int i = 0; i < plan.eh.e_phnum; i++) {
        const Elf64_Phdr *ph = &plan.ph[i];
        if (ph->p_type == PT_LOAD && (ph->p_flags & PF_X) && ph->p_filesz)
            cng_flush_icache(p + ph->p_vaddr - plan.lo,
                              p + ph->p_vaddr - plan.lo + ph->p_filesz);
    }
    sys_munmap(p, length);
    if (!ok) { sys_close((int)fd); goto unwatch; }
    r->key = 23; r->ret = (int)sys_fcntl((int)fd, 1033 /*ADD_SEALS*/, 15);
    if (r->ret != 0) { sys_close((int)fd); goto unwatch; }
    /* Keep the original sealed descriptor. Android may allow its use/map
     * but deny a new open through /proc/self/fd. Seals prohibit image writes,
     * truncation and future writable shared aliases despite O_RDWR access. */
    struct entry *slot;
    for (;;) {
        slot = 0;
        struct entry *oldest = 0;
        for (unsigned i = 0; i < CNG_IMAGE_SLOTS; i++) {
            if (!entries[i].live) { if (!slot) slot = &entries[i]; }
            else if (!oldest || entries[i].age < oldest->age) oldest = &entries[i];
        }
        if (slot && bytes + length <= CNG_IMAGE_BUDGET) break;
        if (!oldest) { r->key = 26; r->ret = -ENOMEM; sys_close((int)fd); goto unwatch; }
        drop(oldest);
    }
    slot->watch = (int)watch; slot->source = source; slot->image = (int)fd;
    slot->bytes = length; slot->age = ++age; slot->live = 1;
    memcpy(slot->stamp, stamp, sizeof stamp); bytes += length;
    r->key = 0; r->ret = 1; r->size = length;
    return slot->image;
 unwatch:
    CNG_SYS(__NR_inotify_rm_watch, notify, watch, 0, 0, 0, 0);
    return -1;
}

int cng_image_cache_get(int source, const Elf64_Ehdr *header, const Elf64_Phdr *ph,
                        unsigned long span, unsigned long lo,
                        struct cng_image_meta *meta) {
    unsigned char before[128], after[128];
    if (cng_image_stamp(source, before)) { cng_image_cache_failure(2, -EBADF); return -1; }
    struct cng_breq q = {0}; struct cng_bresp r = {0}; int fd = -1;
    q.op = CNG_REQ_IMAGE; q.arg = (int)cng_page_size;
    int rpc = cng_broker_rpc_fd(&q, source, &r, &fd);
    if (rpc != 0 || r.ret < 0 || fd < 0) {
        if (fd >= 0) sys_close(fd);
        cng_image_cache_failure(rpc || !r.key ? 1 : r.key,
                                rpc || r.ret >= 0 ? -EIO : r.ret);
        cng_profile_add(CNG_P_IMAGE_FALLBACK, 1); return -1;
    }
    long stage = 4, error = -EINVAL;
    if (r.size != span + cng_page_up(sizeof *meta)) goto invalid;
    stage = 5; error = -EIO;
    if (read_full(fd, meta, sizeof *meta, span)) goto invalid;
    stage = 6; error = -EINVAL;
    if (meta->magic != IMAGE_MAGIC || meta->span != span || meta->lo != lo) goto invalid;
    stage = 7; error = -EAGAIN;
    if (memcmp(&meta->header, header, sizeof *header) ||
        memcmp(meta->ph, ph, header->e_phnum * sizeof *ph)) goto invalid;
    stage = 8; error = -EINVAL;
    if (meta->nsites > CNG_IMAGE_SITES || meta->code.n < 0 || meta->code.n > CNG_CODE_RANGES_MAX) goto invalid;
    unsigned long previous = 0;
    for (unsigned long i = 0; i < meta->nsites; i++) {
        unsigned long site = meta->sites[i];
        if ((site & 3) || site + 4 > span || (i && site <= previous)) goto invalid;
        previous = site;
    }
    stage = 9; error = -EAGAIN;
    if (cng_image_stamp(source, after) || !cng_image_same(before, after)) goto invalid;
    cng_profile_add(r.ret ? CNG_P_IMAGE_MISS : CNG_P_IMAGE_HIT, 1);
    return fd;
 invalid:
    cng_image_cache_failure(stage, error); sys_close(fd);
    cng_profile_add(CNG_P_IMAGE_FALLBACK, 1); return -1;
}
