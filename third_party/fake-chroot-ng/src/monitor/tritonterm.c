#include "cng/profile.h"
/* SPDX-License-Identifier: Apache-2.0 */
/* TritonTerm ownership/mode sidecars shared with the PRoot backend.
 * Synthetic ownership never changes the Android app UID or host ownership. */
#include "cng/tritonterm.h"
#include "cng/broker.h"
#include "cng/l2s.h"
#include "cng/pin.h"
#include "cng/monitor.h"
#include "cng/rt.h"
#include "cng/syscall.h"
#include "cng/uapi.h"

#define META_TAG ".proot-meta-file."
#define META_SUFFIX ".tritonterm-metadata"

int cng_triton_enabled(void) {
    const char *value = cng_broker_env("TRITONTERM_USERLAND_METADATA");
    return value && strcmp(value, "1") == 0;
}

static int metadata_path(const char *host, char *out, size_t size, int tree) {
    if (!cng_g_fs || !host || host[0] != '/')
        return 0;
    const struct cng_fs *fs;
    unsigned seq;
    char root[512];
    do {
        seq = cng_fs_read_begin(&fs);
        cng_strlcpy(root, fs->rootfs, sizeof root);
    } while (cng_fs_read_retry(seq));
    size_t root_len = strlen(root);
    if (!root_len || strncmp(host, root, root_len) ||
        (host[root_len] && host[root_len] != '/'))
        return 0;
    const char *relative = host + root_len;
    while (*relative == '/')
        relative++;
    char path[CNG_PATH_MAX];
    size_t n = cng_strlcpy(path, relative, sizeof path);
    if (n >= sizeof path)
        return 0;
    while (n && path[n - 1] == '/')
        path[--n] = '\0';
    if (tree)
        return cng_snprintf(out, size, "%s%s/%s", root, META_SUFFIX, path) < size;
    if (!n)
        return cng_snprintf(out, size, "%s%s/%sroot", root, META_SUFFIX, META_TAG) < size;
    char *leaf = strrchr(path, '/');
    if (!leaf)
        return cng_snprintf(out, size, "%s%s/%s%s", root, META_SUFFIX, META_TAG, path) < size;
    *leaf++ = '\0';
    return cng_snprintf(out, size, "%s%s/%s/%s%s", root, META_SUFFIX, path, META_TAG, leaf) < size;
}

static int number(const char **cursor, unsigned base, unsigned *result) {
    const char *p = *cursor;
    while (*p == ' ' || *p == '\n' || *p == '\t' || *p == '\r')
        p++;
    unsigned value = 0;
    int digits = 0;
    while (*p >= '0' && *p <= '9') {
        unsigned digit = (unsigned)(*p++ - '0');
        if (digit >= base || value > (0xffffffffu - digit) / base)
            return 0;
        value = value * base + digit;
        digits++;
    }
    if (!digits || (*p && *p != ' ' && *p != '\n' && *p != '\t' && *p != '\r'))
        return 0;
    *cursor = p;
    *result = value;
    return 1;
}

/* A bounded, process-local parsed-record cache. Every hit revalidates the
 * pathname with lstat: inode replacement, in-place edits, permissions changes
 * and updates from PRoot/other processes must not become stale ownership.
 * Atime is deliberately excluded (our reads can change it). Admit only after
 * a second lookup, so a cold scan does not add stat calls to every record.
 * Try-locks protect only memory copies; contention/reentrant signals/fork
 * never wait for a lock held by a suspended or vanished task. */
#define META_CACHE_SLOTS 256
struct meta_cache_entry {
    unsigned busy;
    int valid;
    char path[CNG_PATH_MAX];
    unsigned char stamp[128];
    struct cng_triton_record record;
};
static struct meta_cache_entry meta_cache[META_CACHE_SLOTS];

static struct meta_cache_entry *cache_slot(const char *path) {
    const char *setting = cng_broker_env("TRITONTERM_METADATA_CACHE");
    if (setting && !strcmp(setting, "0"))
        return 0;
    unsigned hash = 2166136261u;
    for (const unsigned char *p = (const unsigned char *)path; *p; p++)
        hash = (hash ^ *p) * 16777619u;
    return &meta_cache[hash % META_CACHE_SLOTS];
}

