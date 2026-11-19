/* SPDX-License-Identifier: Apache-2.0 */
#ifndef CNG_IMAGE_CACHE_H
#define CNG_IMAGE_CACHE_H
#include "cng/loader.h"
#include "cng/rewrite.h"
#include "cng/broker.h"
#define CNG_IMAGE_LIMIT (64UL * 1024 * 1024)
#define CNG_IMAGE_BUDGET (128UL * 1024 * 1024)
#define CNG_IMAGE_SLOTS 8
#define CNG_IMAGE_SITES 8192
struct cng_image_meta {
    unsigned long magic, span, lo, nsites;
    struct cng_code_ranges code;
    Elf64_Ehdr header;
    Elf64_Phdr ph[CNG_MAX_PHDR];
    unsigned long sites[CNG_IMAGE_SITES];
};
extern int cng_g_image_cache;
void cng_image_cache_exec(char *const *envp);
int cng_image_cache_enabled(void);
int cng_image_stamp(int fd, unsigned char stamp[128]);
int cng_image_same(const unsigned char a[128], const unsigned char b[128]);
/* On a successful miss (response->ret==1), takes ownership of source.
 * On hit/error the caller still owns that received descriptor. */
int cng_image_cache_serve(int source, struct cng_bresp *response);
void cng_image_cache_failure(long stage, long error);
void cng_image_cache_diagnostic(int *blocked, long *stage, long *error);
int cng_image_cache_get(int source, const Elf64_Ehdr *header, const Elf64_Phdr *ph,
                        unsigned long span, unsigned long lo,
                        struct cng_image_meta *meta);
#endif
