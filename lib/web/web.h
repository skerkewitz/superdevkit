#ifndef SDEVKIT__WEB_H
#define SDEVKIT__WEB_H

/*
 * What the web adds to the desktop's frontend (lib/desktop/main.c, built with emcc for the target
 * web; ADR-6FCVAA30): main.c's loops stay as they are, and wait for the
 * browser's next frame here instead of in raylib (whose WindowShouldClose sleeps 16 ms on the web,
 * and whose frame limit waits by spinning). The waiting needs Asyncify (-sASYNCIFY): the function
 * returns to the browser and comes back when the frame is due.
 *
 * Host code for emcc, not for dampf.
 */

/* Waits for the browser's next frame in which a tick of the 60 Hz clock is due, and returns how many
 * are due (1 to 4): on a 120 Hz screen every other frame has one, on a 30 Hz one each frame two. A
 * tab that was hidden comes back with at most 4; the time it was away is not caught up. */
int sdevkit__web_frame(void);

/* The size of the browser's window, which the canvas fills. */
void sdevkit__web_size(int *width, int *height);

/* /save, kept in the browser's IndexedDB: mounted and read in (waits until it is). */
void sdevkit__web_mount(void);

/* Writes what changed in /save back to IndexedDB (in the background). */
void sdevkit__web_sync(void);

/* Offers a file of the program's memory file system as a download (F11's recording, F12's screenshot). */
void sdevkit__web_download(const char *path);

#endif
