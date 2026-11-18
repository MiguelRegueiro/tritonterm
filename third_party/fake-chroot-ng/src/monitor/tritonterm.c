/* SPDX-License-Identifier: Apache-2.0 */
/* Experimental TritonTerm sidecar adapter. The package installer continues to
 * use PRoot. Set-id execution and unsupported metadata mutations fail explicitly
 * instead of silently changing the archive's ownership model. */
#include "cng/tritonterm.h"
#include "cng/broker.h"
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

int cng_triton_read(const char *host, struct cng_triton_record *record) {
    char path[CNG_PATH_MAX], data[128];
    if (!cng_triton_enabled() || !metadata_path(host, path, sizeof path, 0))
        return 0;
    long fd = sys_openat(CNG_AT_FDCWD, path, CNG_O_RDONLY | CNG_O_CLOEXEC | CNG_O_NOFOLLOW, 0);
    if (fd < 0)
        return 0;
    long n = sys_read((int)fd, data, sizeof data - 1);
    sys_close((int)fd);
    if (n <= 0)
        return 0;
    data[n] = '\0';
    const char *cursor = data;
    struct cng_triton_record parsed;
    if (!number(&cursor, 8, &parsed.mode) || parsed.mode > 07777 ||
        !number(&cursor, 10, &parsed.uid) || !number(&cursor, 10, &parsed.gid))
        return 0;
    *record = parsed;
    return 1;
}

static int fd_host(long fd, char *out, size_t size) {
    char link[64];
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

static long unsupported(void) {
    cng_puts(2, "TritonTerm experimental chroot-ng: this metadata operation is not supported yet; select PRoot for sudo, package management or unsupported metadata mutations.\n");
    return -EOPNOTSUPP;
}

long cng_triton_exec_check(const char *host) {
    struct cng_triton_record record;
    if (cng_triton_read(host, &record) && (record.mode & 06000))
        return unsupported();
    return 0;
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

static int read_host(long dirfd, const char *guest, int follow, char *out) {
    char input[CNG_PATH_MAX];
    if (cng_user_strcopyin(input, guest, sizeof input) < 0)
        return 0;
    return host_path(dirfd, input, follow, out);
}

long cng_triton_before(struct cng_triton_change *c, long nr, long a0,
                       long a1, long a2, long a3, long a4) {
    c->op = 0;
    c->source[0] = c->target[0] = '\0';
    if (!cng_triton_enabled())
        return 0;
    if (nr == __NR_openat2) {
        unsigned long flags;
        if (a3 < 8 || cng_user_copyin(&flags, (const void *)a2, sizeof flags) < 0)
            return 0;
        a2 = (long)flags;
        nr = __NR_openat;
    }
    int mutates = nr == __NR_openat || nr == __NR_openat2 || nr == __NR_mkdirat ||
        nr == __NR_unlinkat || nr == __NR_renameat || nr == __NR_renameat2 ||
        nr == __NR_fchmod || nr == __NR_fchmodat || nr == __NR_fchmodat2 ||
        nr == __NR_fchown || nr == __NR_fchownat || nr == __NR_linkat;
    if (!mutates)
        return 0;
    int readonly_open = nr == __NR_openat && !(a2 & (CNG_O_CREAT | CNG_O_TRUNC)) && !(a2 & 3);
    if (nr == __NR_linkat)
        return -EOPNOTSUPP;
    if ((!readonly_open && cng_fake_root()) || nr == __NR_fchown || nr == __NR_fchownat)
        return unsupported();
    int fd_form = nr == __NR_fchmod;
    int follow = nr != __NR_unlinkat && nr != __NR_renameat && nr != __NR_renameat2;
    if (!(fd_form ? fd_host(a0, c->source, sizeof c->source)
                  : read_host(a0, (const char *)a1, follow, c->source)))
        return 0;
    char meta[CNG_PATH_MAX];
    if (!metadata_path(c->source, meta, sizeof meta, 0))
        return 0;
    int present = cng_triton_read(c->source, &c->record);
    struct cng_cred_ids ids;
    cng_cred_ids(&ids);
    if (present && c->record.uid != ids.euid && nr != __NR_openat)
        return unsupported();
    if (nr == __NR_openat) {
        if (present) {
            unsigned perms = ids.euid == c->record.uid ? (c->record.mode >> 6) & 7
                : ids.egid == c->record.gid ? (c->record.mode >> 3) & 7 : c->record.mode & 7;
            unsigned need = (a2 & 3) == CNG_O_WRONLY ? 2 : (a2 & 3) == CNG_O_RDWR ? 6 : 4;
            if ((perms & need) != need)
                return -EACCES;
        }
        if ((a2 & CNG_O_CREAT) && !present)
            c->op = 1;
        return 0;
    }
    if (nr == __NR_mkdirat) {
        c->op = 2;
        return 0;
    }
    if (nr == __NR_fchmod || nr == __NR_fchmodat || nr == __NR_fchmodat2) {
        if (!present) {
            unsigned char st[128];
            if (CNG_SYS(__NR_newfstatat, CNG_AT_FDCWD, c->source, st, 0, 0, 0) < 0)
                return 0;
            c->record.uid = ids.euid;
            c->record.gid = ids.egid;
        }
        c->record.mode = (unsigned)(fd_form ? a1 : a2) & 07777;
        c->op = 3;
        return 0;
    }
    if (nr == __NR_unlinkat) {
        c->op = (a2 & CNG_AT_REMOVEDIR) ? 6 : 4;
        return 0;
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
        c->op = directory ? 7 : 5;
    }
    return 0;
}

long cng_triton_after(struct cng_triton_change *c, long result) {
    if (result < 0 || !c->op)
        return result;
    if (c->op == 1 || c->op == 2) {
        unsigned char st[128];
        long r = c->op == 1 ? sys_fstat((int)result, st)
            : CNG_SYS(__NR_newfstatat, CNG_AT_FDCWD, c->source, st, 0, 0, 0);
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
    } else if (c->op == 3) {
        if (!write_record(c->source, &c->record))
            return -EIO;
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
