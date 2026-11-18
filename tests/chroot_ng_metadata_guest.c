#define _GNU_SOURCE
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#define GUEST_UID 11000

static void check(const char *path, unsigned mode, unsigned uid, unsigned gid) {
    struct stat value;
    assert(stat(path, &value) == 0);
    assert((value.st_mode & 07777) == mode);
    assert(value.st_uid == uid && value.st_gid == gid);
}

int main(void) {
    check("/archive", 0644, 0, 123);
    int fd = open("/archive", O_RDONLY);
    assert(fd >= 0);
    struct stat value;
    assert(fstat(fd, &value) == 0);
    assert((value.st_mode & 07777) == 0644 && value.st_uid == 0 && value.st_gid == 123);
    assert(fstatat(fd, "", &value, AT_EMPTY_PATH) == 0 && value.st_uid == 0);
    unsigned char sx[256] = {0};
    assert(syscall(SYS_statx, AT_FDCWD, "/archive", 0, 0x7ff, sx) == 0);
    assert(*(uint32_t *)(sx + 20) == 0 && *(uint32_t *)(sx + 24) == 123);
    assert((*(uint16_t *)(sx + 28) & 07777) == 0644);
    close(fd);
    assert(chdir("/home/test") == 0);
    check("../../archive", 0644, 0, 123);
    int rootfd = open("/", O_RDONLY | O_DIRECTORY);
    assert(rootfd >= 0);
    assert(fstatat(rootfd, "archive", &value, 0) == 0 && value.st_uid == 0);
    close(rootfd);
    assert(open("/archive", O_WRONLY) == -1 && errno == EACCES);
    assert(mkdir("/home/test/work", 0700) == 0);
    fd = open("/home/test/work/new", O_RDWR | O_CREAT | O_EXCL, 0640);
    assert(fd >= 0);
    assert(write(fd, "payload", 7) == 7);
    check("/home/test/work/new", 0640, GUEST_UID, GUEST_UID);
    assert(fchmod(fd, 0600) == 0);
    assert(fstat(fd, &value) == 0 && (value.st_mode & 07777) == 0600);
    assert(rename("/home/test/work/new", "/home/test/work/renamed") == 0);
    check("/home/test/work/renamed", 0600, GUEST_UID, GUEST_UID);
    assert(fstat(fd, &value) == 0 && value.st_uid == GUEST_UID);
    assert(chmod("/home/test/work/renamed", 0644) == 0);
    check("/home/test/work/renamed", 0644, GUEST_UID, GUEST_UID);
    assert(symlink("renamed", "/home/test/work/link") == 0);
    check("/home/test/work/link", 0644, GUEST_UID, GUEST_UID);
    assert(lstat("/home/test/work/link", &value) == 0 && S_ISLNK(value.st_mode));
    assert(rename("/home/test/work", "/home/test/moved") == 0);
    check("/home/test/moved/renamed", 0644, GUEST_UID, GUEST_UID);
    check("/home/test/moved/link", 0644, GUEST_UID, GUEST_UID);
    assert(rename("/home/test/moved", "/home/test/work") == 0);
    assert(syscall(SYS_renameat2, AT_FDCWD, "/home/test/work", AT_FDCWD, "/home/test/moved", 1) == 0);
    check("/home/test/moved/renamed", 0644, GUEST_UID, GUEST_UID);
    assert(rename("/home/test/moved", "/home/test/work") == 0);
    assert(access("/home/test/work/renamed", F_OK) == 0);
    assert(link("/home/test/work/renamed", "/home/test/work/hard") == -1 && errno == EOPNOTSUPP);
    assert(chown("/home/test/work/renamed", 123, 456) == -1 && errno == EOPNOTSUPP);
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        execl("/setid", "/setid", NULL);
        _exit(errno == EOPNOTSUPP ? 0 : 2);
    }
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(fd);
    assert(unlink("/home/test/work/link") == 0);
    assert(unlink("/home/test/work/renamed") == 0);
    assert(rmdir("/home/test/work") == 0);
    puts("PASS metadata stat/fstat/statx, create/chmod/file rename/unlink directory trees, and explicit unsupported-operation gates");
    return 0;
}