static int cache_lock(struct meta_cache_entry *entry) {
    unsigned free = 0;
    return entry && __atomic_compare_exchange_n(&entry->busy, &free, 1, 0,
                                                 __ATOMIC_ACQUIRE, __ATOMIC_RELAXED);
}
static void cache_unlock(struct meta_cache_entry *entry) {
    __atomic_store_n(&entry->busy, 0, __ATOMIC_RELEASE);
}
static int same_stamp(const unsigned char *a, const unsigned char *b) {
    /* AArch64 kernel stat: dev/ino/mode/nlink/uid/gid/rdev, size,
     * mtime + ctime (seconds and nanoseconds). No libc struct/padding. */
    return !memcmp(a, b, 40) && !memcmp(a + 48, b + 48, 8) &&
           !memcmp(a + 88, b + 88, 32);
}

static int cng_triton_read_profiled(const char *host, struct cng_triton_record *record) {
    char path[CNG_PATH_MAX], data[128];
    if (!cng_triton_enabled() || !metadata_path(host, path, sizeof path, 0))
        return 0;
    struct meta_cache_entry *entry = cache_slot(path);
    unsigned char before[128], after[128];
    struct cng_triton_record cached;
    int known = 0, valid = 0;
    if (cache_lock(entry)) {
        if (!strcmp(entry->path, path)) {
            known = 1;
            valid = entry->valid;
            if (valid) {
                memcpy(before, entry->stamp, sizeof before);
                cached = entry->record;
            }
        } else if (entry->path[0])
            cng_profile_add(CNG_P_META_COLLISION, 1);
        cache_unlock(entry);
    }
    if (valid && CNG_SYS(__NR_newfstatat, CNG_AT_FDCWD, path, after,
                         CNG_AT_SYMLINK_NOFOLLOW, 0, 0) == 0 &&
        same_stamp(before, after)) {
        cng_profile_add(CNG_P_METADATA_HITS, 1);
        *record = cached;
        return 1;
    }
    cng_profile_add(CNG_P_METADATA_OPENS, 1);
    long fd = sys_openat(CNG_AT_FDCWD, path, CNG_O_RDONLY | CNG_O_CLOEXEC | CNG_O_NOFOLLOW, 0);
    if (fd < 0) {
        cng_profile_add(fd == -ENOENT ? CNG_P_META_ABSENT : CNG_P_META_OPEN_ERROR, 1);
        return 0;
    }
    cng_profile_add(CNG_P_META_OPEN_OK, 1);
    int snapshot = known && sys_fstat((int)fd, before) == 0;
    long n = sys_read((int)fd, data, sizeof data - 1);
    /* Check both sides of the read: PRoot may edit a record in place. A
     * concurrent mutation may still affect this one read, as before, but
     * must never seed a cached mixture with the new file's timestamp. */
    snapshot = snapshot && sys_fstat((int)fd, after) == 0 && same_stamp(before, after);
    sys_close((int)fd);
    if (n <= 0)
        return 0;
    data[n] = '\0';
    const char *cursor = data;
    struct cng_triton_record parsed;
    if (!number(&cursor, 8, &parsed.mode) || parsed.mode > 07777 ||
        !number(&cursor, 10, &parsed.uid) || !number(&cursor, 10, &parsed.gid))
        return 0;
    if (cache_lock(entry)) {
        cng_strlcpy(entry->path, path, sizeof entry->path);
        entry->record = parsed;
        entry->valid = snapshot;
        if (snapshot) {
            memcpy(entry->stamp, after, sizeof after);
            cng_profile_add(CNG_P_META_ADMISSION, 1);
        }
        cache_unlock(entry);
    }
    cng_profile_add(CNG_P_META_PARSED, 1);
    *record = parsed;
    return 1;
}

