/*
 * The player: the ship (a flying beer tap), its crate of beer, and their shots.
 *
 * B fires: the ship a bottle cap from its spout, the crate, when there is one, bottles from where
 * it is. Holding B, once the Sekt has been found, shakes the bottle of sparkling wine: released
 * after a while, the cork flies through everything in a line, and at full charge it bounces off
 * walls; held too long, the bottle foams over, and instead of the cork comes a wide shower of foam
 * that does little damage. A launches the crate when it is docked and calls it back when it is not.
 *
 * The crate is a state machine (C_*): docked at the front or the back, flying after a launch,
 * floating where it stopped, returning. It docks on the side of the ship it reaches. Its fill is
 * its weapon: a volley costs one bottle; more than 12 bottles fire a fan, more than 6 a pair, the
 * rest single bottles; an empty crate fires caps, weak but not useless.
 */
#include "ertuep.h"

int16_t ship_x, ship_y;
uint8_t ship_alive, ship_tilt, ship_speed, ship_blink;
int16_t crate_x, crate_y;
static int16_t crate_vx;
uint8_t crate_state, crate_bottles;
uint8_t has_sekt;
uint8_t charge;
static uint8_t charge_spent;               /* the bottle foamed over: B must be let go first */
static uint8_t cooldown;
static uint8_t volleys;                    /* a bottle lasts three volleys */

Obj shots[MAX_SHOTS];

#define DOCK_DX     FX(19)                 /* the crate's centre beside the ship's */
#define CHARGE_CORK 40                     /* frames held for a cork */
#define CHARGE_FULL 100                    /* ... for one that bounces */
#define CHARGE_OVER 170                    /* ... and the bottle foams over */

/* Ship speed per espresso, sixteenths of a pixel a frame. */
static const uint8_t SPEED[4] = { 20, 26, 32, 38 };

void player_start(void)
{
    ship_x = FX(48);
    ship_y = FX(120);
    ship_alive = 1;
    ship_tilt = 0;
    ship_speed = 0;
    ship_blink = 120;
    crate_state = C_NONE;
    crate_bottles = 0;
    charge = 0;
    charge_spent = 0;
    cooldown = 0;
}

/* --- shots ------------------------------------------------------------------------------------ */
static Obj *shot_new(uint8_t kind, int16_t x, int16_t y, int16_t vx, int16_t vy)
{
    uint8_t i;

    for (i = 0; i < MAX_SHOTS; i++) {
        Obj *s = &shots[i];
        if (s->kind == S_NONE) {
            s->kind = kind;
            s->x = x;
            s->y = y;
            s->vx = vx;
            s->vy = vy;
            s->state = 0;
            s->timer = 0;
            s->hp = 0;
            s->anim = 0;
            s->flags = 0;
            return s;
        }
    }
    return 0;
}

/* Caps from the ship (or an empty crate) go at most three at a time each. */
static uint8_t caps_flying(void)
{
    uint8_t i, n = 0;

    for (i = 0; i < MAX_SHOTS; i++) {
        if (shots[i].kind == S_CAP) {
            n++;
        }
    }
    return n;
}

static void crate_volley(void)
{
    int16_t dir = crate_state == C_BACK ? -1 : 1;
    int16_t vx = (int16_t)(dir * 64);
    int16_t x = (int16_t)(crate_x + dir * FX(8));

    if (crate_bottles == 0) {
        shot_new(S_CAP, x, crate_y, (int16_t)(dir * 80), 0);
        return;
    }
    if (++volleys == 3) {
        volleys = 0;
        crate_bottles--;
        hud_crate();
    }
    if (crate_bottles >= 12) {
        shot_new(S_BOTTLE, x, crate_y, vx, -18);
        shot_new(S_BOTTLE, x, crate_y, vx, 0);
        shot_new(S_BOTTLE, x, crate_y, vx, 18);
    } else if (crate_bottles >= 6) {
        shot_new(S_BOTTLE, x, (int16_t)(crate_y - FX(4)), vx, 0);
        shot_new(S_BOTTLE, x, (int16_t)(crate_y + FX(4)), vx, 0);
    } else {
        shot_new(S_BOTTLE, x, crate_y, vx, 0);
    }
}

