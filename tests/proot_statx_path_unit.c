#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#include "tracee/statx.h"
#include "tracee/mem.h"
#include "tracee/reg.h"

static int captured_notify(Tracee *tracee, ExtensionEvent event, intptr_t data1, intptr_t data2);
#define notify_extensions captured_notify
#include "tracee/statx.c"
#undef notify_extensions

static word_t registers[NB_REG_VERSION][USERARG_1 + 1];
static char directory[] = "/tmp/tritonterm-statx-path-unit.XXXXXX";
static char fixture[PATH_MAX];
static char link_fixture[PATH_MAX];
static const char original_path[] = "/guest/original";
static const char current_path[] = "/guest/current";
static const char *translated_path;
static word_t observed_string;
static unsigned translations;
static unsigned fd_lookups;
static unsigned reads;
static unsigned writes;
static unsigned notifications;
static int observed_dirfd;
static bool observed_deref;
static int string_error;
static int translation_error;
static int fd_error;
static int read_error;
static int write_error;
static int extension_error;
static bool override_owner;
static struct statx kernel_result;
static struct statx output;
static struct statx_syscall_state observed;

word_t peek_reg(const Tracee *tracee, RegVersion version, Reg reg)
{
    (void) tracee;
    assert(version < NB_REG_VERSION && reg <= USERARG_1);
    return registers[version][reg];
}

int read_string(const Tracee *tracee, char *destination, word_t source, word_t maximum)
{
    (void) tracee;
    observed_string = source;
    if (string_error)
        return string_error;
    if (!source)
        return 0;
    size_t length = strlen((const char *) source) + 1;
    if (length >= maximum)
        return (int) maximum;
    memcpy(destination, (const void *) source, length);
    return (int) length;
}

int translate_path(Tracee *tracee, char destination[PATH_MAX], int dirfd,
                   const char *guest_path, bool deref_final)
{
    (void) tracee;
    translations++;
    observed_dirfd = dirfd;
    observed_deref = deref_final;
    assert(strcmp(guest_path, original_path) == 0 || strcmp(guest_path, current_path) == 0);
    if (translation_error)
        return translation_error;
    assert(strlen(translated_path) < PATH_MAX);
    strcpy(destination, translated_path);
    return 0;
}

int readlink_proc_pid_fd(pid_t pid, int fd, char path[PATH_MAX])
{
    assert(pid == getpid());
    fd_lookups++;
    observed_dirfd = fd;
    if (fd_error)
        return fd_error;
    strcpy(path, fixture);
    return 0;
}

int read_data(const Tracee *tracee, void *destination, word_t source, word_t size)
{
    (void) tracee;
    reads++;
    assert(source == (word_t) &kernel_result && size == sizeof(kernel_result));
    if (read_error)
        return read_error;
    memcpy(destination, &kernel_result, size);
    return 0;
}

int write_data(Tracee *tracee, word_t destination, const void *source, word_t size)
{
    (void) tracee;
    writes++;
    assert(destination == (word_t) &output && size == sizeof(output));
    if (write_error)
        return write_error;
    memcpy(&output, source, size);
    return 0;
}

static int captured_notify(Tracee *tracee, ExtensionEvent event, intptr_t data1, intptr_t data2)
{
    (void) tracee;
    assert(event == STATX_SYSCALL && data2 == 0);
    notifications++;
    struct statx_syscall_state *state = (struct statx_syscall_state *) data1;
    observed = *state;
    if (extension_error)
        return extension_error;
    if (override_owner) {
        state->statx_buf.stx_uid = 12345;
        state->statx_buf.stx_gid = 23456;
        state->updated_stats = true;
    }
    return 0;
}

static Tracee reset_case(void)
{
    memset(registers, 0, sizeof(registers));
    memset(&kernel_result, 0, sizeof(kernel_result));
    memset(&output, 0, sizeof(output));
    memset(&observed, 0, sizeof(observed));
    translations = fd_lookups = reads = writes = notifications = 0;
    string_error = translation_error = fd_error = read_error = write_error = extension_error = 0;
    override_owner = false;
    observed_string = 0;
    observed_dirfd = -1;
    observed_deref = false;
    translated_path = fixture;
    registers[ORIGINAL][SYSARG_1] = 21;
    registers[CURRENT][SYSARG_1] = 22;
    registers[ORIGINAL][SYSARG_2] = (word_t) original_path;
    registers[MODIFIED][SYSARG_2] = (word_t) fixture;
    registers[CURRENT][SYSARG_2] = (word_t) current_path;
    registers[ORIGINAL][SYSARG_4] = registers[CURRENT][SYSARG_4] = STATX_BASIC_STATS;
    registers[ORIGINAL][SYSARG_5] = (word_t) &kernel_result;
    registers[CURRENT][SYSARG_5] = (word_t) &output;
    kernel_result.stx_mask = STATX_BASIC_STATS;
    kernel_result.stx_mode = S_IFREG | 0640;
    kernel_result.stx_size = 987654;
    kernel_result.stx_uid = 456;
    kernel_result.stx_gid = 789;
    Tracee tracee = {.pid = getpid()};
    return tracee;
}

