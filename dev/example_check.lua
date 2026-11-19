-- Mesen script for dev/check_example.sh: plays an example with the input of its check.txt and
-- checks what only the console shows: every byte written into VRAM, CGRAM or OAM must arrive
-- inside a vertical blank or with the screen off (the PPU drops it otherwise). Takes screenshots.
--
-- It counts in ticks, not frames: a tick is one game_tick, which the library's main loop ends
-- with a copy of the sprite table; a tick that takes longer than a frame makes the console fall
-- behind the frames, and counting ticks keeps the run the same as on the desktop, where every
-- frame is one tick. example_input.txt (copied from examples/<name>/check.txt), one line each:
--   ticks N                     stop after N ticks
--   shot T                      a screenshot of the picture after tick T (example_shot_T.png)
--   press T1 T2 BUTTON ...      hold the buttons (a b x y l r start select up down left right)
--                               on pad 1 for the ticks T1 to T2 - 1 (counted from 0)
--   splash N                    a screenshot of frame N (from 1) of the splash screen, before
--                               the first tick (example_splash_N.png): the splash writes the
--                               brightness ($2100, the screen on) once a frame in the vertical
--                               blank, where everything before it only switches the screen off,
--                               so frame N is the Nth write with bit 7 clear; its picture is
--                               taken two frame ends on, as a tick's
-- Writes example_check.txt: lost bytes per frame, then "frames F ticks T lost L"; and
-- example_hashes.txt: the checksum of every tick, "tick hash" (hex), as the desktop's check writes
-- hashes.txt. The kit's main loop writes 0 to $21FD after each game_tick (1 once after game_init);
-- sdevkit_dbg_hash writes the address and size of what it names to $21FC (five bytes), and the
-- memory is read and hashed here (FNV-1a, 32 bits).
-- And example_sound.txt, the sound as the desktop's check writes sound.txt (lib/desktop): every
-- write the SPC700 makes to a DSP register ($F2 names it, $F3 takes the value), "init" before the
-- first tick or the tick after which it came, the register and the value in hex; after
-- game_init's writes "aram H": the FNV-1a of the bytes written into the audio RAM from $0200 on,
-- each its address (low, high byte) and value, in the order written. (Not of the RAM itself: what
-- the uploads did not touch is Mesen's fill pattern there, zero in the others.)
local ok, err = pcall(function()
  local total, shots, presses, splash = 600, {}, {}, {}
  for line in io.lines("example_input.txt") do
    local w = {}
    for word in line:gmatch("%S+") do w[#w + 1] = word end
    if w[1] == "ticks" then total = tonumber(w[2])
    elseif w[1] == "shot" then shots[tonumber(w[2])] = true
    elseif w[1] == "splash" then splash[tonumber(w[2])] = true
    elseif w[1] == "press" then
      -- the buttons of every tick, worked out once: a recording of the desktop (lib/desktop,
      -- F11) has thousands of press lines; its "hash" and "mark" lines are not read here
      local from, to = tonumber(w[2]), tonumber(w[3])
      for t = math.max(from, 0), to - 1 do
        local held = presses[t]
        if not held then held = {}; presses[t] = held end
        for i = 4, #w do held[w[i]] = true end
      end
    end
  end
  local out = io.open("example_check.txt", "w")
  local hashes = io.open("example_hashes.txt", "w")
  local hash, hashBytes, hashTicks = 2166136261, {}, 0
  local sound, started, dspAddr, upload = io.open("example_sound.txt", "w"), false, 0, 2166136261
  emu.addMemoryCallback(function(addr, value)
    local h = upload
    h = ((h ~ (addr & 0xFF)) * 16777619) & 0xFFFFFFFF
    h = ((h ~ (addr >> 8)) * 16777619) & 0xFFFFFFFF
    upload = ((h ~ value) * 16777619) & 0xFFFFFFFF
  end, emu.callbackType.write, 0x0200, 0xFFFF, emu.cpuType.spc, emu.memType.spcMemory)
  emu.addMemoryCallback(function(addr, value) dspAddr = value end,
    emu.callbackType.write, 0xF2, 0xF2, emu.cpuType.spc, emu.memType.spcMemory)
  emu.addMemoryCallback(function(addr, value)
    if dspAddr >= 0x80 then return end
    if started then
      sound:write(string.format("%d %02x %02x\n", hashTicks - 1, dspAddr, value))
    else
      sound:write(string.format("init %02x %02x\n", dspAddr, value))
    end
  end, emu.callbackType.write, 0xF3, 0xF3, emu.cpuType.spc, emu.memType.spcMemory)
  emu.addMemoryCallback(function(addr, value)
    hashBytes[#hashBytes + 1] = value
    if #hashBytes == 5 then
      local a = hashBytes[1] | (hashBytes[2] << 8) | (hashBytes[3] << 16)
      local n = hashBytes[4] | (hashBytes[5] << 8)
      local h = hash
      for i = 0, n - 1 do
        h = ((h ~ emu.read(a + i, emu.memType.snesMemory)) * 16777619) & 0xFFFFFFFF
      end
      hash = h
      hashBytes = {}
    end
  end, emu.callbackType.write, 0x21FC, 0x21FC)
  emu.addMemoryCallback(function(addr, value)
    if value == 1 and not started then      -- game_init is done: what the uploads wrote
      sound:write(string.format("aram %08x\n", upload))
      started = true
    end
    if value == 0 and hashTicks < total then
      hashes:write(string.format("%d %08x\n", hashTicks, hash))
      hashTicks = hashTicks + 1
    end
    hash = 2166136261
  end, emu.callbackType.write, 0x21FD, 0x21FD)
  -- the library copies the sprite table once at power-on, before the first tick: not a tick
  local frame, lost, ticks, shot_due, shot_wait = 0, 0, -1, nil, 0
  local brightness_writes, splash_due, splash_wait = 0, nil, 0
  emu.addMemoryCallback(function(addr, value)
    for ch = 0, 7 do
      if (value >> ch) & 1 == 1 and emu.read(0x4301 + ch * 16, emu.memType.snesMemory) == 0x04 then
        ticks = ticks + 1
        if shots[ticks] then shot_due = ticks; shot_wait = 2 end   -- its picture: two frame ends on
      end
    end
  end, emu.callbackType.write, 0x420B, 0x420B)
  -- The vertical blank runs from the end of a frame (scanline 225) to the start of the next one;
  -- the screen is off while bit 7 of INIDISP ($2100) is set. Kept as two flags: emu.getState()
  -- for every byte written made the check about fifty times slower.
  local vblank, screenOff = false, emu.getState()["ppu.forcedBlank"]
  emu.addEventCallback(function() vblank = false end, emu.eventType.startFrame)
  emu.addMemoryCallback(function(addr, value)
    screenOff = (value & 0x80) ~= 0
    if (value & 0x80) == 0 and ticks <= 0 then
      brightness_writes = brightness_writes + 1
      if splash[brightness_writes] then splash_due = brightness_writes; splash_wait = 2 end
    end
  end, emu.callbackType.write, 0x2100, 0x2100)
  local ports = { [0x2104] = "OAM", [0x2118] = "VRAM", [0x2119] = "VRAM", [0x2122] = "CGRAM" }
  local lostNow = {}
  emu.addMemoryCallback(function(addr, value)
    local what = ports[addr]
    if not what or vblank or screenOff then return end
    lost = lost + 1
    lostNow[what] = (lostNow[what] or 0) + 1
  end, emu.callbackType.write, 0x2104, 0x2122)
  emu.addEventCallback(function()
    -- the joypad is read at the start of the vertical blank, before the copy that ends the
    -- running tick: what is set now is what the next tick reads
    local input = {}
    for b in pairs(presses[ticks + 1] or {}) do input[b] = true end
    emu.setInput(input, 0)
  end, emu.eventType.inputPolled)
  emu.addEventCallback(function()
    vblank = true
    for what, n in pairs(lostNow) do
      out:write(string.format("lost frame %d: %d bytes of %s\n", frame, n, what))
    end
    lostNow = {}
    frame = frame + 1
    if splash_due then                    -- the frame drawn with the splash's Nth brightness
      splash_wait = splash_wait - 1
      if splash_wait == 0 then
        local png = io.open(string.format("example_splash_%d.png", splash_due), "wb")
        png:write(emu.takeScreenshot()); png:close()
        splash_due = nil
      end
    end
    if shot_due then                      -- the frame drawn after tick T's vertical blank
      shot_wait = shot_wait - 1
      if shot_wait == 0 then
        local png = io.open(string.format("example_shot_%d.png", shot_due), "wb")
        png:write(emu.takeScreenshot()); png:close()
        shot_due = nil
      end
    end
    if ticks >= total and not shot_due then
      out:write(string.format("frames %d ticks %d lost %d\n", frame, ticks, lost))
      out:close()
      hashes:close()
      sound:close()
      emu.stop(0)
    end
  end, emu.eventType.endFrame)
end)
if not ok then
  local f = io.open("example_check.txt", "w"); f:write("lua error: " .. tostring(err) .. "\n"); f:close(); emu.stop(1)
end
