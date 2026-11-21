#ifndef CNG_PROFILE_H
#define CNG_PROFILE_H

#include <stddef.h>

enum cng_profile_metric {
    CNG_P_RESOLVE, CNG_P_PIN, CNG_P_METADATA, CNG_P_ELF_HEADERS,
    CNG_P_ELF_MAP, CNG_P_REWRITE, CNG_P_LIBRARY_MAP, CNG_P_DISPATCH,
    CNG_P_FILE_MAPS, CNG_P_ANON_MAPS, CNG_P_COPY_BYTES,
    CNG_P_REWRITE_BYTES, CNG_P_REWRITE_SITES, CNG_P_METADATA_OPENS,
    CNG_P_METADATA_HITS, CNG_P_PATH_PROBES, CNG_P_LOADER_ANON,
    CNG_P_LOADER_FILE, CNG_P_STACK, CNG_P_CLOEXEC, CNG_P_SWEEP,
    CNG_P_RESET, CNG_P_REAP, CNG_P_LOADER_FILL, CNG_P_LOADER_PROTECT,
    CNG_P_LOADER_SYNC, CNG_P_LOADER_SYNC_BYTES, CNG_P_IMAGE_HIT,
    CNG_P_IMAGE_MISS, CNG_P_IMAGE_FALLBACK, CNG_P_IMAGE_OBJECTS,
    CNG_P_IMAGE_PATCH_BYTES, CNG_P_IMAGE_FETCH,
    CNG_P_STATX_ABSOLUTE, CNG_P_STATX_CWD, CNG_P_STATX_DIRFD,
    CNG_P_STATX_EMPTY, CNG_P_STATX_REUSE_ABS, CNG_P_STATX_REUSE_RESOLVED,
    CNG_P_STATX_FALLBACK, CNG_P_META_OPEN_OK, CNG_P_META_ABSENT,
    CNG_P_META_OPEN_ERROR, CNG_P_META_PARSED, CNG_P_META_COLLISION,
    CNG_P_META_ADMISSION, CNG_P_COPY_PVM, CNG_P_COPY_MEMFD, CNG_P_STATX_REUSE_ERROR,
    CNG_P_METRICS
};
struct cng_profile_span { unsigned long start; unsigned generation; };
extern int cng_profile_support;
void cng_profile_init(void);
void cng_profile_exec(const char *path, char *const *envp, int initial);
void cng_profile_commit_thread(void);
void cng_profile_entry(void);
void cng_profile_failure(long error);
void cng_profile_exit(int status);
struct cng_profile_span cng_profile_begin(unsigned metric);
void cng_profile_end(unsigned metric, struct cng_profile_span span);
void cng_profile_add(unsigned metric, unsigned long amount);
void cng_profile_syscall(long nr, int trapped);
void cng_profile_monitor(int installed);

#endif
