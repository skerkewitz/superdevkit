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
-- Writes example_check.txt: lost bytes per frame, then "frames F ticks T lost L".
local ok, err = pcall(function()
  local total, shots, presses = 600, {}, {}
  for line in io.lines("example_input.txt") do
    local w = {}
    for word in line:gmatch("%S+") do w[#w + 1] = word end
    if w[1] == "ticks" then total = tonumber(w[2])
    elseif w[1] == "shot" then shots[tonumber(w[2])] = true
    elseif w[1] == "press" then
      local p = { from = tonumber(w[2]), to = tonumber(w[3]), buttons = {} }
      for i = 4, #w do p.buttons[w[i]] = true end
      presses[#presses + 1] = p
    end
  end
  local out = io.open("example_check.txt", "w")
  -- the library copies the sprite table once at power-on, before the first tick: not a tick
  local frame, lost, ticks, shot_due, shot_wait = 0, 0, -1, nil, 0
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
  emu.addMemoryCallback(function(addr, value) screenOff = (value & 0x80) ~= 0 end,
    emu.callbackType.write, 0x2100, 0x2100)
  local ports = { [0x2104] = "OAM", [0x2118] = "VRAM", [0x2119] = "VRAM", [0x2122] = "CGRAM" }
  local lostNow = {}
  emu.addMemoryCallback(function(addr, value)
    local what = ports[addr]
    if not what or vblank or screenOff then return end
    lost = lost + 1
    lostNow[what] = (lostNow[what] or 0) + 1
  end, emu.callbackType.write, 0x2104, 0x2122)
  emu.addEventCallback(function()
    local input = {}
    for _, p in ipairs(presses) do
      -- the joypad is read at the start of the vertical blank, before the copy that ends the
      -- running tick: what is set now is what the next tick reads
      if ticks + 1 >= p.from and ticks + 1 < p.to then
        for b in pairs(p.buttons) do input[b] = true end
      end
    end
    emu.setInput(input, 0)
  end, emu.eventType.inputPolled)
  emu.addEventCallback(function()
    vblank = true
    for what, n in pairs(lostNow) do
      out:write(string.format("lost frame %d: %d bytes of %s\n", frame, n, what))
    end
    lostNow = {}
    frame = frame + 1
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
      emu.stop(0)
    end
  end, emu.eventType.endFrame)
end)
if not ok then
  local f = io.open("example_check.txt", "w"); f:write("lua error: " .. tostring(err) .. "\n"); f:close(); emu.stop(1)
end
