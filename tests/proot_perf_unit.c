#define _GNU_SOURCE
#include <assert.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <time.h>

static int intercepted_clock_gettime(clockid_t clock, struct timespec *value);
static int intercepted_openat(int directory, const char *path, int flags, ...);
static ssize_t intercepted_write(int fd, const void *data, size_t length);
static int intercepted_close(int fd);
static int intercepted_renameat(int old_directory, const char *old_path, int new_directory, const char *new_path);
static int intercepted_sigaction(int signal, const struct sigaction *action, struct sigaction *previous);
static FILE *intercepted_fopen(const char *path, const char *mode);

#define clock_gettime intercepted_clock_gettime
#define openat intercepted_openat
#define write intercepted_write
#define close intercepted_close
#define renameat intercepted_renameat
#define sigaction(...) intercepted_sigaction(__VA_ARGS__)
#define fopen intercepted_fopen
#include "tracee/perf.c"
#undef clock_gettime
#undef openat
#undef write
#undef close
#undef renameat
#undef sigaction
#undef fopen

enum Failure { NONE, OPEN_TEMP, WRITE_ERROR, WRITE_ZERO, CLOSE_TEMP, RENAME_TEMP, INSTALL_SIGNAL, OPEN_STAT };
static enum Failure failure;
static unsigned opened, written, renamed;
static unsigned interruptions, partials;
static int temporary_fd = -1;
static const char *stat_fixture;
static char directory[128];
static char filename[256];

static bool clock_error;

static int intercepted_clock_gettime(clockid_t clock, struct timespec *value)
{
    assert(clock == CLOCK_MONOTONIC);
    if (clock_error) {
        errno = EIO;
        return -1;
    }
    return clock_gettime(clock, value);
}

static void test_timing(void)
{
    proot_perf_enabled = 1;
    proot_perf_timing_enabled = 0;
    assert(proot_perf_clock() == 0);
    proot_perf_timing_enabled = 1;
    proot_perf_enabled = 0;
    assert(proot_perf_clock() == 0);
    proot_perf_enabled = 1;
    errno = EDOM;
    uint64_t started = proot_perf_clock();
    assert(started > 0 && errno == EDOM);
    uint64_t previous = proot_perf_counters[PROOT_PERF_METADATA_READ_NS];
    proot_perf_elapsed(PROOT_PERF_METADATA_READ_NS, started - 1000);
    assert(proot_perf_counters[PROOT_PERF_METADATA_READ_NS] > previous);
    previous = proot_perf_counters[PROOT_PERF_METADATA_READ_NS];
    proot_perf_elapsed(PROOT_PERF_METADATA_READ_NS, UINT64_MAX);
    proot_perf_elapsed(PROOT_PERF_METADATA_READ_NS, 0);
    assert(proot_perf_counters[PROOT_PERF_METADATA_READ_NS] == previous);
    clock_error = true;
    assert(proot_perf_clock() == 0 && errno == EDOM);
    proot_perf_elapsed(PROOT_PERF_METADATA_READ_NS, started);
    assert(proot_perf_counters[PROOT_PERF_METADATA_READ_NS] == previous && errno == EDOM);
    clock_error = false;
    proot_perf_timing_enabled = 0;
    proot_perf_enabled = 0;
}

static int intercepted_openat(int parent, const char *path, int flags, ...)
{
    assert(flags & O_EXCL);
    assert(flags & O_NOFOLLOW);
    assert(flags & O_CLOEXEC);
    opened++;
    if (failure == OPEN_TEMP) {
        errno = EACCES;
        return -1;
    }
    va_list arguments;
    va_start(arguments, flags);
    mode_t mode = (mode_t) va_arg(arguments, int);
    va_end(arguments);
    assert(mode == 0600);
    int fd = openat(parent, path, flags, mode);
    if (fd >= 0)
        temporary_fd = fd;
    return fd;
}

static ssize_t intercepted_write(int fd, const void *data, size_t length)
{
    assert(fd == temporary_fd);
    written++;
    if (interruptions) {
        interruptions--;
        errno = EINTR;
        return -1;
    }
    if (failure == WRITE_ERROR) {
        errno = ENOSPC;
        return -1;
    }
    if (failure == WRITE_ZERO)
        return 0;
    if (partials) {
        partials--;
        assert(length > 7);
        length = 7;
    }
    return write(fd, data, length);
}

static int intercepted_close(int fd)
{
    bool temporary = fd == temporary_fd;
    int result = close(fd);
    if (temporary)
        temporary_fd = -1;
    if (temporary && failure == CLOSE_TEMP) {
        errno = EIO;
        return -1;
    }
    return result;
}

static int intercepted_renameat(int old_directory, const char *old_path, int new_directory, const char *new_path)
{
    renamed++;
    if (failure == RENAME_TEMP) {
        errno = EACCES;
        return -1;
    }
    return renameat(old_directory, old_path, new_directory, new_path);
}