static void fire(void)
{
    if (caps_flying() < 4) {
        shot_new(S_CAP, (int16_t)(ship_x + FX(16)), (int16_t)(ship_y + FX(3)), 80, 0);
    }
    if (crate_state != C_NONE && crate_state != C_RETURN) {
        crate_volley();
    }
    cooldown = 7;
}

static void fire_cork(void)
{
    Obj *s = shot_new(S_CORK, (int16_t)(ship_x + FX(16)), ship_y, 96, 0);

    if (s != 0) {
        s->hp = charge >= CHARGE_FULL ? 12 : 6;     /* its damage */
        s->state = charge >= CHARGE_FULL ? 3 : 0;   /* bounces left */
    }
    part_spawn(P_FOAM, (int16_t)(ship_x + FX(14)), (int16_t)(ship_y - FX(6)), 16, -24);
    part_spawn(P_FOAM, (int16_t)(ship_x + FX(12)), (int16_t)(ship_y - FX(8)), -8, -32);
}

/* The bottle foams over: a fan of foam, slow and weak, then nothing until B is let go. */
static void foam_over(void)
{
    uint8_t i;

    for (i = 0; i < 7; i++) {
        Obj *s = shot_new(S_FOAM, (int16_t)(ship_x + FX(12)), (int16_t)(ship_y - FX(4)),
                          (int16_t)(40 + (random16() & 15)), (int16_t)(i * 10 - 34));
        if (s != 0) {
            s->timer = (uint8_t)(36 + (i & 3) * 4);
        }
    }
    hud_message("UEBERGESCHAEUMT!", 90);
    charge = 0;
    charge_spent = 1;
    hud_sekt();
}

void shots_update(void)
{
    uint8_t i;

    for (i = 0; i < MAX_SHOTS; i++) {
        Obj *s = &shots[i];
        int16_t px, py;
        if (s->kind == S_NONE) {
            continue;
        }
        s->x = (int16_t)(s->x + s->vx);
        s->y = (int16_t)(s->y + s->vy);
        s->anim++;
        px = PX(s->x);
        py = PX(s->y);
        if (px < -16 || px > SCREEN_W + 16 || py < PLAY_TOP - 8 || py > SCREEN_H + 8) {
            s->kind = S_NONE;
            continue;
        }
        switch (s->kind) {
        case S_CORK:
            if ((s->anim & 1) == 0) {               /* its trail */
                part_spawn(P_FOAM, (int16_t)(s->x - FX(8)), s->y, -8, (int16_t)((random16() & 15) - 8));
            }
            if (solid_at((int16_t)(px + (s->vx > 0 ? 6 : -6)), py)) {
                if (s->state > 0) {                 /* full charge: off the wall */
                    s->state--;
                    s->vx = (int16_t)-s->vx;
                    s->vy = s->vy == 0 ? -40 : (int16_t)-s->vy;
                } else {
                    part_spawn(P_PUFF, s->x, s->y, 0, 0);
                    s->kind = S_NONE;
                }
            } else if (s->vy != 0 && solid_at(px, (int16_t)(py + (s->vy > 0 ? 5 : -5)))) {
                s->vy = (int16_t)-s->vy;
            }
            break;
        case S_FOAM:
            s->vy = (int16_t)(s->vy + 2);           /* it sinks */
            if (--s->timer == 0 || solid_at(px, py)) {
                s->kind = S_NONE;
            }
            break;
        default:                                    /* caps and bottles break on walls */
            if (solid_at(px, py)) {
                if (s->kind == S_BOTTLE) {
                    part_spawn(P_SHARD, s->x, s->y, -16, -20);
                    part_spawn(P_SHARD, s->x, s->y, -24, 8);
                }
                s->kind = S_NONE;
            }
            break;
        }
    }
}

/* --- the crate ----------------------------------------------------------------------------------- */
void crate_grow(uint8_t bottles)
{
    crate_bottles = (uint8_t)(crate_bottles + bottles > 24 ? 24 : crate_bottles + bottles);
    if (crate_state == C_NONE) {                    /* it flies in from the left edge */
        crate_state = C_RETURN;
        crate_x = FX(-12);
        crate_y = ship_y;
    }
    hud_crate();
}

