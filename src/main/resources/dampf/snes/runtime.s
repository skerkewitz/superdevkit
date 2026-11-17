; dampf SNES runtime: start-up, header and vectors (milestone M1).
;
; After reset: native mode, A/X/Y 16 bit, D = 0, DBR = 0, stack at $1FFF,
; BSS cleared, DATA copied from ROM to RAM. Then it calls the entry function
; (dampf_entry, set by the compiler) and, when that returns, writes 1 to
; $21FB (an unused B-bus port) so a test script knows the program is done,
; and stops.

        .p816
        .smart  off

        .import dampf_entry
        .import __BSS_RUN__, __BSS_SIZE__
        .import __DATA_LOAD__, __DATA_RUN__, __DATA_SIZE__
        .export dampf_done

DONE_PORT = $21FB

        .segment "STARTUP"
reset:
        sei
        clc
        xce                     ; native mode
        rep     #$38            ; A/X/Y 16 bit, binary mode
        .a16
        .i16
        ldx     #$1FFF
        txs
        lda     #$0000
        tcd                     ; D = 0
        pha
        plb                     ; DBR = 0 (PHA pushed two zero bytes, pull both)
        plb
        sep     #$20
        .a8
        stz     $4200           ; no NMI, no IRQ, no auto joypad
        lda     #$80
        sta     $2100           ; forced blank
        rep     #$20
        .a16

        ; clear BSS
        ldx     #.loword(__BSS_SIZE__)
        beq     @bss_done
        ldy     #0
@bss:   sep     #$20
        .a8
        lda     #0
        sta     __BSS_RUN__,y
        rep     #$20
        .a16
        iny
        dex
        bne     @bss
@bss_done:

        ; copy DATA from ROM to RAM
        lda     #.loword(__DATA_SIZE__)
        beq     @data_done
        dec     a
        ldx     #.loword(__DATA_LOAD__)
        ldy     #.loword(__DATA_RUN__)
        mvn     #^__DATA_LOAD__, #^__DATA_RUN__
        phk                     ; MVN leaves DBR at the destination bank: restore it
        plb
@data_done:

        jsr     dampf_entry

dampf_done:
        sep     #$20
        .a8
        lda     #1
        sta     DONE_PORT
        rep     #$20
        .a16
@halt:  stp
        bra     @halt

nmi:
irq:
        rti

        .segment "HEADER"
        ; $FFC0: internal header (LoROM, 32 KB, no RAM). Checksum filled in after linking.
        .byte   "DAMPF TEST           "     ; title, 21 bytes
        .byte   $20                         ; LoROM, SlowROM
        .byte   $00                         ; ROM only
        .byte   $05                         ; ROM size: 32 KB
        .byte   $00                         ; no cartridge RAM
        .byte   $01                         ; region: North America (NTSC)
        .byte   $00                         ; developer
        .byte   $00                         ; version
        .word   $FFFF                       ; checksum complement
        .word   $0000                       ; checksum

        .segment "VECTORS"
        ; $FFE0: native mode vectors
        .word   0, 0                        ; reserved
        .word   .loword(irq)                ; COP
        .word   .loword(irq)                ; BRK
        .word   .loword(irq)                ; ABORT
        .word   .loword(nmi)                ; NMI
        .word   0                           ; reserved
        .word   .loword(irq)                ; IRQ
        ; $FFF0: emulation mode vectors
        .word   0, 0                        ; reserved
        .word   .loword(irq)                ; COP
        .word   0                           ; reserved
        .word   .loword(irq)                ; ABORT
        .word   .loword(nmi)                ; NMI
        .word   .loword(reset)              ; RESET
        .word   .loword(irq)                ; IRQ/BRK
