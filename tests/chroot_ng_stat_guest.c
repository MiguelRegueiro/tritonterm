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

static struct stat get(const char *path, int flags) {
    struct stat st;
    assert(syscall(SYS_newfstatat, AT_FDCWD, path, &st, flags) == 0);
    return st;
}

int main(int argc, char **argv) {
    assert(argc > 1);
    struct stat st;
    if (!strcmp(argv[1], "scan")) {
        for (int pass = 0; pass < 3; pass++) {
            for (int dir = 0; dir < 16; dir++) for (int file = 0; file < 32; file++) {
                char path[256];
                snprintf(path, sizeof path, "/tree/level%d/file%d", dir, file);
                st = get(path, file & 1 ? AT_SYMLINK_NOFOLLOW : 0);
                assert(S_ISREG(st.st_mode) && st.st_size == 4);
                assert(st.st_uid == 123 && st.st_gid == 456 && (st.st_mode & 07777) == 0640);
            }
        }
        puts("PASS 1536 absolute stats preserve ownership, permissions and size");
        return 0;
    }
    if (!strcmp(argv[1], "race")) {
        assert(argc == 3);
        unsigned long long ino = strtoull(argv[2], 0, 10);
        for (int i = 0; i < 2000; i++) {
            errno = 0;
            if (lstat("/race/file", &st) == 0) {
                assert(S_ISREG(st.st_mode) && st.st_ino == ino);
            } else assert(errno == ENOENT || errno == ENOTDIR || errno == ELOOP);
        }
        puts("PASS concurrent parent replacement exposes no outside inode");
        return 0;
    }
    if (!strcmp(argv[1], "live")) {
        st = get("/inside/file", 0);
        unsigned long long before = st.st_ino;
        puts("READY"); fflush(stdout);
        assert(getchar() == '\n');
        st = get("/inside/file", 0);
        assert(st.st_ino != before && st.st_size == 11);
        puts("PASS same-process replacement is visible");
        return 0;
    }
    if (!strcmp(argv[1], "chroot")) {
        st = get("/inside/file", 0);
        unsigned long long before = st.st_ino;
        assert(chroot("/jail") == 0 && chdir("/") == 0);
        st = get("/inside/file", 0);
        assert(st.st_ino != before && st.st_size == 4);
        puts("PASS changed filesystem view resolves against the new root");
        return 0;
    }
    assert(!strcmp(argv[1], "correctness"));
    struct stat original = get("/inside/file", 0);
    assert(S_ISREG(original.st_mode) && original.st_uid == 123 && original.st_gid == 456);
    const char *paths[] = {"/inside/file", "//inside/file", "/inside//file", "/inside/./file",
        "/inside/../inside/file", "/parent-link/file", "/relative-parent/file", "/final-link"};
    for (unsigned i = 0; i < sizeof paths / sizeof *paths; i++) {
        st = get(paths[i], 0); assert(st.st_ino == original.st_ino);
    }
    st = get("/final-link", AT_SYMLINK_NOFOLLOW); assert(S_ISLNK(st.st_mode));
    st = get("/inside", 0); assert(S_ISDIR(st.st_mode));
    st = get("/inside/", AT_SYMLINK_NOFOLLOW); assert(S_ISDIR(st.st_mode));
    st = get("/parent-link/", AT_SYMLINK_NOFOLLOW); assert(S_ISDIR(st.st_mode));
    assert(lstat("/inside/file/", &st) == -1 && errno == ENOTDIR);
    assert(stat("/missing", &st) == -1 && errno == ENOENT);
    assert(stat("/loop/file", &st) == -1 && errno == ELOOP);
    errno = 0;
    long invalid = syscall(SYS_newfstatat, AT_FDCWD, "/inside/file", &st, 0x40000000);
    if (invalid != -1 || errno != EINVAL)
        fprintf(stderr, "invalid stat flags: result=%ld errno=%d mode=%o\n", invalid, errno, st.st_mode);
    assert(invalid == -1 && errno == EINVAL);
    assert(syscall(SYS_newfstatat, AT_FDCWD, "/inside/file", NULL, 0) == -1 && errno == EFAULT);
    assert(syscall(SYS_newfstatat, AT_FDCWD, "/inside/file", (void *)1, 0) == -1 && errno == EFAULT);
    assert(syscall(SYS_newfstatat, -999, "/inside/file", &st, 0) == 0 && st.st_ino == original.st_ino);
    assert(syscall(SYS_newfstatat, -999, "file", &st, 0) == -1 && errno == EBADF);
    int fd = open("/inside", O_RDONLY | O_DIRECTORY); assert(fd >= 0);
    assert(fstatat(fd, "file", &st, 0) == 0 && st.st_ino == original.st_ino); close(fd);
    assert(chdir("/inside") == 0);
    st = get("file", 0); assert(st.st_ino == original.st_ino);
    st = get("../inside/file", AT_SYMLINK_NOFOLLOW); assert(st.st_ino == original.st_ino);
    assert(chdir("/") == 0);
    st = get("/bound/file", 0); assert(S_ISREG(st.st_mode) && st.st_size == 7 && st.st_ino != original.st_ino);
    st = get("/bound-link/file", 0); assert(st.st_size == 7);
    st = get("/dev/null", 0); assert(S_ISCHR(st.st_mode));
    st = get("/proc/self/exe", 0); assert(S_ISREG(st.st_mode));
    assert(link("/inside/file", "/hard-alias") == 0);
    st = get("/inside/file", 0); assert(st.st_ino == original.st_ino && st.st_nlink == 2 && st.st_uid == 123);
    st = get("/hard-alias", AT_SYMLINK_NOFOLLOW); assert(S_ISREG(st.st_mode) && st.st_ino == original.st_ino && st.st_nlink == 2);
    assert(unlink("/hard-alias") == 0);
    puts("PASS absolute/relative stats, symlinks, binds, flags, invalid buffers, proc/dev and hardlink views");
    return 0;
}