static void test_native_success(void)
{
    Tracee tracee = reset_case();
    override_owner = true;
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(observed_string == (word_t) fixture);
    assert(translations == 0 && fd_lookups == 0 && reads == 1 && writes == 1);
    assert(strcmp(observed.host_path, fixture) == 0);
    assert(observed.statx_buf.stx_size == kernel_result.stx_size);
    assert(output.stx_size == kernel_result.stx_size && output.stx_mode == kernel_result.stx_mode);
    assert(output.stx_uid == 12345 && output.stx_gid == 23456);

    tracee = reset_case();
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(reads == 1 && writes == 0 && notifications == 1 && translations == 0);
}

static void test_fallback_paths(void)
{
    Tracee tracee = reset_case();
    registers[CURRENT][SYSARG_RESULT] = (word_t) -ENOSYS;
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(observed_string == (word_t) original_path);
    assert(translations == 1 && observed_dirfd == 21 && observed_deref);
    assert(reads == 0 && writes == 1 && notifications == 1);
    assert(output.stx_size == 3 && (output.stx_mode & 0777) == 0640);

    tracee = reset_case();
    assert(handle_statx_syscall(&tracee, true) == 0);
    assert(observed_string == (word_t) current_path);
    assert(translations == 1 && observed_dirfd == 22 && observed_deref);
    assert(reads == 0 && writes == 1 && output.stx_size == 3);

    tracee = reset_case();
    tracee.restore_sysarg1_after_sigsys = true;
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(observed_string == (word_t) original_path);
    assert(translations == 1 && observed_dirfd == 21 && observed_deref);
    assert(reads == 1 && observed.statx_buf.stx_size == kernel_result.stx_size);

    tracee = reset_case();
    registers[CURRENT][SYSARG_RESULT] = (word_t) -ENOSYS;
    registers[ORIGINAL][SYSARG_3] = AT_SYMLINK_NOFOLLOW;
    translated_path = link_fixture;
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(translations == 1 && !observed_deref);
    assert((output.stx_mode & S_IFMT) == S_IFLNK);

    const int fallback_errors[] = {-EACCES, -EPERM};
    for (unsigned i = 0; i < sizeof(fallback_errors) / sizeof(fallback_errors[0]); i++) {
        tracee = reset_case();
        registers[CURRENT][SYSARG_RESULT] = (word_t) fallback_errors[i];
        assert(handle_statx_syscall(&tracee, false) == 0);
        assert(translations == 1 && writes == 1 && output.stx_size == 3);
    }
}

static void test_native_missing_paths(void)
{
    const int errors[] = {-ENOENT, -ENOTDIR};
    for (unsigned i = 0; i < sizeof(errors) / sizeof(errors[0]); i++) {
        Tracee tracee = reset_case();
        registers[CURRENT][SYSARG_RESULT] = (word_t) errors[i];
        string_error = -EFAULT;
        translation_error = -EIO;
        assert(handle_statx_syscall(&tracee, false) == errors[i]);
        assert(observed_string == 0 && translations == 0 && fd_lookups == 0);
        assert(reads == 0 && writes == 0 && notifications == 0);

        tracee = reset_case();
        registers[CURRENT][SYSARG_RESULT] = (word_t) errors[i];
        assert(handle_statx_syscall(&tracee, true) == 0);
        assert(translations == 1 && writes == 1 && output.stx_size == 3);

        tracee = reset_case();
        registers[CURRENT][SYSARG_RESULT] = (word_t) errors[i];
        tracee.restore_sysarg1_after_sigsys = true;
        assert(handle_statx_syscall(&tracee, false) == 0);
        assert(translations == 1 && writes == 1 && output.stx_size == 3);
    }
}