static int16_t toward(int16_t now, int16_t want, int16_t step)
{
    if (want > now + step) {
        return (int16_t)(now + step);
    }
    if (want < now - step) {
        return (int16_t)(now - step);
    }
    return want;
}

static void crate_update(uint16_t pressed)
{
    switch (crate_state) {
    case C_FRONT:
    case C_BACK:
        crate_x = (int16_t)(ship_x + (crate_state == C_FRONT ? DOCK_DX : -DOCK_DX));
        crate_y = ship_y;
        if (pressed & SDEVKIT_KEY_A) {
            crate_vx = crate_state == C_FRONT ? 88 : -88;
            crate_state = C_FLY;
        }
        break;
    case C_FLY:                                     /* out, slowing down */
        crate_x = (int16_t)(crate_x + crate_vx);
        crate_vx = crate_vx > 0 ? (int16_t)(crate_vx - 3) : (int16_t)(crate_vx + 3);
        if ((crate_vx < 4 && crate_vx > -4) || PX(crate_x) > SCREEN_W - 24 || PX(crate_x) < 12) {
            crate_state = C_FLOAT;
        }
        if (pressed & SDEVKIT_KEY_A) {
            crate_state = C_RETURN;
        }
        break;
    case C_FLOAT:                                   /* stays, and follows the ship's height a little */
        crate_y = toward(crate_y, ship_y, 6);
        if (pressed & SDEVKIT_KEY_A) {
            crate_state = C_RETURN;
        }
        break;
    case C_RETURN:
        crate_x = toward(crate_x, ship_x, 72);
        crate_y = toward(crate_y, ship_y, 72);
        {
            int16_t dx = (int16_t)(crate_x - ship_x);
            int16_t dy = (int16_t)(crate_y - ship_y);
            if (dx < FX(24) && dx > -FX(24) && dy < FX(10) && dy > -FX(10)) {
                crate_state = dx >= 0 ? C_FRONT : C_BACK;
            }
        }
        break;
    default:
        break;
    }
}

/* --- the ship ------------------------------------------------------------------------------------ */
void ship_hit(void)
{
    uint8_t i;

    if (!ship_alive || ship_blink > 0) {
        return;
    }
    ship_alive = 0;
    /* one random16() a statement: C leaves the order of a call's arguments open, and gcc and
     * dampf may choose differently */
    for (i = 0; i < 6; i++) {
        int16_t vx = (int16_t)((random16() & 31) - 16);
        int16_t vy = (int16_t)((random16() & 31) - 20);
        part_spawn(P_PUFF, (int16_t)(ship_x + (int16_t)(i * 48) - FX(12)), (int16_t)(ship_y + (int16_t)((i & 1) * 64) - FX(2)), vx, vy);
    }
    for (i = 0; i < 6; i++) {
        int16_t vx = (int16_t)((random16() & 63) - 32);
        int16_t vy = (int16_t)(-(int16_t)(random16() & 31) - 8);
        part_spawn(P_FOAM, ship_x, ship_y, vx, vy);
    }
    if (crate_state != C_NONE) {
        part_spawn(P_PUFF, crate_x, crate_y, 0, -8);
        crate_state = C_NONE;
        crate_bottles = 0;
    }
    charge = 0;
    lose_life();
}

static uint8_t ship_in_wall(void)
{
    int16_t px = PX(ship_x), py = PX(ship_y);

    return (uint8_t)(solid_at((int16_t)(px + 10), py) || solid_at((int16_t)(px - 8), py) || solid_at(px, (int16_t)(py - 3))
                     || solid_at(px, (int16_t)(py + 4)));
}

