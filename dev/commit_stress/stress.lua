-- The stress ROM in Mesen: bytes reaching VRAM, CGRAM or OAM outside the vertical blank with the
-- screen on (as dev/example_check.lua counts them), the latest scanline a DMA starts on and the
-- latest the commit's last write (INIDISP) is on, counted from the vertical blank's start (225).
local ok, err = pcall(function()
  local out = io.open("stress_out.txt", "w")
  local ticks, frames, lost, vblank, screenOff = 0, 0, 0, false, true
  local lastDma, lastEnd = 0, 0
  local function line()
    local s = emu.getState()["ppu.scanline"]
    if s < 225 then s = s + 262 end
    return s
  end
  emu.addMemoryCallback(function(addr, value)
    if value == 0 then ticks = ticks + 1 end
  end, emu.callbackType.write, 0x21FD, 0x21FD)
  emu.addEventCallback(function() vblank = false end, emu.eventType.startFrame)
  emu.addMemoryCallback(function(addr, value)
    screenOff = (value & 0x80) ~= 0
    if ticks > 2 then local l = line(); if l > lastEnd then lastEnd = l end end
  end, emu.callbackType.write, 0x2100, 0x2100)
  emu.addMemoryCallback(function(addr, value)
    if ticks > 2 and (value & 1) == 1 then local l = line(); if l > lastDma then lastDma = l end end
  end, emu.callbackType.write, 0x420B, 0x420B)
  emu.addMemoryCallback(function(addr, value)
    if addr ~= 0x2104 and addr ~= 0x2118 and addr ~= 0x2119 and addr ~= 0x2122 then return end
    if not vblank and not screenOff then lost = lost + 1 end
  end, emu.callbackType.write, 0x2104, 0x2122)
  emu.addEventCallback(function()
    vblank = true
    frames = frames + 1
    if ticks >= 600 then
      out:write(string.format("frames %d ticks %d lost %d last_dma_line %d last_commit_line %d\n", frames, ticks, lost, lastDma, lastEnd))
      out:close()
      emu.stop(0)
    end
  end, emu.eventType.endFrame)
end)
if not ok then local f = io.open("stress_out.txt", "w"); f:write("error " .. tostring(err) .. "\n"); f:close(); emu.stop(1) end
