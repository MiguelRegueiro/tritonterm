/* SPDX-License-Identifier: Apache-2.0 */
#ifndef CNG_LOADER_PROBE_H
#define CNG_LOADER_PROBE_H
/* Raw host mappings, no execmap fallback. Each strategy runs independently.
 * 9 success; 1 memfd, 2 size, 3 fill, 4 seals, 5 file map, 6 file RX,
 * 7 file/private execution, 8 source integrity, 10 seal check;
 * 11 pipe, 12 fork, 13 wait, 14 timeout, 15 signal, 16 incomplete result;
 * 17 anonymous replacement, 18 anonymous RX, 19 shared entry execution,
 * 20 shared-to-anonymous execution. No cached image or guest loader change. */
enum cng_loader_probe_strategy {
    CNG_LP_PRIVATE_PATCH, CNG_LP_DIRECT_RX, CNG_LP_READ_RX, CNG_LP_HYBRID,
    CNG_LP_STRATEGIES
};
struct cng_loader_probe_result { long stage, error, signal; };
struct cng_loader_probe_result cng_loader_probe(int strategy);
#endif
