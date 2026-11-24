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
        jml     @bank80         ; continue in bank $80, the mirror FastROM speeds up later
@bank80:
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
.if DAMPF_FASTROM
        lda     #1
        sta     f:$00420D       ; MEMSEL: banks $80-$FF at 3.58 MHz (FastROM)
.endif
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

.if DAMPF_IRQ
        cli                     ; an IRQ handler in C: IRQs come once the program sets the timer (NMITIMEN)
.endif
        jsl     dampf_entry

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
; Interrupt handlers in C (DAMPF_INTERRUPT, ADR-6FM9TXV4), assembled only for a program that has
; one (DAMPF_NMI, DAMPF_IRQ). The entry stub saves A, X and Y in 16 bits, the data bank and the
; direct page (the CPU has pushed P, PC and PBR), sets the data bank to $7E and the direct page to
; the context's own page, whose first 32 bytes are its copy of dampf_regs and dampf_ptr and whose
; rest holds its frames (the code generator exports dampf_nmi_dp and dampf_irq_dp). The vector
; leads into bank $00; the stub's first instruction jumps on into bank $80. The cycles are in
; dampf.backend.Timing (INTERRUPT_ENTRY, INTERRUPT_EXIT), from the interrupt being taken to the
; first instruction of the handler and from its RTL to the end of the RTI.

.if DAMPF_NMI
        .import dampf_nmi_handler, dampf_nmi_dp
        .export dampf_nmi
dampf_nmi:
        jml     @fast           ; on into bank $80, as reset does: FastROM, where the labels are
@fast:  rep     #$30            ; (the RTI restores the widths)
        .a16
        .i16
        pha
        phx
        phy
        phb
        phd
        lda     #dampf_nmi_dp
        tcd                     ; the NMI context's direct page
        pea     $7E7E
        plb                     ; DBR = $7E
        plb
        sep     #$20
        .a8
        lda     f:$004210       ; RDNMI: read in 8 bits, $4211 would end a pending IRQ
        rep     #$20
        .a16
        jsl     dampf_nmi_handler
        pld
        plb
        ply
        plx
        pla
        rti
.endif

.if DAMPF_IRQ
        .import dampf_irq_handler, dampf_irq_dp
        .export dampf_irq
dampf_irq:
        jml     @fast           ; on into bank $80, as reset does: FastROM, where the labels are
@fast:  rep     #$30
        .a16
        .i16
        pha
        phx
        phy
        phb
        phd
        lda     #dampf_irq_dp
        tcd                     ; the IRQ context's direct page
        pea     $7E7E
        plb                     ; DBR = $7E
        plb
        lda     f:$004211       ; TIMEUP (and HVBJOY): ends the IRQ, which would come again at once
        jsl     dampf_irq_handler
        pld
        plb
        ply
        plx
        pla
        rti
.endif