void player_update(uint16_t pad, uint16_t pressed, uint16_t released)
{
    int16_t v;

    if (!ship_alive) {
        return;
    }
    if (ship_blink > 0) {
        ship_blink--;
    }
    v = SPEED[ship_speed];
    ship_tilt = 0;
    if (pad & SDEVKIT_KEY_UP) {
        ship_y = (int16_t)(ship_y - v);
        ship_tilt = 1;
    } else if (pad & SDEVKIT_KEY_DOWN) {
        ship_y = (int16_t)(ship_y + v);
        ship_tilt = 2;
    }
    if (pad & SDEVKIT_KEY_LEFT) {
        ship_x = (int16_t)(ship_x - v);
    } else if (pad & SDEVKIT_KEY_RIGHT) {
        ship_x = (int16_t)(ship_x + v);
    }
    if (ship_x < FX(16)) {
        ship_x = FX(16);
    } else if (ship_x > FX(232)) {
        ship_x = FX(232);
    }
    if (ship_y < FX(PLAY_TOP + 6)) {
        ship_y = FX(PLAY_TOP + 6);
    } else if (ship_y > FX(SCREEN_H - 8)) {
        ship_y = FX(SCREEN_H - 8);
    }
    if ((frame_count & 7) == 0) {                   /* foam from the back */
        part_spawn(P_EXHAUST, (int16_t)(ship_x - FX(16)), (int16_t)(ship_y + FX(1)), -24, 0);
    }

    /* B: caps and bottles; held with the Sekt, the charge */
    if (cooldown > 0) {
        cooldown--;
    }
    if (pressed & SDEVKIT_KEY_B) {
        if (cooldown == 0) {
            fire();
        }
    } else if ((pad & SDEVKIT_KEY_B) && has_sekt && !charge_spent) {
        charge++;
        if (charge == CHARGE_CORK || charge == CHARGE_FULL || charge == 1) {
            hud_sekt();
        }
        if (charge >= CHARGE_OVER) {
            foam_over();
        }
    }
    if (released & SDEVKIT_KEY_B) {
        if (charge >= CHARGE_CORK) {
            fire_cork();
        }
        if (charge > 0) {
            charge = 0;
            hud_sekt();
        }
        charge_spent = 0;
    }
    crate_update(pressed);
    if (ship_in_wall()) {
        ship_blink = 0;                             /* a wall is a wall, blinking or not */
        ship_hit();
    }
}

/* --- the demo's pilot ---------------------------------------------------------------------------- */
/* The title's demo flies by itself: a joypad made up each frame. It keeps the ship at a quarter of
 * the screen, looks two and three cells ahead for the free row nearest to the ship's and steers
 * there, fires all the time and shakes the Sekt now and then. It cannot dodge bullets; in the demo
 * they do not hit it (game.c), walls do. */
uint16_t autopilot(void)
{
    int16_t px = PX(ship_x), py = PX(ship_y);
    int16_t target = py;                            /* where it wants to be */
    int16_t best;
    int16_t d;
    uint16_t pad = 0;
    int16_t aim = boss_aim_y();

    if (aim > 0) {                                  /* a boss: at its weak point */
        target = (int16_t)(aim - 3);
    }
    best = target;
    for (d = 0; d < 96; d += 8) {                   /* the free height nearest to it, up or down */
        int16_t up = (int16_t)(target - d), down = (int16_t)(target + d);
        if (up > PLAY_TOP + 8 && !solid_at((int16_t)(px + 32), up) && !solid_at((int16_t)(px + 48), up)
            && !solid_at((int16_t)(px + 12), up)) {
            best = up;
            break;
        }
        if (down < SCREEN_H - 12 && !solid_at((int16_t)(px + 32), down) && !solid_at((int16_t)(px + 48), down)
            && !solid_at((int16_t)(px + 12), down)) {
            best = down;
            break;
        }
    }
    if (best < py - 2) {
        pad |= SDEVKIT_KEY_UP;
    } else if (best > py + 2) {
        pad |= SDEVKIT_KEY_DOWN;
    }
    if (px < 60) {
        pad |= SDEVKIT_KEY_RIGHT;
    } else if (px > 72) {
        pad |= SDEVKIT_KEY_LEFT;
    }
    if ((frame_count & 255) < 140) {                /* the Sekt: held, then let go */
        if ((frame_count & 255) > 20) {
            pad |= SDEVKIT_KEY_B;
        }
    } else if ((frame_count & 7) == 0) {
        pad |= SDEVKIT_KEY_B;
    }
    return pad;
}