static int fd_host(long fd, char *out, size_t size) {
    char link[64];
    if ((int)fd == CNG_AT_FDCWD)
        cng_strlcpy(link, "/proc/self/cwd", sizeof link);
    else
        cng_snprintf(link, sizeof link, "/proc/self/fd/%d", (int)fd);
    long n = sys_readlinkat(CNG_AT_FDCWD, link, out, size - 1);
    if (n <= 0 || (size_t)n >= size - 1)
        return 0;
    out[n] = '\0';
    const char deleted[] = " (deleted)";
    if ((size_t)n >= sizeof deleted - 1 &&
        !strcmp(out + n - (sizeof deleted - 1), deleted))
        return 0;
    return out[0] == '/';
}

static int host_path(long dirfd, const char *path, int follow, char *out) {
    if (!path || !path[0])
        return fd_host(dirfd, out, CNG_PATH_MAX);
    return cng_resolve_at(dirfd, path, follow, out, CNG_PATH_MAX) == 0;
}

void cng_triton_stat(long dirfd, const char *host, void *st, int statx) {
    if (!cng_triton_enabled())
        return;
    struct cng_triton_record record;
    char resolved[CNG_PATH_MAX];
    const char *path = host;
    if (!path || !path[0]) {
        if (!fd_host(dirfd, resolved, sizeof resolved))
            return;
        path = resolved;
    } else if (path[0] != '/') {
        unsigned type = statx ? *(unsigned short *)((char *)st + 28)
                              : *(unsigned *)((char *)st + 16);
        if (!host_path(dirfd, path, (type & CNG_S_IFMT) != 0120000u, resolved))
            return;
        path = resolved;
    }
    if (!cng_triton_read(path, &record))
        return;
    char *buffer = st;
    if (statx) {
        unsigned short *mode = (unsigned short *)(buffer + 28);
        *mode = (*mode & CNG_S_IFMT) | record.mode;
        *(unsigned *)(buffer + 20) = record.uid;
        *(unsigned *)(buffer + 24) = record.gid;
    } else {
        unsigned *mode = (unsigned *)(buffer + 16);
        *mode = (*mode & CNG_S_IFMT) | record.mode;
        *(unsigned *)(buffer + 24) = record.uid;
        *(unsigned *)(buffer + 28) = record.gid;
    }
}

void cng_triton_fd_stat(long fd, void *buf, int statx) {
    /* The l2s link count and ownership overlay both need the same live fd
     * target. Resolve it once for this operation; never cache fd numbers. */
    char host[CNG_PATH_MAX];
    int have = cng_triton_enabled() && fd_host(fd, host, sizeof host);
    if (cng_g_l2s) {
        if (have)
            cng_l2s_fix_host(host, buf, statx);
        else if (statx)
            cng_l2s_fix_fd_statx(fd, buf);
        else
            cng_l2s_fix_fd(fd, buf);
    }
    if (have)
        cng_triton_stat(fd, host, buf, statx);
}

static long unsupported(void) {
    cng_puts(2, "TritonTerm chroot-ng: these rename flags are not supported by the metadata adapter; use PRoot for this operation.\n");
    return -EOPNOTSUPP;
}

static unsigned permissions(const struct cng_triton_record *r,
                            const struct cng_cred_ids *ids, int real) {
    unsigned groups[CNG_NGROUPS_MAX];
    unsigned uid = real ? ids->ruid : ids->fsuid;
    unsigned gid = real ? ids->rgid : ids->fsgid;
    if (uid == r->uid)
        return (r->mode >> 6) & 7;
    int match = gid == r->gid;
    if (!match) {
        int n = cng_cred_groups(groups, 0);
        for (int i = 0; i < n; i++)
            match |= groups[i] == r->gid;
    }
    return (r->mode >> (match ? 3 : 0)) & 7;
}

long cng_triton_exec_check(const char *host) {
    struct cng_triton_record record;
    if (!cng_triton_read(host, &record))
        return 0;
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    if (ids.fsuid == 0)
        return record.mode & 0111 ? 0 : -EACCES;
    return permissions(&record, &ids, 0) & 1 ? 0 : -EACCES;
}

