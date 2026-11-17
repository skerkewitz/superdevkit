; dampf SNES runtime: start-up, header and vectors (milestone M1).
;
; After reset: native mode, A/X/Y 16 bit, D = 0, DBR = $7E (all variables are
; in bank $7E, see docs/memory-map.md), stack at $1FFF, BSS cleared, DATA
; copied from ROM to RAM. Then it calls the entry function
; (dampf_entry, set by the compiler) and, when that returns, writes 1 to
; $21FB (an unused B-bus port) so a test script knows the program is done,
; and loops forever.

        .p816
        .smart  off

        .import dampf_entry
        .import __BSS_RUN__, __BSS_SIZE__
        .import __DATA_LOAD__, __DATA_RUN__, __DATA_SIZE__
        .export dampf_done
        .exportzp dampf_regs, dampf_ptr
        .export dampf_mul16, dampf_mul32, dampf_udiv16, dampf_udiv32, dampf_sdiv32

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
        sep     #$20
        .a8
        lda     #0
        sta     f:$004200       ; no NMI, no IRQ, no auto joypad
        lda     #$80
        sta     f:$002100       ; forced blank
        rep     #$20
        .a16
        pea     $7E7E
        plb                     ; DBR = $7E (PEA pushed two bytes, pull both)
        plb

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
        mvn     #^__DATA_LOAD__, #$7E   ; leaves DBR = $7E, the destination bank
@data_done:

        jsr     dampf_entry

dampf_done:
        sep     #$20
        .a8
        lda     #1
        sta     f:DONE_PORT     ; a long address: DBR points at RAM, not at the I/O registers
        rep     #$20
        .a16
@halt:  bra     @halt           ; wait here; not STP, which would stop the CPU and leave
                                ; a debugger nothing to step or continue

nmi:
irq:
        rti

; ---------------------------------------------------------------------------
; Arithmetic routines for generated code. All are entered and left with
; A/X/Y 16 bit, D = 0. They work in dampf_regs (R0..R17 below), which is
; scratch space: an interrupt handler that calls C code must save it.
;
; Simple shift-and-add / shift-and-subtract loops: this is the reference
; implementation. The SNES hardware multiplier and divider come later.

        .segment "ZEROPAGE"
dampf_regs:     .res    18
; Two 24-bit pointers (4 bytes each: address, bank, unused) for [dp],y
; accesses: generated code copies a pointer here right before it uses it.
dampf_ptr:      .res    8

R0  = dampf_regs+0
R2  = dampf_regs+2
R4  = dampf_regs+4
R6  = dampf_regs+6
R8  = dampf_regs+8
R10 = dampf_regs+10
R12 = dampf_regs+12
R14 = dampf_regs+14
R16 = dampf_regs+16

        .segment "CODE"
        .a16
        .i16

; A = A * R0, low 16 bits of the product. Destroys R0, R2, X.
dampf_mul16:
        sta     z:R2            ; multiplicand
        lda     #0
        ldx     #16
@loop:  asl     a               ; product <<= 1
        asl     z:R0            ; next multiplier bit, from the top, into C
        bcc     @skip
        clc
        adc     z:R2
@skip:  dex
        bne     @loop
        rts

; R8:R10 = R0:R2 * R4:R6, low 32 bits of the product. Destroys R4, R6, X.
dampf_mul32:
        stz     z:R8
        stz     z:R10
        ldx     #32
@loop:  asl     z:R8            ; product <<= 1
        rol     z:R10
        asl     z:R4            ; next multiplier bit, from the top, into C
        rol     z:R6
        bcc     @skip
        clc
        lda     z:R8
        adc     z:R0
        sta     z:R8
        lda     z:R10
        adc     z:R2
        sta     z:R10
@skip:  dex
        bne     @loop
        rts

; Unsigned A / R0: quotient in A, remainder in R2. Destroys R4, X.
; Division by zero gives quotient $FFFF (C leaves it undefined).
dampf_udiv16:
        sta     z:R4            ; dividend, shifted into the remainder bit by bit
        stz     z:R2
        ldx     #16
@loop:  asl     z:R4            ; quotient bit 0 becomes free
        rol     z:R2
        bcs     @sub            ; a 17th bit: the remainder is surely >= the divisor
        lda     z:R2
        sec
        sbc     z:R0
        bcc     @next           ; remainder < divisor: quotient bit stays 0
        sta     z:R2
        inc     z:R4
        bra     @next
@sub:   lda     z:R2
        sec
        sbc     z:R0
        sta     z:R2
        inc     z:R4
@next:  dex
        bne     @loop
        lda     z:R4
        rts

; Unsigned R0:R2 / R4:R6: quotient in R8:R10, remainder in R12:R14. Destroys X, Y.
dampf_udiv32:
        lda     z:R0
        sta     z:R8
        lda     z:R2
        sta     z:R10
        stz     z:R12
        stz     z:R14
        ldx     #32
@loop:  asl     z:R8            ; next dividend bit into the remainder
        rol     z:R10
        rol     z:R12
        rol     z:R14
        bcs     @sub            ; a 33rd bit: the remainder is surely >= the divisor
        lda     z:R12
        sec
        sbc     z:R4
        tay
        lda     z:R14
        sbc     z:R6
        bcc     @next           ; remainder < divisor
        bra     @store
@sub:   lda     z:R12
        sec
        sbc     z:R4
        tay
        lda     z:R14
        sbc     z:R6
@store: sta     z:R14
        sty     z:R12
        inc     z:R8            ; quotient bit
@next:  dex
        bne     @loop
        rts

; Signed R0:R2 / R4:R6 as in C: the quotient rounds towards zero (R8:R10),
; the remainder has the sign of the dividend (R12:R14). Destroys R0..R6, R16, X, Y.
dampf_sdiv32:
        stz     z:R16           ; bit 0: negate the quotient, bit 1: negate the remainder
        lda     z:R2
        bpl     @divisor
        lda     #0              ; dividend = -dividend
        sec
        sbc     z:R0
        sta     z:R0
        lda     #0
        sbc     z:R2
        sta     z:R2
        lda     #3
        sta     z:R16
@divisor:
        lda     z:R6
        bpl     @divide
        lda     #0              ; divisor = -divisor
        sec
        sbc     z:R4
        sta     z:R4
        lda     #0
        sbc     z:R6
        sta     z:R6
        lda     z:R16
        eor     #1
        sta     z:R16
@divide:
        jsr     dampf_udiv32
        lda     z:R16
        and     #1
        beq     @rem
        lda     #0              ; quotient = -quotient
        sec
        sbc     z:R8
        sta     z:R8
        lda     #0
        sbc     z:R10
        sta     z:R10
@rem:   lda     z:R16
        and     #2
        beq     @done
        lda     #0              ; remainder = -remainder
        sec
        sbc     z:R12
        sta     z:R12
        lda     #0
        sbc     z:R14
        sta     z:R14
@done:  rts

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
