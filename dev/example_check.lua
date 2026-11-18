-- Mesen 2 script for dev/check_example.sh: plays an example with the input of its check.txt and
-- checks what only the console shows: every byte written into VRAM, CGRAM or OAM must arrive
-- inside a vertical blank or with the screen off (the PPU drops it otherwise). Takes screenshots.
--
-- example_input.txt (copied from examples/<name>/check.txt), one line each:
--   frames N                    stop after N frames
--   shot F                      a screenshot after frame F (example_shot_F.png)
--   press F1 F2 BUTTON ...      hold the buttons (a b x y l r start select up down left right)
--                               on pad 1 from frame F1 to frame F2 - 1
-- Writes example_check.txt: lost bytes per frame, then "frames N ticks T lost L" (a tick is one
-- copy of the sprite table, which the library's main loop makes after each game_tick).
local ok, err = pcall(function()
  local frames, shots, presses = 600, {}, {}
  for line in io.lines("example_input.txt") do
    local w = {}
    for word in line:gmatch("%S+") do w[#w + 1] = word end
    if w[1] == "frames" then frames = tonumber(w[2])
    elseif w[1] == "shot" then shots[tonumber(w[2])] = true
    elseif w[1] == "press" then
      local p = { from = tonumber(w[2]), to = tonumber(w[3]), buttons = {} }
      for i = 4, #w do p.buttons[w[i]] = true end
      presses[#presses + 1] = p
    end
  end
  local out = io.open("example_check.txt", "w")
  local frame, lost, ticks = 0, 0, 0
  emu.addMemoryCallback(function(addr, value)
    for ch = 0, 7 do
      if (value >> ch) & 1 == 1 and emu.read(0x4301 + ch * 16, emu.memType.snesMemory) == 0x04 then
        ticks = ticks + 1
      end
    end
  end, emu.callbackType.write, 0x420B, 0x420B)
  local ports = { [0x2104] = "OAM", [0x2118] = "VRAM", [0x2119] = "VRAM", [0x2122] = "CGRAM" }
  local lostNow = {}
  emu.addMemoryCallback(function(addr, value)
    local what = ports[addr]
    if not what then return end
    local s = emu.getState()
    if s["ppu.scanline"] < 225 and not s["ppu.forcedBlank"] then
      lost = lost + 1
      lostNow[what] = (lostNow[what] or 0) + 1
    end
  end, emu.callbackType.write, 0x2104, 0x2122)
  emu.addEventCallback(function()
    local input = {}
    for _, p in ipairs(presses) do
      if frame >= p.from and frame < p.to then
        for b in pairs(p.buttons) do input[b] = true end
      end
    end
    emu.setInput(input, 0)
  end, emu.eventType.inputPolled)
  emu.addEventCallback(function()
    for what, n in pairs(lostNow) do
      out:write(string.format("lost frame %d: %d bytes of %s\n", frame, n, what))
    end
    lostNow = {}
    frame = frame + 1
    if shots[frame] then
      local png = io.open(string.format("example_shot_%d.png", frame), "wb")
      png:write(emu.takeScreenshot()); png:close()
    end
    if frame >= frames then
      out:write(string.format("frames %d ticks %d lost %d\n", frame, ticks, lost))
      out:close()
      emu.stop(0)
    end
  end, emu.eventType.endFrame)
end)
if not ok then
  local f = io.open("example_check.txt", "w"); f:write("lua error: " .. tostring(err) .. "\n"); f:close(); emu.stop(1)
end