static void test_empty_path(void)
{
    Tracee tracee = reset_case();
    registers[MODIFIED][SYSARG_2] = (word_t) "";
    registers[ORIGINAL][SYSARG_3] = AT_EMPTY_PATH;
    assert(handle_statx_syscall(&tracee, false) == 0);
    assert(translations == 0 && fd_lookups == 1 && observed_dirfd == 21);
    assert(strcmp(observed.host_path, fixture) == 0 && reads == 1);

    tracee = reset_case();
    int fd = open(fixture, O_RDONLY);
    assert(fd >= 0);
    registers[CURRENT][SYSARG_1] = (word_t) fd;
    registers[CURRENT][SYSARG_2] = (word_t) "";
    registers[CURRENT][SYSARG_3] = AT_EMPTY_PATH;
    assert(handle_statx_syscall(&tracee, true) == 0);
    assert(translations == 0 && fd_lookups == 1 && observed_dirfd == fd);
    assert(writes == 1 && output.stx_size == 3);
    assert(close(fd) == 0);

    tracee = reset_case();
    registers[MODIFIED][SYSARG_2] = (word_t) "";
    assert(handle_statx_syscall(&tracee, false) == -ENOENT);
    assert(fd_lookups == 0 && notifications == 0);

    tracee = reset_case();
    registers[MODIFIED][SYSARG_2] = (word_t) "";
    registers[ORIGINAL][SYSARG_3] = AT_EMPTY_PATH;
    fd_error = -EBADF;
    assert(handle_statx_syscall(&tracee, false) == -EBADF);
    assert(fd_lookups == 1 && notifications == 0);
}

static void test_errors(void)
{
    Tracee tracee = reset_case();
    registers[CURRENT][SYSARG_RESULT] = (word_t) -ENOSYS;
    translation_error = -ENOENT;
    assert(handle_statx_syscall(&tracee, false) == -ENOENT);
    assert(translations == 1 && notifications == 0);

    tracee = reset_case();
    registers[CURRENT][SYSARG_RESULT] = (word_t) -ENOSYS;
    char missing_path[PATH_MAX];
    assert(snprintf(missing_path, sizeof(missing_path), "%s/missing", directory) > 0);
    translated_path = missing_path;
    assert(handle_statx_syscall(&tracee, false) == -ENOENT);
    assert(translations == 1 && notifications == 0);

    tracee = reset_case();
    string_error = -EFAULT;
    assert(handle_statx_syscall(&tracee, false) == -EFAULT);
    assert(translations == 0 && notifications == 0);

    tracee = reset_case();
    registers[MODIFIED][SYSARG_2] = 0;
    assert(handle_statx_syscall(&tracee, false) == -EFAULT);

    tracee = reset_case();
    char long_path[PATH_MAX + 1];
    memset(long_path, 'a', PATH_MAX);
    long_path[PATH_MAX] = '\0';
    registers[MODIFIED][SYSARG_2] = (word_t) long_path;
    assert(handle_statx_syscall(&tracee, false) == -ENAMETOOLONG);
    assert(translations == 0 && notifications == 0);

    tracee = reset_case();
    read_error = -EFAULT;
    assert(handle_statx_syscall(&tracee, false) == -EFAULT);
    assert(reads == 1 && notifications == 0);

    tracee = reset_case();
    override_owner = true;
    write_error = -EFAULT;
    assert(handle_statx_syscall(&tracee, false) == -EFAULT);
    assert(writes == 1 && notifications == 1);

    tracee = reset_case();
    extension_error = -EACCES;
    assert(handle_statx_syscall(&tracee, false) == -EACCES);
    assert(notifications == 1 && writes == 0);
}

int main(void)
{
    assert(mkdtemp(directory) != NULL);
    assert(snprintf(fixture, sizeof(fixture), "%s/file", directory) > 0);
    assert(snprintf(link_fixture, sizeof(link_fixture), "%s/link", directory) > 0);
    int fd = open(fixture, O_CREAT | O_EXCL | O_WRONLY, 0640);
    assert(fd >= 0 && write(fd, "abc", 3) == 3 && close(fd) == 0);
    assert(chmod(fixture, 0640) == 0 && symlink("file", link_fixture) == 0);
    test_native_success();
    test_fallback_paths();
    test_native_missing_paths();
    test_empty_path();
    test_errors();
    assert(unlink(link_fixture) == 0 && unlink(fixture) == 0 && rmdir(directory) == 0);
    puts("PASS statx native success, permission fallback, SIGSYS, synthetic exit, fd paths and errors");
    return 0;
}
