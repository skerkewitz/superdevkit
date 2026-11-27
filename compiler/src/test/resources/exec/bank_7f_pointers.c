/* Pointers into objects in bank $7F (ADR-6FVM9QWS): grid (32,000 bytes) and cells (30,002) do
   not fit beside rest (28,000) into bank $7E and go to $7F, so every pointer into them carries
   bank $7F, while pointers into rest and near carry $7E. They are made by &, stepped and
   compared, passed to functions and returned, kept in a global, in a struct in $7F and in an
   initialised static (its bank written by the start-up's copy), read from a constant table in
   ROM, and mixed with pointers into $7E through one variable. */
#include <stdint.h>

struct cell {
    uint16_t value;
    uint8_t tag;
    struct cell *link;
};

uint16_t walked;
uint16_t found_at;
uint16_t through_param;
uint16_t returned;
uint16_t from_table;
uint16_t from_static;
uint16_t mixed;
uint16_t linked;
uint16_t compared;
uint16_t copied;
uint16_t distance;

uint16_t *kept;

static void fill(uint16_t *to, uint16_t n, uint16_t start)
{
    uint16_t i;
    for (i = 0; i < n; i++) {
        to[i] = (uint16_t)(start + i * 5u);
    }
}

static uint16_t sum(const uint16_t *from, uint16_t n)
{
    uint16_t s = 0;
    while (n != 0) {
        s = (uint16_t)(s + *from);
        from++;
        n--;
    }
    return s;
}

static uint16_t *find(uint16_t *in, uint16_t n, uint16_t value)
{
    uint16_t i;
    for (i = 0; i < n; i++) {
        if (in[i] == value) {
            return &in[i];
        }
    }
    return 0;
}

static void bump(struct cell *c)
{
    c->value = (uint16_t)(c->value + 1000u);
    c->tag++;
}

void test_main(void)
{
    static uint16_t grid[16000];
    static uint8_t rest[28000];
    static struct cell cells[4286];
    static uint16_t near[4] = {11, 12, 13, 14};
    static uint16_t *start = &grid[100];
    static uint16_t *const table[3] = {&grid[0], &grid[15999], &grid[7]};
    uint16_t *p;
    uint16_t *q;
    uint16_t *hit;
    uint16_t i;

    fill(grid, 16000, 3);
    rest[27999] = 9;
    rest[0] = 4;

    /* stepping a pointer through bank $7F */
    walked = 0;
    for (p = &grid[200]; p < &grid[260]; p++) {
        walked = (uint16_t)(walked + *p);
    }

    /* passed, returned, compared with null */
    through_param = sum(&grid[15990], 10);
    hit = find(grid, 16000, (uint16_t)(3 + 1234u * 5u));
    found_at = hit == 0 ? 0xFFFF : (uint16_t)(hit - grid);
    returned = hit != 0 ? *hit : 0;

    /* a constant table in ROM and an initialised static, both pointing into $7F */
    from_table = (uint16_t)(*table[0] + *table[1] + *table[2]);
    from_static = *start;
    *start = 5;
    from_static = (uint16_t)(from_static + grid[100]);

    /* a pointer kept in a global, written through */
    kept = &grid[15000];
    *kept = 77;
    kept[1] = (uint16_t)(kept[0] + 1u);

    /* one variable, pointers into either bank */
    mixed = 0;
    for (i = 0; i < 6; i++) {
        q = (i & 1) != 0 ? &grid[i] : i == 4 ? (uint16_t *)0 : &near[i];
        if (q != 0) {
            mixed = (uint16_t)(mixed + *q);
            *q = (uint16_t)(*q + 1u);
        } else {
            mixed = (uint16_t)(mixed + rest[0] + rest[27999]);
        }
    }
    mixed = (uint16_t)(mixed + near[0] + near[2] + grid[1] + grid[3] + grid[5]);

    /* pointers stored in structs, a struct changed through a pointer and copied */
    for (i = 0; i < 8; i++) {
        cells[i].value = grid[i];
        cells[i].tag = (uint8_t)i;
        cells[i].link = i == 7 ? (struct cell *)0 : &cells[i + 1];
    }
    bump(&cells[3]);
    linked = 0;
    {
        struct cell *c;
        for (c = &cells[0]; c != 0; c = c->link) {
            linked = (uint16_t)(linked + c->value + c->tag);
        }
    }
    cells[0] = cells[3];
    copied = (uint16_t)(cells[0].value + cells[0].tag);

    /* comparisons and differences of pointers into $7F */
    p = &grid[5000];
    q = &grid[12000];
    compared = (uint16_t)((p < q) + (q > p) * 2u + (p == &grid[5000]) * 4u + (kept == &grid[15000]) * 8u);
    distance = (uint16_t)(q - p);
}
