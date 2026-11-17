-- Mesen 2 script: how often each instruction ran (execution counts per ROM offset), for
-- dampf profile. Written to profile.txt as "offset count" lines, plus "clocks N": the master
-- clocks of the measured stretch, counted in whole frames (Lua sees Mesen's master clock as a
-- 32-bit number, which a replay of a few thousand ticks would overflow).
--
-- The stretch is given in ticks of the game's benchmark (bench.c writes $21FA after each tick):
-- PROFILE_FROM and PROFILE_TO are filled in by tools/profile_game.sh. FROM 0 starts at once;
-- the profile also ends when the program writes $21FB (done), e.g. a replay.
local ok, err = pcall(function()
  local from, to = PROFILE_FROM, PROFILE_TO
  local tick = 0
  local frames = 0
  local running = false
  local function start() emu.resetAccessCounters(); frames = 0; running = true end
  emu.addEventCallback(function() if running then frames = frames + 1 end end, emu.eventType.endFrame)
  local function dump()
    local clocks = frames * 357366
    local c = emu.getAccessCounters(emu.memType.snesPrgRom, emu.counterType.execCount)
    local out = io.open("profile.txt", "w")
    out:write("clocks ", clocks, "\n")
    for i = 0, #c do if c[i] and c[i] > 0 then out:write(i, " ", c[i], "\n") end end
    out:close()
    emu.stop(0)
  end
  if from == 0 then start() end
  emu.addMemoryCallback(function()
    tick = tick + 1
    if tick == from then start() end
    if tick == to then dump() end
  end, emu.callbackType.write, 0x21FA, 0x21FA)
  emu.addMemoryCallback(function() dump() end, emu.callbackType.write, 0x21FB, 0x21FB)
end)
if not ok then local f = io.open("profile.txt", "w"); f:write("lua error: " .. tostring(err) .. "\n"); f:close(); emu.stop(1) end