static int make_parent(char *path) {
    for (char *p = path + 1; *p; p++) {
        if (*p != '/')
            continue;
        *p = '\0';
        long r = CNG_SYS(__NR_mkdirat, CNG_AT_FDCWD, path, 0700, 0, 0, 0);
        *p = '/';
        if (r < 0 && r != -EEXIST)
            return 0;
    }
    return 1;
}

static int write_record(const char *host, const struct cng_triton_record *record) {
    char path[CNG_PATH_MAX], temp[CNG_PATH_MAX], data[96];
    if (!metadata_path(host, path, sizeof path, 0))
        return 1;
    if (!make_parent(path))
        return 0;
    if (cng_snprintf(temp, sizeof temp, "%s.cng-%ld-%ld", path, sys_getpid(), sys_gettid()) >= sizeof temp)
        return 0;
    long fd = sys_openat(CNG_AT_FDCWD, temp, CNG_O_WRONLY | CNG_O_CREAT | CNG_O_TRUNC | CNG_O_CLOEXEC | CNG_O_NOFOLLOW, 0600);
    if (fd < 0)
        return 0;
    size_t n = cng_snprintf(data, sizeof data, "%o\n%u\n%u\n", record->mode, record->uid, record->gid);
    long r = cng_write_all((int)fd, data, n);
    sys_close((int)fd);
    if (r < 0 || (size_t)r != n || CNG_SYS(__NR_renameat, CNG_AT_FDCWD, temp, CNG_AT_FDCWD, path, 0, 0) < 0) {
        CNG_SYS(__NR_unlinkat, CNG_AT_FDCWD, temp, 0, 0, 0, 0);
        return 0;
    }
    return 1;
}

static void remove_record(const char *host) {
    char path[CNG_PATH_MAX];
    if (metadata_path(host, path, sizeof path, 0))
        CNG_SYS(__NR_unlinkat, CNG_AT_FDCWD, path, 0, 0, 0, 0);
}

/* Internal l2s moves bypass guest syscalls. Carry the sidecar along with the
 * backing inode so every member and an already-open descriptor agree. */
long cng_triton_move_record(const char *source, const char *target) {
    char old[CNG_PATH_MAX], new[CNG_PATH_MAX];
    if (!cng_triton_enabled() || !metadata_path(source, old, sizeof old, 0) ||
        !metadata_path(target, new, sizeof new, 0)) return 0;
    if (!make_parent(new)) return -EIO;
    long r = CNG_SYS(__NR_renameat, CNG_AT_FDCWD, old, CNG_AT_FDCWD, new, 0, 0);
    return r == -ENOENT ? 0 : r;
}

void cng_triton_remove_record(const char *host) {
    if (cng_triton_enabled()) remove_record(host);
}

static int read_host(long dirfd, const char *guest, int follow, char *out) {
    char input[CNG_PATH_MAX];
    if (cng_user_strcopyin(input, guest, sizeof input) < 0)
        return 0;
    return host_path(dirfd, input, follow, out);
}

static long parent_write(const char *host) {
    char parent[CNG_PATH_MAX];
    if (!host || host[0] != '/' || cng_strlcpy(parent, host, sizeof parent) >= sizeof parent)
        return 0;
    char *slash = strrchr(parent, '/');
    if (!slash) return 0;
    if (slash == parent) slash[1] = '\0'; else *slash = '\0';
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    if (ids.fsuid == 0) return 0;
    struct cng_triton_record record;
    if (cng_triton_read(parent, &record)) {
        if ((permissions(&record, &ids, 0) & 3) != 3)
            return -EACCES;
        if ((record.mode & 01000) && record.uid != ids.fsuid) {
            struct cng_triton_record leaf;
            if (cng_triton_read(host, &leaf) && leaf.uid != ids.fsuid)
                return -EPERM;
        }
    }
    return 0;
}

