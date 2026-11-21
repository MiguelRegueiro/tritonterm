#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static struct statx get(int fd, const char *path, int flags, unsigned mask) {
    struct statx st;
    memset(&st, 0, sizeof st);
    assert(syscall(SYS_statx, fd, path, flags, mask, &st) == 0);
    return st;
}
static struct statx full(const char *path) {
    return get(AT_FDCWD, path, 0, STATX_BASIC_STATS | STATX_BTIME);
}
static void stamp(struct statx_timestamp a, struct statx_timestamp b) {
    assert(a.tv_sec == b.tv_sec && a.tv_nsec == b.tv_nsec);
}
int main(int argc, char **argv) {
    assert(argc > 1);
    if (!strncmp(argv[1], "scan", 4)) {
        int relative = !strcmp(argv[1], "scan-relative");
        int dirfd = !strcmp(argv[1], "scan-dirfd");
        int missing = !strcmp(argv[1], "scan-missing");
        int dirs[16];
        assert(chdir("/home/triton/.cargo/registry/src/index.fixture") == 0);
        if (dirfd) for (int d = 0; d < 16; d++) {
            char name[80]; snprintf(name, sizeof name, "crate%d/src", d);
            dirs[d] = open(name, O_PATH | O_DIRECTORY); assert(dirs[d] >= 0);
        }
        for (int pass = 0; pass < 3; pass++) for (int dir = 0; dir < 16; dir++)
            for (int file = 0; file < 32; file++) {
                char path[256];
                if (dirfd) snprintf(path, sizeof path, "file%d", file);
                else snprintf(path, sizeof path, "%scrate%d/src/%s%d", relative || missing ? "" :
                    "/home/triton/.cargo/registry/src/index.fixture/", dir, missing ? "absent" : "file", file);
                if (missing) {
                    struct statx st; memset(&st, 0x5a, sizeof st);
                    assert(syscall(SYS_statx, AT_FDCWD, path, 0, STATX_BASIC_STATS, &st) == -1 && errno == ENOENT);
                    for (unsigned i = 0; i < sizeof st; i++) assert(((unsigned char *)&st)[i] == 0x5a);
                    continue;
                }
                struct statx st = get(dirfd ? dirs[dir] : AT_FDCWD, path, file & 1 ? AT_SYMLINK_NOFOLLOW : 0,
                                     STATX_BASIC_STATS | STATX_BTIME);
                assert((st.stx_mask & STATX_BASIC_STATS) == STATX_BASIC_STATS);
                assert(S_ISREG(st.stx_mode) && st.stx_size == 4);
                assert(st.stx_uid == 123 && st.stx_gid == 456 && (st.stx_mode & 07777) == 0640);
            }
        if (dirfd) for (int d = 0; d < 16; d++) close(dirs[d]);
        puts("PASS 1536 deep statx requests preserve real fields, metadata or expected ENOENT"); return 0;
    }
    if (!strncmp(argv[1], "live", 4)) {
        int relative = !strcmp(argv[1], "live-relative");
        int fd = !strcmp(argv[1], "live-dirfd") ? open("/inside", O_PATH | O_DIRECTORY) : AT_FDCWD;
        assert(fd == AT_FDCWD || fd >= 0); assert(chdir("/inside") == 0);
        const char *path = relative || fd >= 0 ? "file" : "/inside/file";
        struct statx before = get(fd, path, 0, STATX_BASIC_STATS | STATX_BTIME);
        puts("READY"); fflush(stdout); assert(getchar() == '\n');
        struct statx after = get(fd, path, 0, STATX_BASIC_STATS | STATX_BTIME);
        if (fd >= 0) close(fd);
        assert(after.stx_ino != before.stx_ino && after.stx_size == 11);
        assert(after.stx_uid == 789 && after.stx_gid == 321 && (after.stx_mode & 07777) == 0600);
        puts("PASS replacement inode and externally changed metadata visible"); return 0;
    }
    if (!strcmp(argv[1], "chroot")) {
        struct statx before = full("/inside/file");
        assert(chroot("/jail") == 0 && chdir("/") == 0);
        struct statx after = full("/inside/file");
        assert(before.stx_ino != after.stx_ino && after.stx_size == 4);
        puts("PASS statx follows the changed filesystem view"); return 0;
    }
    if (!strncmp(argv[1], "race", 4)) {
        int fd = !strcmp(argv[1], "race-dirfd") ? open("/", O_PATH | O_DIRECTORY) : AT_FDCWD;
        assert(fd == AT_FDCWD || fd >= 0); assert(chdir("/") == 0);
        const char *path = !strcmp(argv[1], "race") ? "/race/file" : "race/file";
        assert(argc == 3); unsigned long long ino = strtoull(argv[2], NULL, 10);
        for (int n = 0; n < 2500; n++) {
            struct statx st;
            if (syscall(SYS_statx, fd, path, 0, STATX_BASIC_STATS, &st) == 0)
                assert(S_ISREG(st.stx_mode) && st.stx_ino == ino);
            else assert(errno == ENOENT || errno == ENOTDIR || errno == ELOOP);
        }
        puts("PASS statx parent replacement preserves containment"); return 0;
    }
    if (!strcmp(argv[1], "denied")) {
        struct statx sx; struct stat st;
        errno = 0;
        assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", 0, STATX_BASIC_STATS, &sx) == -1 && errno == EPERM);
        assert(syscall(SYS_newfstatat, AT_FDCWD, "/inside/file", &st, 0) == 0);
        assert(S_ISREG(st.st_mode) && st.st_uid == 123 && (st.st_mode & 07777) == 0640);
        puts("PASS denied statx retains EPERM and working stat fallback"); return 0;
    }
    assert(!strcmp(argv[1], "correctness"));
    _Static_assert(sizeof(struct statx) == 256, "kernel statx layout");
    struct statx oracle;
    FILE *f = fopen("/oracle", "rb"); assert(f && fread(&oracle, sizeof oracle, 1, f) == 1 && fclose(f) == 0);
    unsigned masks[] = {0, STATX_TYPE, STATX_SIZE, STATX_BASIC_STATS, STATX_BASIC_STATS | STATX_BTIME};
    int flags[] = {0, AT_SYMLINK_NOFOLLOW, AT_NO_AUTOMOUNT, AT_STATX_FORCE_SYNC, AT_STATX_DONT_SYNC,
                   AT_SYMLINK_NOFOLLOW | AT_NO_AUTOMOUNT | AT_STATX_DONT_SYNC};
    int testdir = open("/inside", O_PATH | O_DIRECTORY); assert(testdir >= 0);
    assert(chdir("/") == 0);
    for (int form = 0; form < 3; form++)
    for (unsigned m = 0; m < sizeof masks / sizeof *masks; m++)
        for (unsigned n = 0; n < sizeof flags / sizeof *flags; n++) {
            struct statx st = get(form == 2 ? testdir : form == 1 ? AT_FDCWD : -999,
                form == 2 ? "file" : form == 1 ? "inside/file" : "/inside/file", flags[n], masks[m]);
            assert((st.stx_mask & masks[m] & STATX_BASIC_STATS) == (oracle.stx_mask & masks[m] & STATX_BASIC_STATS));
            assert(st.stx_uid == 123 && st.stx_gid == 456 && (st.stx_mode & 07777) == 0640);
            assert(st.stx_blksize == oracle.stx_blksize);
            assert(st.stx_rdev_major == oracle.stx_rdev_major && st.stx_rdev_minor == oracle.stx_rdev_minor);
            if (st.stx_mask & STATX_TYPE) assert(S_ISREG(st.stx_mode));
            if (st.stx_mask & STATX_INO) assert(st.stx_ino == oracle.stx_ino);
            if (st.stx_mask & STATX_SIZE) assert(st.stx_size == oracle.stx_size);
            if (st.stx_mask & STATX_NLINK) assert(st.stx_nlink == oracle.stx_nlink);
            if (st.stx_mask & STATX_BLOCKS) assert(st.stx_blocks == oracle.stx_blocks);
            if (st.stx_mask & STATX_ATIME) stamp(st.stx_atime, oracle.stx_atime);
            if (st.stx_mask & STATX_MTIME) stamp(st.stx_mtime, oracle.stx_mtime);
            if (st.stx_mask & STATX_CTIME) stamp(st.stx_ctime, oracle.stx_ctime);
            if (st.stx_mask & STATX_BTIME) { assert(oracle.stx_mask & STATX_BTIME); stamp(st.stx_btime, oracle.stx_btime); }
            assert(st.stx_dev_major == oracle.stx_dev_major && st.stx_dev_minor == oracle.stx_dev_minor);
            assert(st.stx_attributes == oracle.stx_attributes && st.stx_attributes_mask == oracle.stx_attributes_mask);
        }
    close(testdir);
    const char *paths[] = {"/inside/file", "//inside/file", "/inside//file", "/inside/./file",
        "/inside/../inside/file", "/parent-link/file", "/relative-parent/file", "/final-link"};
    for (unsigned n = 0; n < sizeof paths / sizeof *paths; n++) {
        struct statx st = full(paths[n]); assert(st.stx_ino == oracle.stx_ino && st.stx_uid == 123);
    }
    assert(S_ISLNK(get(AT_FDCWD, "/final-link", AT_SYMLINK_NOFOLLOW, STATX_TYPE).stx_mode));
    assert(S_ISDIR(full("/inside/").stx_mode));
    assert(S_ISDIR(full("/parent-link/").stx_mode));
    struct statx st;
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file/", 0, STATX_BASIC_STATS, &st) == -1 && errno == ENOTDIR);
    assert(syscall(SYS_statx, AT_FDCWD, "/missing", 0, STATX_BASIC_STATS, &st) == -1 && errno == ENOENT);
    assert(syscall(SYS_statx, AT_FDCWD, "/loop/file", 0, STATX_BASIC_STATS, &st) == -1 && errno == ELOOP);
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", 0x40000000, STATX_BASIC_STATS, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", AT_STATX_FORCE_SYNC | AT_STATX_DONT_SYNC, STATX_BASIC_STATS, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", 0, 0x80000000u, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", 0, STATX_BASIC_STATS, (void *)1) == -1 && errno == EFAULT);
    assert(syscall(SYS_statx, AT_FDCWD, "/inside/file", 0, STATX_BASIC_STATS, NULL) == -1 && errno == EFAULT);
    assert(syscall(SYS_statx, -999, "file", 0, STATX_BASIC_STATS, &st) == -1 && errno == EBADF);
    int fd = open("/inside", O_RDONLY | O_DIRECTORY); assert(fd >= 0);
    assert(get(fd, "file", 0, STATX_BASIC_STATS).stx_ino == oracle.stx_ino);
    assert(syscall(SYS_statx, fd, "file/child", 0, STATX_BASIC_STATS, &st) == -1 && errno == ENOTDIR);
    assert(syscall(SYS_statx, fd, "missing", 0, STATX_BASIC_STATS, &st) == -1 && errno == ENOENT);
    assert(syscall(SYS_statx, fd, "missing", 0x40000000, STATX_BASIC_STATS, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, fd, "missing", 0, 0x80000000u, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, fd, "missing", AT_STATX_FORCE_SYNC | AT_STATX_DONT_SYNC, STATX_BASIC_STATS, &st) == -1 && errno == EINVAL);
    assert(syscall(SYS_statx, fd, "missing", 0, STATX_BASIC_STATS, (void *)1) == -1 && errno == ENOENT);
    close(fd);
    fd = open("/inside/file", O_RDONLY); assert(fd >= 0);
    assert(get(fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS).stx_ino == oracle.stx_ino); close(fd);
    assert(S_ISREG(full("/proc/self/status").stx_mode));
    assert(S_ISCHR(full("/dev/null").stx_mode));
    assert(S_ISREG(full("/bound/file").stx_mode));
    assert(full("/bound-link/file").stx_ino == full("/bound/file").stx_ino);
    assert(link("/inside/file", "/inside/alias") == 0);
    struct statx a = full("/inside/file"), b = full("/inside/alias");
    assert(a.stx_ino == b.stx_ino && a.stx_nlink == 2 && b.stx_nlink == 2);
    assert(get(AT_FDCWD, "/inside/file", AT_SYMLINK_NOFOLLOW, STATX_BASIC_STATS).stx_ino == a.stx_ino);
    puts("PASS statx masks, timestamps, sync flags, metadata, special paths and invalid arguments");
}
