/*
 * The web's part of the frontend (web.h): the browser's frames, the save folder in IndexedDB,
 * downloads. JavaScript in C (EM_JS, and EM_ASYNC_JS where the program waits for the browser).
 *
 * Host code for emcc, not for dampf.
 */
#include <emscripten.h>

#include "web.h"

/* The ticks due by the 60 Hz clock since the first frame: rounded, so that a 60 Hz screen's frames,
 * which come a little early or late, have one each. */
EM_ASYNC_JS(int, sdevkit__web_frame, (void), {
    const clock = Module.sdevkitClock || (Module.sdevkitClock = { start: -1, done: 0, frames: 0, dropped: 0 });
    for (;;) {
        const now = await new Promise(requestAnimationFrame);
        if (clock.start < 0) {
            clock.start = now - 1000 / 60;
        }
        const due = Math.round((now - clock.start) * 60 / 1000) - clock.done;
        clock.frames++;
        if (due >= 1) {
            clock.done += due;
            clock.dropped += Math.max(due - 4, 0);
            return Math.min(due, 4);
        }
    }
});

EM_JS(void, sdevkit__web_size, (int *width, int *height), {
    HEAP32[width >> 2] = window.innerWidth;
    HEAP32[height >> 2] = window.innerHeight;
});

EM_ASYNC_JS(void, sdevkit__web_mount, (void), {
    FS.mkdir('/save');
    FS.mount(IDBFS, {}, '/save');
    await new Promise(done => FS.syncfs(true, e => {
        if (e) {
            console.error('superdevkit: the save folder in IndexedDB cannot be read: ' + e);
        }
        done();
    }));
});

EM_JS(void, sdevkit__web_sync, (void), {
    FS.syncfs(false, e => {
        if (e) {
            console.error('superdevkit: the save RAM was not written to IndexedDB: ' + e);
        }
    });
});

EM_JS(void, sdevkit__web_download, (const char *path), {
    const name = UTF8ToString(path);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([FS.readFile(name)]));
    a.download = name.split('/').pop();
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
});