long cng_triton_access(const char *host, int mode, int real) {
    if (!cng_triton_enabled() || !host || !host[0] || mode == 0 || (mode & ~7))
        return 0;
    struct cng_triton_record r;
    if (!cng_triton_read(host, &r)) return 0;
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    unsigned uid = real ? ids.ruid : ids.fsuid;
    if (uid == 0)
        return (mode & 1) && !(r.mode & 0111) ? -EACCES : 0;
    return (permissions(&r, &ids, real) & (unsigned)mode) == (unsigned)mode ? 0 : -EACCES;
}

static long open_record(struct cng_triton_change *c, long flags,
                        int present, const struct cng_cred_ids *ids) {
    if (present && ids->fsuid != 0 && !(flags & CNG_O_PATH)) {
        unsigned perms = permissions(&c->record, ids, 0);
        unsigned need = (flags & 3) == CNG_O_WRONLY ? 2 : (flags & 3) == CNG_O_RDWR ? 6 : 4;
        if ((perms & need) != need)
            return -EACCES;
    }
    if ((flags & CNG_O_CREAT) && !present) {
        long r = parent_write(c->source);
        if (r < 0) return r;
        c->op = 1;
    }
    return 0;
}

long cng_triton_open_before(struct cng_triton_change *c, long dirfd,
                              const char *host, long flags, int follow) {
    c->op = 0;
    if (!cng_triton_enabled())
        return 0;
    if (!host || !host[0])
        return 0;
    if (host[0] == '/') {
        if (cng_strlcpy(c->source, host, sizeof c->source) >= sizeof c->source)
            return 0;
    } else if (!host_path(dirfd, host, follow, c->source)) {
        return 0;
    }
    char meta[CNG_PATH_MAX];
    if (!metadata_path(c->source, meta, sizeof meta, 0))
        return 0;
    int present = cng_triton_read(c->source, &c->record);
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    return open_record(c, flags, present, &ids);
}

/* Return one for a completed virtual attribute operation, zero for the
 * ordinary dispatcher. Validate the real target first so bad descriptors,
 * missing names, read-only binds and invalid flags retain their errors. */
