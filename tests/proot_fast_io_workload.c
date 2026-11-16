#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <linux/filter.h>
#include <linux/futex.h>
#include <linux/seccomp.h>
#include <pthread.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define CHECK(condition) do { \
    if (!(condition)) { \
        fprintf(stderr, "FAIL line=%d check=%s errno=%d (%s)\n", \
                __LINE__, #condition, errno, strerror(errno)); \
        exit(1); \
    } \
} while (0)

static volatile sig_atomic_t delivered;
static pthread_mutex_t counter_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t ready_condition = PTHREAD_COND_INITIALIZER;
static unsigned counter;
static int workers_ready;
static int workers_go;

static void signal_handler(int signal_number)
{
    delivered = signal_number;
}

static void *worker(void *unused)
{
    (void) unused;
    CHECK(pthread_mutex_lock(&counter_mutex) == 0);
    workers_ready++;
    CHECK(pthread_cond_broadcast(&ready_condition) == 0);
    while (!workers_go)
        CHECK(pthread_cond_wait(&ready_condition, &counter_mutex) == 0);
    CHECK(pthread_mutex_unlock(&counter_mutex) == 0);
    for (unsigned i = 0; i < 1000; i++) {
        CHECK(pthread_mutex_lock(&counter_mutex) == 0);
        counter++;
        CHECK(pthread_mutex_unlock(&counter_mutex) == 0);
    }
    return NULL;
}

static void test_threads(void)
{
    pthread_t threads[4];
    for (unsigned i = 0; i < 4; i++)
        CHECK(pthread_create(&threads[i], NULL, worker, NULL) == 0);
    CHECK(pthread_mutex_lock(&counter_mutex) == 0);
    while (workers_ready < 4)
        CHECK(pthread_cond_wait(&ready_condition, &counter_mutex) == 0);
    workers_go = 1;
    CHECK(pthread_cond_broadcast(&ready_condition) == 0);
    CHECK(pthread_mutex_unlock(&counter_mutex) == 0);
    for (unsigned i = 0; i < 4; i++)
        CHECK(pthread_join(threads[i], NULL) == 0);
    CHECK(counter == 4000);
#ifdef SYS_futex
    int futex_word = 0;
    CHECK(syscall(SYS_futex, &futex_word, FUTEX_WAKE_PRIVATE, 1, NULL, NULL, 0) == 0);
#endif
    puts("PASS threads_futex");
}

static void wait_child(pid_t child, int expected)
{
    int status;
    pid_t result;
    do {
        result = waitpid(child, &status, 0);
    } while (result < 0 && errno == EINTR);
    CHECK(result == child);
    CHECK(WIFEXITED(status));
    CHECK(WEXITSTATUS(status) == expected);
}

static void test_processes(void)
{
    struct sigaction action = {0};
    action.sa_handler = signal_handler;
    CHECK(sigemptyset(&action.sa_mask) == 0);
    CHECK(sigaction(SIGUSR1, &action, NULL) == 0);
    CHECK(kill(getpid(), SIGUSR1) == 0);
    CHECK(delivered == SIGUSR1);

    pid_t child = fork();
    CHECK(child >= 0);
    if (!child)
        _exit(37);
    wait_child(child, 37);

    child = fork();
    CHECK(child >= 0);
    if (!child) {
        execl("/proc/self/exe", "/proc/self/exe", "--child", (char *) NULL);
        _exit(99);
    }
    wait_child(child, 23);

    child = fork();
    CHECK(child >= 0);
    if (!child) {
        raise(SIGTERM);
        _exit(98);
    }
    int status;
    CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFSIGNALED(status));
    CHECK(WTERMSIG(status) == SIGTERM);
    puts("PASS fork_exec_wait_signals");
}