static int intercepted_sigaction(int signal, const struct sigaction *action, struct sigaction *previous)
{
    if (action != NULL && action->sa_handler == request_signal && failure == INSTALL_SIGNAL) {
        errno = EPERM;
        return -1;
    }
    return sigaction(signal, action, previous);
}

static FILE *intercepted_fopen(const char *path, const char *mode)
{
    if (strcmp(path, "/proc/self/stat") != 0)
        return fopen(path, mode);
    if (failure == OPEN_STAT) {
        errno = EACCES;
        return NULL;
    }
    if (stat_fixture == NULL)
        return fopen(path, mode);
    FILE *stream = tmpfile();
    assert(stream != NULL);
    assert(fputs(stat_fixture, stream) >= 0);
    rewind(stream);
    return stream;
}

static void expect_previous_handler(void)
{
    struct sigaction action;
    assert(sigaction(SIGUSR2, NULL, &action) == 0);
    assert(action.sa_handler == SIG_IGN);
}

static void reset(void)
{
    failure = NONE;
    stat_fixture = NULL;
    interruptions = partials = 0;
    assert(unsetenv("PROOT_PERF_STATS_DIR") == 0);
    proot_perf_init();
    opened = written = renamed = 0;
    expect_previous_handler();
}

static void enable(void)
{
    assert(setenv("PROOT_PERF_STATS_DIR", directory, 1) == 0);
    errno = EDOM;
    proot_perf_init();
    assert(errno == EDOM && proot_perf_enabled);
}

static void read_record(char record[4096])
{
    FILE *stream = fopen(filename, "r");
    assert(stream != NULL);
    size_t bytes = fread(record, 1, 4095, stream);
    assert(bytes > 0 && !ferror(stream));
    assert(fclose(stream) == 0);
    record[bytes] = '\0';
    assert(strstr(record, "\"version\":1,") != NULL);
    assert(strstr(record, "}}\n") != NULL);
    assert(strstr(record, directory) == NULL);
    struct stat metadata;
    assert(stat(filename, &metadata) == 0 && (metadata.st_mode & 0777) == 0600);
}

static void test_disabled(void)
{
    reset();
    errno = E2BIG;
    proot_perf_init();
    assert(errno == E2BIG && !proot_perf_enabled);
    proot_perf_add(PROOT_PERF_EVENTS, 1);
    proot_perf_set(PROOT_PERF_F2FS_ACTIVE, 1);
    proot_perf_pvm_result(0, 4, 4);
    proot_perf_request_snapshot();
    proot_perf_flush_requested();
    assert(opened == 0 && !snapshot_requested);
    for (int i = 0; i < PROOT_PERF_COUNT; i++)
        assert(proot_perf_counters[i] == 0);
    const char *invalid[] = {"", "relative/path", "/no-such-tritonterm-perf-directory"};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
        assert(setenv("PROOT_PERF_STATS_DIR", invalid[i], 1) == 0);
        proot_perf_init();
        assert(!proot_perf_enabled && stats_directory == -1);
        expect_previous_handler();
    }
    char long_directory[PATH_MAX + 2];
    memset(long_directory, '/', sizeof(long_directory) - 1);
    long_directory[sizeof(long_directory) - 1] = '\0';
    assert(setenv("PROOT_PERF_STATS_DIR", long_directory, 1) == 0);
    proot_perf_init();
    assert(!proot_perf_enabled);
}

static void test_start_ticks(void)
{
    const char *invalid[] = {"", "1 missing-parenthesis S 1 2 3", "1 (x)\n", "1 (x) S"};
    for (size_t i = 0; i < sizeof(invalid) / sizeof(invalid[0]); i++) {
        reset();
        stat_fixture = invalid[i];
        assert(setenv("PROOT_PERF_STATS_DIR", directory, 1) == 0);
        proot_perf_init();
        assert(!proot_perf_enabled && stats_directory == -1);
    }
    const char *bad_ticks[] = {"not-a-number", "-1", "123x", "18446744073709551616"};
    char fixture[512];
    for (size_t i = 0; i < sizeof(bad_ticks) / sizeof(bad_ticks[0]); i++) {
        reset();
        snprintf(fixture, sizeof(fixture), "1 (name) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 %s 0\n", bad_ticks[i]);
        stat_fixture = fixture;
        assert(setenv("PROOT_PERF_STATS_DIR", directory, 1) == 0);
        proot_perf_init();
        assert(!proot_perf_enabled);
    }
    reset();
    stat_fixture = "1 (name with ) and ( parentheses) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 123456 0\n";
    enable();
    assert(process_start_ticks == 123456);
}