static long attributes(struct cng_triton_change *c, long nr, long a0,
                       long a1, long a2, long a3, long a4) {
    int owner = nr == __NR_fchown || nr == __NR_fchownat;
    int fd = nr == __NR_fchmod || nr == __NR_fchown;
    int flags = fd ? 0 : nr == __NR_fchownat ? (int)a4
        : nr == __NR_fchmodat2 ? (int)a3 : 0;
    if (flags & ~(CNG_AT_SYMLINK_NOFOLLOW | CNG_AT_EMPTY_PATH))
        return -EINVAL;
    if (!fd) {
        char first;
        if (cng_user_copyin(&first, (const void *)a1, 1) < 0) return -EFAULT;
        if (!first && !(flags & CNG_AT_EMPTY_PATH)) return -ENOENT;
    } else {
        long status = sys_fcntl((int)a0, CNG_F_GETFL, 0);
        if (status < 0) return status;
        if (status & CNG_O_PATH) return -EBADF;
    }
    if (!(fd ? fd_host(a0, c->source, sizeof c->source)
          : read_host(a0, (const char *)a1,
                      !(flags & CNG_AT_SYMLINK_NOFOLLOW), c->source)))
        return 0; /* ordinary dispatcher preserves bad pointers/fds/paths */
    char meta[CNG_PATH_MAX];
    if (!metadata_path(c->source, meta, sizeof meta, 0))
        return 0;
    unsigned char st[128];
    long r = fd ? sys_fstat((int)a0, st)
        : cng_pin_fstatat(c->source, st, flags & CNG_AT_SYMLINK_NOFOLLOW);
    if (r < 0)
        return r;
    if (cng_g_l2s && (*(unsigned *)(st + 16) & CNG_S_IFMT) == 0120000u) {
        char backing[CNG_PATH_MAX];
        if (cng_l2s_resolve(c->source, backing, sizeof backing, 0) == 1) {
            cng_strlcpy(c->source, backing, sizeof c->source);
            r = cng_pin_fstatat(c->source, st, CNG_AT_SYMLINK_NOFOLLOW);
            if (r < 0) return r;
        }
    }
    if (cng_fs_host_ro(cng_g_fs, c->source))
        return -EROFS;
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    if (!cng_triton_read(c->source, &c->record)) {
        c->record.mode = *(unsigned *)(st + 16) & 07777;
        c->record.uid = ids.fsuid;
        c->record.gid = ids.fsgid;
    }
    if (ids.euid != 0 && ids.euid != c->record.uid)
        return -EPERM;
    if (owner) {
        unsigned uid = (unsigned)(fd ? a1 : a2);
        unsigned gid = (unsigned)(fd ? a2 : a3);
        if (ids.euid != 0) {
            struct cng_triton_record group_test = { .mode = 0070, .uid = ~0u, .gid = gid };
            if ((uid != ~0u && uid != c->record.uid) ||
                (gid != ~0u && gid != c->record.gid && !permissions(&group_test, &ids, 0)))
                return -EPERM;
        }
        if (uid != ~0u) c->record.uid = uid;
        if (gid != ~0u) c->record.gid = gid;
        if ((*(unsigned *)(st + 16) & CNG_S_IFMT) != CNG_S_IFDIR)
            c->record.mode &= ~06000u;
    } else {
        if ((*(unsigned *)(st + 16) & CNG_S_IFMT) == 0120000u)
            return -EOPNOTSUPP;
        c->record.mode = (unsigned)(fd ? a1 : a2) & 07777;
        /* Keep the app able to manage the inode. Guest-visible permissions
         * live in the sidecar; physical execute bits still serve the loader. */
        unsigned physical = (c->record.mode & 0111) | 0600;
        if ((*(unsigned *)(st + 16) & CNG_S_IFMT) == CNG_S_IFDIR)
            physical |= 0100;
        struct cng_pin pin;
        r = fd ? CNG_SYS(__NR_fchmod, a0, physical, 0, 0, 0, 0)
            : cng_pin_at(CNG_AT_FDCWD, c->source, &pin);
        if (!fd && r == 0) {
            r = CNG_SYS(__NR_fchmodat, pin.dfd, pin.name, physical, 0, 0, 0);
            cng_unpin(&pin);
        }
        if (r < 0) return r;
    }
    if (!write_record(c->source, &c->record))
        return -EIO;
    return 1;
}

long cng_triton_before(struct cng_triton_change *c, long nr, long a0,
                       long a1, long a2, long a3, long a4) {
    c->op = 0;
    c->source[0] = c->target[0] = '\0';
    if (!cng_triton_enabled() || nr == __NR_openat)
        return 0;
    if (nr == __NR_openat2) {
        unsigned long flags;
        if (a3 < 8 || cng_user_copyin(&flags, (const void *)a2, sizeof flags) < 0)
            return 0;
        a2 = (long)flags;
        nr = __NR_openat;
    }
    if (nr == __NR_fchmod || nr == __NR_fchmodat || nr == __NR_fchmodat2 ||
        nr == __NR_fchown || nr == __NR_fchownat)
        return attributes(c, nr, a0, a1, a2, a3, a4);
    int mutates = nr == __NR_openat || nr == __NR_openat2 || nr == __NR_mkdirat ||
        nr == __NR_unlinkat || nr == __NR_renameat || nr == __NR_renameat2 ||
        nr == __NR_linkat || nr == __NR_symlinkat;
    if (!mutates)
        return 0;
    if (nr == __NR_linkat) {
        if (read_host(a2, (const char *)a3, 0, c->target))
            return parent_write(c->target);
        return 0;
    }
    if (nr == __NR_symlinkat) {
        if (!read_host(a1, (const char *)a2, 0, c->source)) return 0;
        c->op = 8;
        return parent_write(c->source);
    }
    int follow = nr != __NR_unlinkat && nr != __NR_renameat && nr != __NR_renameat2;
    if (!read_host(a0, (const char *)a1, follow, c->source))
        return 0;
    char meta[CNG_PATH_MAX];
    if (!metadata_path(c->source, meta, sizeof meta, 0))
        return 0;
    int present = cng_triton_read(c->source, &c->record);
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    if (nr == __NR_openat)
        return open_record(c, a2, present, &ids);
    if (nr == __NR_mkdirat) {
        c->op = 2;
        return parent_write(c->source);
    }
    if (nr == __NR_unlinkat) {
        c->op = (a2 & CNG_AT_REMOVEDIR) ? 6 : 4;
        return parent_write(c->source);
    }
    if (nr == __NR_renameat || nr == __NR_renameat2) {
        unsigned char st[128];
        if (CNG_SYS(__NR_newfstatat, CNG_AT_FDCWD, c->source, st, CNG_AT_SYMLINK_NOFOLLOW, 0, 0) < 0)
            return 0;
        if (nr == __NR_renameat2 && (a4 & ~1L))
            return unsupported();
        int directory = (*(unsigned *)(st + 16) & CNG_S_IFMT) == CNG_S_IFDIR;
        if (!read_host(a2, (const char *)a3, 0, c->target))
            return 0;
        if (!present) {
            c->record.mode = *(unsigned *)(st + 16) & 07777;
            c->record.uid = ids.euid;
            c->record.gid = ids.egid;
        }
        long allowed = parent_write(c->source);
        if (allowed < 0) return allowed;
        allowed = parent_write(c->target);
        if (allowed < 0) return allowed;
        c->op = directory ? 7 : 5;
    }
    return 0;
}