static void test_statx_variants(void)
{
#ifdef SYS_statx
    int directory = open(".", O_RDONLY | O_DIRECTORY);
    int file = open("payload", O_RDONLY);
    CHECK(directory >= 0 && file >= 0);
    struct statx expected, info;
    CHECK(syscall(SYS_statx, directory, "payload", 0, STATX_BASIC_STATS, &expected) == 0);
    CHECK((expected.stx_mode & S_IFMT) == S_IFREG);
    CHECK((expected.stx_mode & 0777) == 0600 && expected.stx_size == 4096);
    CHECK(syscall(SYS_statx, directory, "symlink", 0, STATX_BASIC_STATS, &info) == 0);
    CHECK(info.stx_ino == expected.stx_ino && info.stx_size == expected.stx_size);
    CHECK(info.stx_mode == expected.stx_mode);
    CHECK(info.stx_uid == expected.stx_uid && info.stx_gid == expected.stx_gid);
    CHECK(syscall(SYS_statx, directory, "symlink", AT_SYMLINK_NOFOLLOW,
                  STATX_BASIC_STATS, &info) == 0);
    CHECK((info.stx_mode & S_IFMT) == S_IFLNK);
    CHECK(syscall(SYS_statx, file, "", AT_EMPTY_PATH, STATX_BASIC_STATS, &info) == 0);
    CHECK(info.stx_ino == expected.stx_ino && info.stx_size == expected.stx_size);
    CHECK(info.stx_mode == expected.stx_mode);
    CHECK(info.stx_uid == expected.stx_uid && info.stx_gid == expected.stx_gid);
    char path[PATH_MAX];
    CHECK(getcwd(path, sizeof(path)) != NULL);
    size_t length = strlen(path);
    CHECK(length + sizeof("/symlink") < sizeof(path));
    memcpy(path + length, "/symlink", sizeof("/symlink"));
    CHECK(syscall(SYS_statx, AT_FDCWD, path, 0, STATX_BASIC_STATS, &info) == 0);
    CHECK(info.stx_ino == expected.stx_ino && info.stx_mode == expected.stx_mode);
    CHECK(syscall(SYS_statx, AT_FDCWD, path, AT_SYMLINK_NOFOLLOW,
                  STATX_BASIC_STATS, &info) == 0);
    CHECK((info.stx_mode & S_IFMT) == S_IFLNK);
    errno = 0;
    CHECK(syscall(SYS_statx, directory, "missing", 0, STATX_BASIC_STATS, &info) == -1);
    CHECK(errno == ENOENT);
    errno = 0;
    CHECK(syscall(SYS_statx, directory, "payload/child", 0, STATX_BASIC_STATS, &info) == -1);
    CHECK(errno == ENOTDIR);
    CHECK(symlinkat("missing-target", directory, "dangling") == 0);
    errno = 0;
    CHECK(syscall(SYS_statx, directory, "dangling", 0, STATX_BASIC_STATS, &info) == -1);
    CHECK(errno == ENOENT);
    CHECK(syscall(SYS_statx, directory, "dangling", AT_SYMLINK_NOFOLLOW,
                  STATX_BASIC_STATS, &info) == 0);
    CHECK((info.stx_mode & S_IFMT) == S_IFLNK);
    CHECK(unlinkat(directory, "dangling", 0) == 0);
    errno = 0;
    CHECK(syscall(SYS_statx, directory, "transient", 0, STATX_BASIC_STATS, &info) == -1);
    CHECK(errno == ENOENT);
    int transient = openat(directory, "transient", O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(transient >= 0 && write(transient, "x", 1) == 1);
    CHECK(syscall(SYS_statx, directory, "transient", 0, STATX_BASIC_STATS, &info) == 0);
    CHECK((info.stx_mode & S_IFMT) == S_IFREG && info.stx_size == 1);
    CHECK(close(transient) == 0 && unlinkat(directory, "transient", 0) == 0);
    errno = 0;
    CHECK(syscall(SYS_statx, directory, "transient", 0, STATX_BASIC_STATS, &info) == -1);
    CHECK(errno == ENOENT);
    CHECK(close(file) == 0 && close(directory) == 0);
    puts("PASS statx_relative_symlink_empty_path_missing_transitions");
#endif
}

static void test_fd_variants(void)
{
    const char contents[] = "descriptor payload";
    int fd = open("fd-original", O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(fd >= 0);
    CHECK(write(fd, contents, sizeof(contents)) == (ssize_t) sizeof(contents));
    CHECK(chmod("fd-original", 0640) == 0);
    CHECK(rename("fd-original", "fd-renamed") == 0);
    struct stat info;
    CHECK(fstat(fd, &info) == 0);
    CHECK(S_ISREG(info.st_mode) && info.st_size == (off_t) sizeof(contents));
    CHECK((info.st_mode & 0777) == 0640);
    CHECK(info.st_uid == getuid() && info.st_gid == getgid());
    CHECK(unlink("fd-renamed") == 0);
    CHECK(fstat(fd, &info) == 0);
    CHECK(S_ISREG(info.st_mode) && info.st_size == (off_t) sizeof(contents));
    CHECK(info.st_uid == getuid() && info.st_gid == getgid());
    char bytes[sizeof(contents)];
    CHECK(pread(fd, bytes, sizeof(bytes), 0) == (ssize_t) sizeof(bytes));
    CHECK(memcmp(bytes, contents, sizeof(bytes)) == 0);
    CHECK(close(fd) == 0);

    int pipe_fds[2];
    CHECK(pipe(pipe_fds) == 0);
    CHECK(fstat(pipe_fds[0], &info) == 0 && S_ISFIFO(info.st_mode));
    CHECK(info.st_uid == getuid() && info.st_gid == getgid());
    CHECK(fstat(pipe_fds[1], &info) == 0 && S_ISFIFO(info.st_mode));
    CHECK(write(pipe_fds[1], "x", 1) == 1);
    CHECK(read(pipe_fds[0], bytes, 1) == 1 && bytes[0] == 'x');
    CHECK(close(pipe_fds[0]) == 0 && close(pipe_fds[1]) == 0);
    puts("PASS fstat_renamed_deleted_pipe");
}

static void test_files(void)
{
    char initial[PATH_MAX];
    char directory[] = "/tmp/tritonterm-fast-io.XXXXXX";
    CHECK(getcwd(initial, sizeof(initial)) != NULL);
    CHECK(mkdtemp(directory) != NULL);
    CHECK(chdir(directory) == 0);
    char current[PATH_MAX];
    CHECK(getcwd(current, sizeof(current)) != NULL);
    CHECK(strcmp(current, directory) == 0);
    test_fd_variants();

    int fd = open("payload", O_CREAT | O_RDWR | O_EXCL, 0640);
    CHECK(fd >= 0);
    CHECK(write(fd, "abcdefgh", 8) == 8);
    CHECK(pwrite(fd, "XY", 2, 2) == 2);
    char buffer[16] = {0};
    CHECK(pread(fd, buffer, 8, 0) == 8);
    CHECK(memcmp(buffer, "abXYefgh", 8) == 0);
    CHECK(lseek(fd, 0, SEEK_SET) == 0);
    struct iovec parts[] = {{buffer, 3}, {buffer + 3, 5}};
    CHECK(readv(fd, parts, 2) == 8);
    CHECK(memcmp(buffer, "abXYefgh", 8) == 0);
    CHECK(lseek(fd, 0, SEEK_END) == 8);
    struct iovec suffix[] = {{"12", 2}, {"34", 2}};
    CHECK(writev(fd, suffix, 2) == 4);

    CHECK(ftruncate(fd, 4096) == 0);
    char *mapping = mmap(NULL, 4096, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    CHECK(mapping != MAP_FAILED);
    CHECK(memcmp(mapping, "abXYefgh1234", 12) == 0);
    mapping[0] = 'Z';
    CHECK(msync(mapping, 4096, MS_SYNC) == 0);
    CHECK(mprotect(mapping, 4096, PROT_READ) == 0);
    CHECK(munmap(mapping, 4096) == 0);
    CHECK(pread(fd, buffer, 1, 0) == 1 && buffer[0] == 'Z');
    struct stat info;
    CHECK(fstat(fd, &info) == 0 && info.st_size == 4096);
    CHECK(close(fd) == 0);
    CHECK(chmod("payload", 0600) == 0);
    CHECK(stat("payload", &info) == 0);
    CHECK((info.st_mode & 0777) == 0600);
#ifdef SYS_statx
    struct statx extended;
    CHECK(syscall(SYS_statx, AT_FDCWD, "payload", 0, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_size == 4096);
    CHECK((extended.stx_mode & 0777) == 0600);
#endif
    CHECK(symlink("payload", "symlink") == 0);
    test_statx_variants();
    ssize_t count = readlink("symlink", buffer, sizeof(buffer));
    CHECK(count == 7 && memcmp(buffer, "payload", 7) == 0);
    CHECK(rename("payload", "renamed") == 0);
    CHECK(link("renamed", "hardlink") == 0);
    CHECK(unlink("renamed") == 0);
    fd = open("hardlink", O_RDONLY);
    CHECK(fd >= 0);
    CHECK(read(fd, buffer, 12) == 12);
    CHECK(memcmp(buffer, "ZbXYefgh1234", 12) == 0);
    CHECK(close(fd) == 0);
    errno = 0;
    CHECK(open("missing", O_RDONLY) == -1 && errno == ENOENT);
    errno = 0;
    CHECK(stat("missing", &info) == -1 && errno == ENOENT);
    errno = 0;
    CHECK(rename("missing", "other") == -1 && errno == ENOENT);
    errno = 0;
    CHECK(read(-1, buffer, 1) == -1 && errno == EBADF);
    CHECK(mkdir("subdirectory", 0700) == 0);
    errno = 0;
    CHECK(mkdir("subdirectory", 0700) == -1 && errno == EEXIST);
    CHECK(rmdir("subdirectory") == 0);
    CHECK(unlink("symlink") == 0);
    CHECK(unlink("hardlink") == 0);
    int parent = open(initial, O_DIRECTORY | O_RDONLY);
    CHECK(parent >= 0);
    CHECK(fchdir(parent) == 0);
    CHECK(close(parent) == 0);
    CHECK(rmdir(directory) == 0);
#ifdef SYS_setresgid
    CHECK(syscall(SYS_setresgid, (gid_t) -1, (gid_t) -1, (gid_t) -1) == 0);
#endif
    puts("PASS files_metadata_paths_errors");
}

static void install_traps(void)
{
    const int trapped[] = {
#ifdef SYS_chdir
        SYS_chdir,
#endif
#ifdef SYS_fchdir
        SYS_fchdir,
#endif
#ifdef SYS_getcwd
        SYS_getcwd,
#endif
#ifdef SYS_statx
        SYS_statx,
#endif
#ifdef SYS_clone3
        SYS_clone3,
#endif
#ifdef SYS_setresgid
        SYS_setresgid,
#endif
#ifdef SYS_rseq
        SYS_rseq,
#endif
#ifdef SYS_set_robust_list
        SYS_set_robust_list,
#endif
    };
    struct sock_filter instructions[2 * sizeof(trapped) / sizeof(trapped[0]) + 2];
    size_t count = 0;
    instructions[count++] = (struct sock_filter) BPF_STMT(BPF_LD | BPF_W | BPF_ABS,
                                                         offsetof(struct seccomp_data, nr));
    for (size_t i = 0; i < sizeof(trapped) / sizeof(trapped[0]); i++) {
        instructions[count++] = (struct sock_filter) BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K,
                                                              (uint32_t) trapped[i], 0, 1);
        instructions[count++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP);
    }
    instructions[count++] = (struct sock_filter) BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW);
    struct sock_fprog filter = {.len = (unsigned short) count, .filter = instructions};
    CHECK(prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0);
    CHECK(prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &filter) == 0);
}

static int benchmark(void)
{
    int fd = open("/dev/null", O_RDWR);
    CHECK(fd >= 0);
    char byte = 'x';
    struct timespec start, end;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
    for (unsigned i = 0; i < 20000; i++) {
        CHECK(syscall(SYS_write, fd, &byte, 1) == 1);
        CHECK(syscall(SYS_read, fd, &byte, 1) == 0);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
    CHECK(close(fd) == 0);
    double elapsed = (double) (end.tv_sec - start.tv_sec) +
                     (double) (end.tv_nsec - start.tv_nsec) / 1000000000.0;
    printf("BENCH read_write iterations=20000 syscalls=40000 elapsed_ms=%.3f\n", elapsed * 1000.0);
    return 0;
}

static double elapsed_ms(const struct timespec *start, const struct timespec *end)
{
    return (double) (end->tv_sec - start->tv_sec) * 1000.0 +
           (double) (end->tv_nsec - start->tv_nsec) / 1000000.0;
}

static int metadata_benchmark(unsigned iterations)
{
    char directory[] = "/tmp/tritonterm-metadata-bench.XXXXXX";
    CHECK(mkdtemp(directory) != NULL);
    char path[PATH_MAX];
    CHECK(strlen(directory) + sizeof("/a/b/c/d/e/file") < sizeof(path));
    strcpy(path, directory);
    for (unsigned i = 0; i < 5; i++) {
        size_t length = strlen(path);
        path[length] = '/';
        path[length + 1] = (char) ('a' + i);
        path[length + 2] = '\0';
        CHECK(mkdir(path, 0700) == 0);
    }
    strcat(path, "/file");
    int fd = open(path, O_CREAT | O_EXCL | O_WRONLY, 0600);
    CHECK(fd >= 0);
    const char payload[] = "metadata benchmark";
    CHECK(write(fd, payload, sizeof(payload)) == (ssize_t) sizeof(payload));
    CHECK(close(fd) == 0);
    CHECK(chmod(path, 0640) == 0);
    uid_t uid = getuid();
    gid_t gid = getgid();
    struct timespec start, end;
    double statx_ms = 0.0;
#ifdef SYS_statx
    struct statx extended;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
    for (unsigned i = 0; i < iterations; i++) {
        CHECK(syscall(SYS_statx, AT_FDCWD, path, 0, STATX_BASIC_STATS, &extended) == 0);
        CHECK(extended.stx_size == sizeof(payload));
        CHECK((extended.stx_mode & 07777) == 0640);
        CHECK(extended.stx_uid == uid && extended.stx_gid == gid);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
    statx_ms = elapsed_ms(&start, &end);
#endif
    struct stat info;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
    for (unsigned i = 0; i < iterations; i++) {
        CHECK(stat(path, &info) == 0);
        CHECK(info.st_size == (off_t) sizeof(payload));
        CHECK((info.st_mode & 07777) == 0640);
        CHECK(info.st_uid == uid && info.st_gid == gid);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
    double stat_ms = elapsed_ms(&start, &end);
    CHECK(unlink(path) == 0);
    *strrchr(path, '/') = '\0';
    for (unsigned i = 0; i < 5; i++) {
        CHECK(rmdir(path) == 0);
        *strrchr(path, '/') = '\0';
    }
    CHECK(strcmp(path, directory) == 0);
    CHECK(rmdir(directory) == 0);
    printf("BENCH metadata iterations=%u statx_ms=%.3f stat_ms=%.3f\n",
           iterations, statx_ms, stat_ms);
    return 0;
}

static int fstat_benchmark(unsigned iterations)
{
    char directory[] = "/tmp/tritonterm-fstat-bench.XXXXXX";
    CHECK(mkdtemp(directory) != NULL);
    char path[PATH_MAX];
    int length = snprintf(path, sizeof(path), "%s/file", directory);
    CHECK(length > 0 && (size_t) length < sizeof(path));
    int fd = open(path, O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(fd >= 0);
    const char payload[] = "fstat benchmark";
    CHECK(write(fd, payload, sizeof(payload)) == (ssize_t) sizeof(payload));
    CHECK(chmod(path, 0640) == 0);
    uid_t uid = getuid();
    gid_t gid = getgid();
    struct timespec start, end;
    struct stat info;
    CHECK(clock_gettime(CLOCK_MONOTONIC, &start) == 0);
    for (unsigned i = 0; i < iterations; i++) {
        CHECK(fstat(fd, &info) == 0);
        CHECK(info.st_size == (off_t) sizeof(payload));
        CHECK((info.st_mode & 07777) == 0640);
        CHECK(info.st_uid == uid && info.st_gid == gid);
    }
    CHECK(clock_gettime(CLOCK_MONOTONIC, &end) == 0);
    CHECK(close(fd) == 0);
    CHECK(unlink(path) == 0);
    CHECK(rmdir(directory) == 0);
    printf("BENCH fstat iterations=%u elapsed_ms=%.3f\n", iterations, elapsed_ms(&start, &end));
    return 0;
}

static int test_ptrace(int attach)
{
    int ready[2], gate[2];
    CHECK(pipe(ready) == 0);
    CHECK(pipe(gate) == 0);
    int fd = open("/dev/null", O_RDWR);
    CHECK(fd >= 0);
    pid_t child = fork();
    CHECK(child >= 0);
    if (child == 0) {
        char byte = 'x';
        if (attach) {
            CHECK(write(ready[1], &byte, 1) == 1);
            CHECK(read(gate[0], &byte, 1) == 1);
        } else {
            CHECK(ptrace(PTRACE_TRACEME, 0, NULL, NULL) == 0);
            CHECK(raise(SIGSTOP) == 0);
        }
        for (unsigned i = 0; i < 64; i++) {
            CHECK(syscall(SYS_write, fd, &byte, 1) == 1);
            CHECK(syscall(SYS_read, fd, &byte, 1) == 0);
        }
        _exit(0);
    }
    if (attach) {
        char byte;
        CHECK(read(ready[0], &byte, 1) == 1);
        CHECK(ptrace(PTRACE_ATTACH, child, NULL, NULL) == 0);
    }
    int status;
    CHECK(waitpid(child, &status, 0) == child);
    CHECK(WIFSTOPPED(status) && WSTOPSIG(status) == SIGSTOP);
    CHECK(ptrace(PTRACE_SETOPTIONS, child, NULL, (void *) PTRACE_O_TRACESYSGOOD) == 0);
    if (attach) {
        char byte = 'x';
        CHECK(write(gate[1], &byte, 1) == 1);
    }
    unsigned syscall_stops = 0;
    while (1) {
        CHECK(ptrace(PTRACE_SYSCALL, child, NULL, NULL) == 0);
        CHECK(waitpid(child, &status, 0) == child);
        if (WIFEXITED(status)) {
            CHECK(WEXITSTATUS(status) == 0);
            break;
        }
        CHECK(WIFSTOPPED(status));
        if (WSTOPSIG(status) == (SIGTRAP | 0x80))
            syscall_stops++;
        else
            CHECK(WSTOPSIG(status) == SIGSTOP || WSTOPSIG(status) == SIGTRAP);
    }
    CHECK(syscall_stops >= 256);
    CHECK(close(fd) == 0);
    CHECK(close(ready[0]) == 0 && close(ready[1]) == 0);
    CHECK(close(gate[0]) == 0 && close(gate[1]) == 0);
    printf("PASS ptrace_%s syscall_stops=%u\n", attach ? "attach" : "traceme", syscall_stops);
    return 0;
}

static void check_cache_owner(const struct stat *info, mode_t mode)
{
    CHECK(info->st_uid == getuid());
    CHECK(info->st_gid == getgid());
    CHECK((info->st_mode & 07777) == mode);
}

static void check_cache_metadata(const char *path, int parent, const char *name,
                                 int fd, mode_t mode)
{
    struct stat info;
    CHECK(stat(path, &info) == 0);
    check_cache_owner(&info, mode);
    CHECK(lstat(path, &info) == 0);
    check_cache_owner(&info, mode);
    CHECK(fstat(fd, &info) == 0);
    check_cache_owner(&info, mode);
    CHECK(fstatat(parent, name, &info, 0) == 0);
    check_cache_owner(&info, mode);
#ifdef SYS_newfstatat
    CHECK(syscall(SYS_newfstatat, AT_FDCWD, path, &info, 0) == 0);
    check_cache_owner(&info, mode);
    CHECK(syscall(SYS_newfstatat, parent, name, &info, 0) == 0);
    check_cache_owner(&info, mode);
#endif
#ifdef SYS_statx
    struct statx extended;
    CHECK(syscall(SYS_statx, AT_FDCWD, path, 0, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_uid == getuid() && extended.stx_gid == getgid());
    CHECK((extended.stx_mode & 07777) == mode);
    CHECK(syscall(SYS_statx, parent, name, 0, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_uid == getuid() && extended.stx_gid == getgid());
    CHECK((extended.stx_mode & 07777) == mode);
    CHECK(syscall(SYS_statx, fd, "", AT_EMPTY_PATH, STATX_BASIC_STATS, &extended) == 0);
    CHECK(extended.stx_uid == getuid() && extended.stx_gid == getgid());
    CHECK((extended.stx_mode & 07777) == mode);
#endif
}

static int test_bound_cache(const char *directory)
{
    CHECK(directory[0] == '/');
    int parent = open(directory, O_RDONLY | O_DIRECTORY);
    CHECK(parent >= 0);
    struct stat before, after;
    CHECK(stat(directory, &before) == 0);
    CHECK(before.st_uid == getuid() && before.st_gid == getgid());
    CHECK(fstat(parent, &after) == 0);
    CHECK(after.st_uid == getuid() && after.st_gid == getgid());
    CHECK(after.st_mode == before.st_mode);

    char cache[PATH_MAX];
    int length = snprintf(cache, sizeof(cache), "%s/yazi-%u.XXXXXX", directory, (unsigned) getuid());
    CHECK(length > 0 && (size_t) length < sizeof(cache));
    CHECK(mkdtemp(cache) != NULL);
    const char *name = strrchr(cache, '/') + 1;
    int cache_fd = open(cache, O_RDONLY | O_DIRECTORY);
    CHECK(cache_fd >= 0);
    check_cache_metadata(cache, parent, name, cache_fd, 0700);

    char payload_path[PATH_MAX];
    length = snprintf(payload_path, sizeof(payload_path), "%s/payload", cache);
    CHECK(length > 0 && (size_t) length < sizeof(payload_path));
    int payload = openat(cache_fd, "payload", O_CREAT | O_EXCL | O_RDWR, 0600);
    CHECK(payload >= 0);
    const char contents[] = "cache payload unchanged";
    CHECK(write(payload, contents, sizeof(contents)) == (ssize_t) sizeof(contents));
    check_cache_metadata(payload_path, cache_fd, "payload", payload, 0600);
    char bytes[sizeof(contents)];
    CHECK(pread(payload, bytes, sizeof(bytes), 0) == (ssize_t) sizeof(bytes));
    CHECK(memcmp(bytes, contents, sizeof(bytes)) == 0);
    CHECK(close(payload) == 0);
    CHECK(unlinkat(cache_fd, "payload", 0) == 0);
    CHECK(close(cache_fd) == 0);
    CHECK(rmdir(cache) == 0);
    CHECK(fstat(parent, &after) == 0);
    CHECK(after.st_uid == before.st_uid && after.st_gid == before.st_gid);
    CHECK(after.st_mode == before.st_mode);
    CHECK(close(parent) == 0);
    printf("PASS bound_cache_ownership uid=%u gid=%u directory_mode=0700 payload_mode=0600\n",
           (unsigned) getuid(), (unsigned) getgid());
    return 0;
}

int main(int argc, char **argv)
{
    if (argc == 2 && strcmp(argv[1], "--child") == 0)
        return 23;
    if (argc == 2 && strcmp(argv[1], "--bench") == 0)
        return benchmark();
    if ((argc == 2 || argc == 3) &&
        (strcmp(argv[1], "--metadata-bench") == 0 || strcmp(argv[1], "--fstat-bench") == 0)) {
        unsigned long iterations = 10000;
        if (argc == 3) {
            char *end;
            errno = 0;
            iterations = strtoul(argv[2], &end, 10);
            CHECK(errno == 0 && end != argv[2] && *end == '\0');
        }
        CHECK(iterations > 0 && iterations <= 100000);
        return strcmp(argv[1], "--fstat-bench") == 0
            ? fstat_benchmark((unsigned) iterations) : metadata_benchmark((unsigned) iterations);
    }
    if (argc == 2 && strcmp(argv[1], "--ptrace-attach") == 0)
        return test_ptrace(1);
    if (argc == 2 && strcmp(argv[1], "--ptrace-traceme") == 0)
        return test_ptrace(0);
    if (argc == 3 && strcmp(argv[1], "--bound-cache") == 0)
        return test_bound_cache(argv[2]);
    if (argc >= 3 && strcmp(argv[1], "--trap-launch") == 0) {
        install_traps();
        execvp(argv[2], argv + 2);
        perror("execvp trap launcher");
        return 1;
    }
    if (argc == 2 && strcmp(argv[1], "--trap-guest") == 0) {
        install_traps();
        execl("/proc/self/exe", "/proc/self/exe", "--trapped-exec", (char *) NULL);
        perror("exec trapped workload");
        return 1;
    }
    CHECK(argc == 1 || (argc == 2 && strcmp(argv[1], "--trapped-exec") == 0));
    test_files();
    test_threads();
    test_processes();
    puts("PROOT_FAST_IO_WORKLOAD_PASSED");
    return 0;
}
