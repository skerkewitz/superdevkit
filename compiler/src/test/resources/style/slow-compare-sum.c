/* rule: slow-compare-sum
 * claim: compare_sum slower compare_sum_narrowed
 * A comparison with a sum of an int16_t and a constant is a 32-bit comparison: C adds in int, which
 * has 32 bits (as with gcc), and the sum may not fit 16 bits. Where it does, a cast back to int16_t
 * says so and makes it a 16-bit one. (Er-Tüp's enemies steer toward the ship this way.) */
#include <stdint.h>

int16_t ship_y = 1200, enemy_y = 900, steer;

void compare_sum(void)
{
    if (ship_y > enemy_y + 32) {
        steer = 4;
    } else if (ship_y < enemy_y - 32) {
        steer = -4;
    }
}

void compare_sum_narrowed(void)
{
    if (ship_y > (int16_t)(enemy_y + 32)) {
        steer = 4;
    } else if (ship_y < (int16_t)(enemy_y - 32)) {
        steer = -4;
    }
}

void test_main(void)
{
    compare_sum();
    compare_sum_narrowed();
}