long cng_triton_after(struct cng_triton_change *c, long result) {
    if (result < 0 || !c->op)
        return result;
    if (c->op == 1 || c->op == 2 || c->op == 8) {
        unsigned char st[128];
        long r = c->op == 1 ? sys_fstat((int)result, st)
            : CNG_SYS(__NR_newfstatat, CNG_AT_FDCWD, c->source, st, c->op == 8 ? CNG_AT_SYMLINK_NOFOLLOW : 0, 0, 0);
        if (r < 0)
            return result;
        struct cng_cred_ids ids;
        cng_cred_ids(&ids);
        c->record.mode = *(unsigned *)(st + 16) & 07777;
        c->record.uid = ids.fsuid;
        c->record.gid = ids.fsgid;
        if (!write_record(c->source, &c->record)) {
            if (c->op == 1)
                sys_close((int)result);
            return -EIO;
        }
    } else if (c->op == 4 || c->op == 6) {
        remove_record(c->source);
        if (c->op == 6) {
            char tree[CNG_PATH_MAX];
            if (metadata_path(c->source, tree, sizeof tree, 1))
                CNG_SYS(__NR_unlinkat, CNG_AT_FDCWD, tree, CNG_AT_REMOVEDIR, 0, 0, 0);
        }
    } else if (c->op == 5 || c->op == 7) {
        if (c->op == 7) {
            char source[CNG_PATH_MAX], target[CNG_PATH_MAX];
            if (metadata_path(c->source, source, sizeof source, 1) &&
                metadata_path(c->target, target, sizeof target, 1)) {
                if (!make_parent(target))
                    return -EIO;
                long moved = CNG_SYS(__NR_renameat, CNG_AT_FDCWD, source,
                                     CNG_AT_FDCWD, target, 0, 0);
                if (moved < 0 && moved != -ENOENT)
                    return -EIO;
                if (moved == -ENOENT)
                    CNG_SYS(__NR_unlinkat, CNG_AT_FDCWD, target, CNG_AT_REMOVEDIR, 0, 0, 0);
            }
        }
        if (!write_record(c->target, &c->record))
            return -EIO;
        remove_record(c->source);
    }
    return result;
}

int cng_triton_read(const char *host, struct cng_triton_record *record) {
    struct cng_profile_span span = cng_profile_begin(CNG_P_METADATA);
    int result = cng_triton_read_profiled(host, record);
    cng_profile_end(CNG_P_METADATA, span);
    return result;
}