static void test_init_errors(void)
{
    const enum Failure failures[] = {OPEN_TEMP, WRITE_ERROR, WRITE_ZERO, CLOSE_TEMP, RENAME_TEMP, INSTALL_SIGNAL, OPEN_STAT};
    for (size_t i = 0; i < sizeof(failures) / sizeof(failures[0]); i++) {
        reset();
        failure = failures[i];
        assert(setenv("PROOT_PERF_STATS_DIR", directory, 1) == 0);
        errno = EBUSY;
        proot_perf_init();
        assert(errno == EBUSY && !proot_perf_enabled && stats_directory == -1);
        expect_previous_handler();
        char temporary[256];
        snprintf(temporary, sizeof(temporary), "%s/.proot-perf-%ld-0.tmp", directory, (long) getpid());
        assert(access(temporary, F_OK) != 0);
    }
}

static void test_snapshots(void)
{
    reset();
    enable();
    char record[4096], initial[4096];
    read_record(initial);
    assert(strstr(initial, "\"sequence\":0,") != NULL);
    char identity[128];
    snprintf(identity, sizeof(identity), "\"tracer_pid\":%ld,\"process_start_ticks\":%" PRIu64,
             (long) getpid(), process_start_ticks);
    assert(strstr(initial, identity) != NULL);
    proot_perf_add(PROOT_PERF_EVENTS, 7);
    proot_perf_set(PROOT_PERF_F2FS_ACTIVE, 1);
    proot_perf_pvm_result(0, -1, 256);
    proot_perf_pvm_result(0, 17, 256);
    proot_perf_pvm_result(0, 256, 256);
    proot_perf_pvm_result(1, 0, 8);
    proot_perf_pvm_result(1, 256, 256);
    errno = ENOTTY;
    assert(raise(SIGUSR2) == 0 && errno == ENOTTY);
    assert(snapshot_requested && sequence == 0 && renamed == 1);
    read_record(record);
    assert(strcmp(record, initial) == 0);
    interruptions = partials = 1;
    errno = ENOSYS;
    proot_perf_flush_requested();
    assert(errno == ENOSYS && !snapshot_requested && sequence == 1);
    assert(written == 4 && renamed == 2);
    read_record(record);
    assert(strstr(record, "\"sequence\":1,") != NULL);
    assert(strstr(record, "\"events\":7,") != NULL);
    assert(strstr(record, "\"pvm_read_attempts\":3,") != NULL);
    assert(strstr(record, "\"pvm_read_success\":1,") != NULL);
    assert(strstr(record, "\"pvm_read_bytes\":273,") != NULL);
    assert(strstr(record, "\"pvm_write_attempts\":2,") != NULL);
    assert(strstr(record, "\"pvm_write_success\":1,") != NULL);
    assert(strstr(record, "\"pvm_write_bytes\":256,") != NULL);
    unsigned previous_writes = written;
    proot_perf_flush_requested();
    assert(written == previous_writes);
    failure = WRITE_ERROR;
    proot_perf_request_snapshot();
    proot_perf_flush_requested();
    assert(sequence == 1 && proot_perf_enabled);
    char unchanged[4096];
    read_record(unchanged);
    assert(strcmp(record, unchanged) == 0);
    failure = NONE;
    proot_perf_request_snapshot();
    proot_perf_request_snapshot();
    proot_perf_flush_requested();
    assert(sequence == 2);
    proot_perf_request_snapshot();
    pid_t child = fork();
    assert(child >= 0);
    if (child == 0) {
        proot_perf_flush_requested();
        assert(sequence == 2 && snapshot_requested);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    proot_perf_flush_requested();
    assert(sequence == 3);
}

static void test_symlink_temp(void)
{
    reset();
    char target[256], temporary[256];
    snprintf(target, sizeof(target), "%s/valuable", directory);
    snprintf(temporary, sizeof(temporary), "%s/.proot-perf-%ld-0.tmp", directory, (long) getpid());
    FILE *stream = fopen(target, "w");
    assert(stream != NULL && fputs("untouched", stream) >= 0 && fclose(stream) == 0);
    assert(symlink(target, temporary) == 0);
    assert(setenv("PROOT_PERF_STATS_DIR", directory, 1) == 0);
    proot_perf_init();
    assert(!proot_perf_enabled);
    stream = fopen(target, "r");
    assert(stream != NULL);
    char bytes[16];
    assert(fgets(bytes, sizeof(bytes), stream) != NULL && strcmp(bytes, "untouched") == 0);
    assert(fclose(stream) == 0);
    expect_previous_handler();
    assert(unlink(temporary) == 0 && unlink(target) == 0);
}

int main(void)
{
    struct sigaction action = {0};
    action.sa_handler = SIG_IGN;
    assert(sigaction(SIGUSR2, &action, NULL) == 0);
    strcpy(directory, "/tmp/tritonterm-perf-unit-XXXXXX");
    assert(mkdtemp(directory) != NULL);
    snprintf(filename, sizeof(filename), "%s/proot-perf-%ld.json", directory, (long) getpid());
    test_timing();
    test_disabled();
    test_start_ticks();
    test_init_errors();
    test_snapshots();
    test_symlink_temp();
    reset();
    assert(unlink(filename) == 0 && rmdir(directory) == 0);
    puts("PASS perf opt-in, start identity, signal requests, atomic snapshots, errno, failures and symlink safety");
    return 0;
}
