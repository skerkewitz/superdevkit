/*
 * DEPRECATED: the old names of superdevkit's console API, mapped to the sdevkit_ ones (ADR
 * record 34). Only for code that cannot be changed yet (the snapshot of Blubber Blasen); new code
 * includes "sdevkit/snes.h". tools/sdevkit_rename.py converts a game, and writes this file
 * (--write-compat): do not edit it by hand.
 */

#ifndef SNES_SNES_H
#define SNES_SNES_H

#include "sdevkit/snes.h"

typedef uint8_t  u8;
typedef uint16_t u16;
typedef int16_t  s16;
typedef uint32_t u32;

#define setMode sdevkit_set_mode
#define setBrightness sdevkit_set_brightness
#define setScreenOn sdevkit_set_screen_on
#define setScreenOff sdevkit_set_screen_off
#define setColorEffect sdevkit_set_color_effect
#define setColorIntensity sdevkit_set_color_intensity
#define setPaletteColor sdevkit_set_palette_color
#define dmaCopyVram sdevkit_dma_copy_vram
#define dmaCopyCGram sdevkit_dma_copy_cgram
#define dmaClearVram sdevkit_dma_clear_vram
#define bgSetGfxPtr sdevkit_bg_set_gfx_ptr
#define bgSetMapPtr sdevkit_bg_set_map_ptr
#define bgSetScroll sdevkit_bg_set_scroll
#define bgSetEnable sdevkit_bg_set_enable
#define bgSetDisable sdevkit_bg_set_disable
#define bgSetEnableSub sdevkit_bg_set_enable_sub
#define bgSetDisableSub sdevkit_bg_set_disable_sub
#define bgInitTileSet sdevkit_bg_init_tile_set
#define bgInitMapSet sdevkit_bg_init_map_set
#define oamInit sdevkit_oam_init
#define oamInitGfxSet sdevkit_oam_init_gfx_set
#define oamInitGfxAttr sdevkit_oam_init_gfx_attr
#define oamSet sdevkit_oam_set
#define oamSetXY sdevkit_oam_set_xy
#define oamSetEx sdevkit_oam_set_ex
#define oamSetVisible sdevkit_oam_set_visible
#define oamClear sdevkit_oam_clear
#define oamMemory sdevkit_oam_memory
#define snesRegWrite sdevkit_reg_write
#define snesHdmaSet sdevkit_hdma_set
#define snesHdmaEnable sdevkit_hdma_enable
#define snesDmaFits sdevkit_dma_fits
#define snes_vram sdevkit_dbg_vram
#define RGB5 SDEVKIT_RGB5
#define RGB8 SDEVKIT_RGB8
#define BG_MODE1 SDEVKIT_BG_MODE1
#define BG3_MODE1_PRIORITY_HIGH SDEVKIT_BG3_MODE1_PRIORITY_HIGH
#define BG1_ENABLE SDEVKIT_BG1_ENABLE
#define BG2_ENABLE SDEVKIT_BG2_ENABLE
#define BG3_ENABLE SDEVKIT_BG3_ENABLE
#define BG4_ENABLE SDEVKIT_BG4_ENABLE
#define OBJ_ENABLE SDEVKIT_OBJ_ENABLE
#define SC_32x32 SDEVKIT_SC_32x32
#define SC_64x32 SDEVKIT_SC_64x32
#define SC_32x64 SDEVKIT_SC_32x64
#define SC_64x64 SDEVKIT_SC_64x64
#define BG_4COLORS SDEVKIT_BG_4COLORS
#define BG_16COLORS SDEVKIT_BG_16COLORS
#define BG_TIL_NUM SDEVKIT_BG_TIL_NUM
#define BG_TIL_PAL SDEVKIT_BG_TIL_PAL
#define BG_TIL_PRIO SDEVKIT_BG_TIL_PRIO
#define BG_TIL_FLIPX SDEVKIT_BG_TIL_FLIPX
#define BG_TIL_FLIPY SDEVKIT_BG_TIL_FLIPY
#define OBJ_SIZE8_L16 SDEVKIT_OBJ_SIZE8_L16
#define OBJ_SIZE8_L32 SDEVKIT_OBJ_SIZE8_L32
#define OBJ_SIZE8_L64 SDEVKIT_OBJ_SIZE8_L64
#define OBJ_SIZE16_L32 SDEVKIT_OBJ_SIZE16_L32
#define OBJ_SIZE16_L64 SDEVKIT_OBJ_SIZE16_L64
#define OBJ_SIZE32_L64 SDEVKIT_OBJ_SIZE32_L64
#define OBJ_SMALL SDEVKIT_OBJ_SMALL
#define OBJ_LARGE SDEVKIT_OBJ_LARGE
#define OBJ_SHOW SDEVKIT_OBJ_SHOW
#define OBJ_HIDE SDEVKIT_OBJ_HIDE
#define ATTR2_DISABLED SDEVKIT_ATTR2_DISABLED
#define CM_SUBBGOBJ_ENABLE SDEVKIT_CM_SUBBGOBJ_ENABLE
#define CM_ALWAYS_ENABLE SDEVKIT_CM_ALWAYS_ENABLE
#define CM_MATWIN_ENABLE SDEVKIT_CM_MATWIN_ENABLE
#define CM_NOMATWIN_ENABLE SDEVKIT_CM_NOMATWIN_ENABLE
#define CM_NEVER_ENABLE SDEVKIT_CM_NEVER_ENABLE
#define CM_SCBLK_ALWAYS_ENABLE SDEVKIT_CM_SCBLK_ALWAYS_ENABLE
#define CM_SCBLK_MATWIN_ENABLE SDEVKIT_CM_SCBLK_MATWIN_ENABLE
#define CM_SCBLK_NOMATWIN_ENABLE SDEVKIT_CM_SCBLK_NOMATWIN_ENABLE
#define CM_SCBLK_NEVER_ENABLE SDEVKIT_CM_SCBLK_NEVER_ENABLE
#define CM_SUB_MAINSUB SDEVKIT_CM_SUB_MAINSUB
#define CM_DIV2_DIVIDE SDEVKIT_CM_DIV2_DIVIDE
#define CM_MSCR_BACK SDEVKIT_CM_MSCR_BACK
#define CM_MSCR_PAL47 SDEVKIT_CM_MSCR_PAL47
#define CM_MSCR_BG4 SDEVKIT_CM_MSCR_BG4
#define CM_MSCR_BG3 SDEVKIT_CM_MSCR_BG3
#define CM_MSCR_BG2 SDEVKIT_CM_MSCR_BG2
#define CM_MSCR_BG1 SDEVKIT_CM_MSCR_BG1
#define CM_APPLY_BLUE SDEVKIT_CM_APPLY_BLUE
#define CM_APPLY_GREEN SDEVKIT_CM_APPLY_GREEN
#define CM_APPLY_RED SDEVKIT_CM_APPLY_RED
#define HDMA_CHANNEL0 SDEVKIT_HDMA_CHANNEL0
#define HDMA_CHANNEL1 SDEVKIT_HDMA_CHANNEL1
#define HDMA_CHANNEL2 SDEVKIT_HDMA_CHANNEL2
#define HDMA_CHANNEL3 SDEVKIT_HDMA_CHANNEL3
#define HDMA_CHANNEL4 SDEVKIT_HDMA_CHANNEL4
#define HDMA_CHANNEL5 SDEVKIT_HDMA_CHANNEL5
#define HDMA_CHANNEL6 SDEVKIT_HDMA_CHANNEL6
#define HDMA_CHANNEL7 SDEVKIT_HDMA_CHANNEL7
#define PPU_INIDISP SDEVKIT_PPU_INIDISP
#define PPU_OBSEL SDEVKIT_PPU_OBSEL
#define PPU_BGMODE SDEVKIT_PPU_BGMODE
#define PPU_BG1SC SDEVKIT_PPU_BG1SC
#define PPU_BG2SC SDEVKIT_PPU_BG2SC
#define PPU_BG3SC SDEVKIT_PPU_BG3SC
#define PPU_BG4SC SDEVKIT_PPU_BG4SC
#define PPU_BG12NBA SDEVKIT_PPU_BG12NBA
#define PPU_BG34NBA SDEVKIT_PPU_BG34NBA
#define PPU_BG1HOFS SDEVKIT_PPU_BG1HOFS
#define PPU_BG1VOFS SDEVKIT_PPU_BG1VOFS
#define PPU_W12SEL SDEVKIT_PPU_W12SEL
#define PPU_W34SEL SDEVKIT_PPU_W34SEL
#define PPU_WOBJSEL SDEVKIT_PPU_WOBJSEL
#define PPU_WH0 SDEVKIT_PPU_WH0
#define PPU_WH1 SDEVKIT_PPU_WH1
#define PPU_WH2 SDEVKIT_PPU_WH2
#define PPU_WH3 SDEVKIT_PPU_WH3
#define PPU_WBGLOG SDEVKIT_PPU_WBGLOG
#define PPU_WOBJLOG SDEVKIT_PPU_WOBJLOG
#define PPU_TM SDEVKIT_PPU_TM
#define PPU_TS SDEVKIT_PPU_TS
#define PPU_TMW SDEVKIT_PPU_TMW
#define PPU_TSW SDEVKIT_PPU_TSW
#define PPU_CGWSEL SDEVKIT_PPU_CGWSEL
#define PPU_CGADSUB SDEVKIT_PPU_CGADSUB
#define PPU_COLDATA SDEVKIT_PPU_COLDATA

#endif /* SNES_SNES_H */
