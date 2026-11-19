/* rule: fast-range-index
 * claim: store_with_range faster store_without_range
 * An array accessed with a 16-bit index goes through a 24-bit pointer unless dampf knows the index
 * stays inside the array; a DAMPF_RANGE on the index lets it use the array's address directly.
 * Worth it for arrays of more than 256 elements in hot code (Schach's move list). */
#include <stdint.h>
#include <dampf.h>

#define N 1000
uint8_t from_a[N], to_a[N], from_b[N], to_b[N];
uint16_t count_a, count_b;

void store_without_range(uint8_t f, uint8_t t)
{
    uint16_t n = count_a;
    from_a[n] = f;
    to_a[n] = t;
    count_a = (uint16_t)(n + 1);
}

void store_with_range(uint8_t f, uint8_t t)
{
    uint16_t n = count_b;
    DAMPF_RANGE(n, 0, N - 1);
    from_b[n] = f;
    to_b[n] = t;
    count_b = (uint16_t)(n + 1);
}

void test_main(void)
{
    uint16_t i;
    for (i = 0; i < 300; i++) {
        store_without_range((uint8_t)i, (uint8_t)(i + 1));
        store_with_range((uint8_t)i, (uint8_t)(i + 1));
    }
}
