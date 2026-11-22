/*
 * Music on Rachael (ADR-6F3CK03Z): the track the game asked for, which the frontend plays from
 * its file (lib/desktop, raylib's music stream). Rachael only keeps the request, so she stays free
 * of raylib (the web runs the same frontend, lib/desktop with lib/web). Power on: no music.
 */
#include "rachael.h"

sdevkit__music_state sdevkit__music = { -1, 0 };

void sdevkit_music_play(uint8_t track)
{
    if (sdevkit__music.track != track) {
        sdevkit__music.track = track;
        sdevkit__music.changed = 1;
    }
}

void sdevkit_music_stop(void)
{
    if (sdevkit__music.track >= 0) {
        sdevkit__music.track = -1;
        sdevkit__music.changed = 1;
    }
}

void sdevkit__music_reset(void)
{
    sdevkit__music.track = -1;
    sdevkit__music.changed = 1;
}