; ---------------------------------------------------------------------------
; Arithmetic routines for generated code, called with JSL (they may be in
; another bank than the caller) and left with RTL. All are entered and left
; with A/X/Y 16 bit, D = 0 (or an interrupt context's page). They work in
; dampf_regs (R0..R17 below), which is scratch space: each interrupt context
; has its own copy, in its own direct page.
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

        .segment "DAMPF_CODE"
        .a16
        .i16

; The CPU's 8 x 8 multiplier: write the factors to WRMPYA and WRMPYB, and the product is in RDMPY
; 8 CPU cycles after the write to WRMPYB. LDA long reads in its 5th cycle, so at least 4 cycles lie
; between the cycle that writes WRMPYB and that instruction. (A 16-bit STA writes WRMPYB in its
; first write cycle: its second counts. Three cycles give wrong products, here and in Mesen.)
WRMPYA = $004202
WRMPYB = $004203
RDMPY  = $004216

; A = A * R0, low 16 bits of the product: al*bl + (al*bh + ah*bl) * 256. Destroys R0, R2, X.
dampf_mul16:
        sta     z:R2            ; a
        sep     #$20
        .a8
        sta     f:WRMPYA        ; al
        lda     z:R0
        sta     f:WRMPYB        ; al * bl
        rep     #$20
        .a16
        nop
        lda     f:RDMPY
        tax
        sep     #$20
        .a8
        lda     z:R0+1
        sta     f:WRMPYB        ; al * bh
        lda     z:R2+1
        xba                     ; B = ah
        lda     f:RDMPY         ; only its low byte reaches the result
        sta     z:R0+1
        xba
        sta     f:WRMPYA        ; ah
        lda     z:R0
        sta     f:WRMPYB        ; ah * bl
        stx     z:R2            ; al * bl
        lda     z:R2+1
        clc
        adc     z:R0+1
        clc
        adc     f:RDMPY
        xba                     ; B = the high byte of the result
        lda     z:R2
        rep     #$20
        .a16
        rtl

; R8:R10 = R0:R2 * R4:R6, low 32 bits of the product, from the ten products of a byte of each
; whose place is below 4. WRMPYB is written in 16 bits: the second byte goes to $4204, the
; divider's dividend, which nothing here reads. X counts the carries into the top byte.
; Destroys R4, R6, X.
dampf_mul32:
        ldx     #0
        stz     z:R10
        sep     #$20
        .a8
        lda     z:R0
        sta     f:WRMPYA        ; a0
        rep     #$20
        .a16
        lda     z:R4
        sta     f:WRMPYB        ; a0 * b0, place 0
        nop
        nop
        lda     f:RDMPY
        sta     z:R8
        lda     z:R4+1
        sta     f:WRMPYB        ; a0 * b1, place 1
        clc
        nop
        lda     f:RDMPY
        adc     z:R8+1
        sta     z:R8+1
        bcc     @a01
        inx
@a01:   lda     z:R6
        sta     f:WRMPYB        ; a0 * b2, place 2
        clc
        nop
        lda     f:RDMPY
        adc     z:R10
        sta     z:R10
        lda     z:R6+1
        sta     f:WRMPYB        ; a0 * b3, place 3: only the low byte counts
        nop
        nop
        lda     f:RDMPY
        xba
        and     #$FF00
        clc
        adc     z:R10
        sta     z:R10
        sep     #$20
        .a8
        lda     z:R0+1
        sta     f:WRMPYA        ; a1
        rep     #$20
        .a16
        lda     z:R4
        sta     f:WRMPYB        ; a1 * b0, place 1
        clc
        nop
        lda     f:RDMPY
        adc     z:R8+1
        sta     z:R8+1
        bcc     @a10
        inx
@a10:   lda     z:R4+1
        sta     f:WRMPYB        ; a1 * b1, place 2
        clc
        nop
        lda     f:RDMPY
        adc     z:R10
        sta     z:R10
        lda     z:R6
        sta     f:WRMPYB        ; a1 * b2, place 3
        nop
        nop
        lda     f:RDMPY
        xba
        and     #$FF00
        clc
        adc     z:R10
        sta     z:R10
        sep     #$20
        .a8
        lda     z:R2
        sta     f:WRMPYA        ; a2
        rep     #$20
        .a16
        lda     z:R4
        sta     f:WRMPYB        ; a2 * b0, place 2
        clc
        nop
        lda     f:RDMPY
        adc     z:R10
        sta     z:R10
        lda     z:R4+1
        sta     f:WRMPYB        ; a2 * b1, place 3
        nop
        nop
        lda     f:RDMPY
        xba
        and     #$FF00
        clc
        adc     z:R10
        sta     z:R10
        sep     #$20
        .a8
        lda     z:R2+1
        sta     f:WRMPYA        ; a3
        rep     #$20
        .a16
        lda     z:R4
        sta     f:WRMPYB        ; a3 * b0, place 3
        nop
        nop
        lda     f:RDMPY
        xba
        and     #$FF00
        clc
        adc     z:R10
        sta     z:R10
        txa                     ; the carries into place 3
        xba
        clc
        adc     z:R10
        sta     z:R10
        rtl

.if DAMPF_NMI | DAMPF_IRQ
        .export dampf_mul16_soft, dampf_mul32_soft

; The multiplications of interrupt code, without the CPU's multiplier: an interrupt between the
; write of its factors and the read of the product would corrupt the interrupted product, and its
; factors cannot be read back to save them (ADR-6FM9TXV4). Shift and add, the same results and
; registers as dampf_mul16 and dampf_mul32.

; A = A * R0, low 16 bits of the product. Destroys R0, R2, X. One round per bit of R0 up to its
; highest one.
dampf_mul16_soft:
        sta     z:R2            ; a, shifted left each round
        ldx     #0              ; the product
        lda     z:R0
        beq     @done
@loop:  lsr     a               ; the next bit of b
        sta     z:R0
        bcc     @next
        txa
        clc
        adc     z:R2
        tax
@next:  asl     z:R2
        lda     z:R0
        bne     @loop
@done:  txa
        rtl

; R8:R10 = R0:R2 * R4:R6, low 32 bits of the product. Destroys R4, R6, X. 32 rounds, the bits of
; R4:R6 from the top: the product doubles, and gets R0:R2 where the bit is 1.
dampf_mul32_soft:
        stz     z:R8
        stz     z:R10
        ldx     #32
@loop:  asl     z:R8
        rol     z:R10
        asl     z:R4
        rol     z:R6
        bcc     @next
        clc
        lda     z:R8
        adc     z:R0
        sta     z:R8
        lda     z:R10
        adc     z:R2
        sta     z:R10
@next:  dex
        bne     @loop
        rtl
.endif

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
        rtl

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
        rtl

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
        jsl     dampf_udiv32
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
@done:  rtl

        .segment "HEADER"
        ; $FFC0: internal header (LoROM, no RAM). The ROM size and the checksum
        ; are filled in after linking (dampf.snes.RomBuilder).
        .byte   "DAMPF TEST           "     ; title, 21 bytes
.if DAMPF_FASTROM
        .byte   $30                         ; LoROM, FastROM
.else
        .byte   $20                         ; LoROM, SlowROM
.endif
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
.if DAMPF_NMI
        .word   .loword(dampf_nmi)          ; NMI: the entry stub of a handler in C
.else
        .word   .loword(nmi)                ; NMI
.endif
        .word   0                           ; reserved
.if DAMPF_IRQ
        .word   .loword(dampf_irq)          ; IRQ: the entry stub of a handler in C
.else
        .word   .loword(irq)                ; IRQ
.endif
        ; $FFF0: emulation mode vectors
        .word   0, 0                        ; reserved
        .word   .loword(irq)                ; COP
        .word   0                           ; reserved
        .word   .loword(irq)                ; ABORT
        .word   .loword(nmi)                ; NMI
        .word   .loword(reset)              ; RESET
        .word   .loword(irq)                ; IRQ/BRK
