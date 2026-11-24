; The functions of asm_functions.c, written by hand (DAMPF_ASM): dampf's convention, the same
; for both code generators. Parameters in their places in the frame (imported), the result in A
; (and X for 32 bits), JSL/RTL, A/X/Y 16 bit on entry and exit, DBR = $7E, D = 0.

        .p816
        .smart  off
        .a16
        .i16
        .importzp _mix8__a, _mix8__b, _neg8__a, _bits16__x, _bits16__locals
        .importzp _add32__a, _add32__b, _sum_words__p, _sum_words__n, _bump__by
        .import _counter
        .export _mix8, _neg8, _bits16, _add32, _sum_words, _bump

; uint8_t mix8(uint8_t a, uint8_t b): (a ^ b) + 1 in 8 bits
        .segment "C_mix8"
.proc _mix8
        sep     #$20
        .a8
        lda     z:_mix8__a
        eor     z:_mix8__b
        inc     a
        rep     #$20
        .a16
        rtl                             ; A's high byte is whatever it was
.endproc

; int8_t neg8(int8_t a): -a; the high byte of a's place is undefined, and so is A's
        .segment "C_neg8"
.proc _neg8
        lda     z:_neg8__a
        eor     #$FFFF
        inc     a
        rtl
.endproc

; uint16_t bits16(uint16_t x): the number of 1 bits
        .segment "C_bits16"
.proc _bits16
        lda     z:_bits16__x
        sta     z:_bits16__locals       ; the bits not counted yet
        ldy     #0                      ; the count
        ldx     #16
@loop:  lsr     z:_bits16__locals
        bcc     @zero
        iny
@zero:  dex
        bne     @loop                   ; dampf: at most 16
        tya
        rtl
.endproc

; uint32_t add32(uint32_t a, uint32_t b)
        .segment "C_add32"
.proc _add32
        clc
        lda     z:_add32__a
        adc     z:_add32__b
        pha
        lda     z:_add32__a+2
        adc     z:_add32__b+2
        tax
        pla
        rtl
.endproc

; uint16_t sum_words(const uint16_t *p, uint8_t n): p[0] + ... + p[n - 1]
        .segment "C_sum_words"
.proc _sum_words
        lda     z:_sum_words__n
        and     #$00FF                  ; an 8-bit parameter is the low byte
        beq     @none
        asl     a
        tay                             ; 2 n: past the last word
        lda     #0
@loop:  dey
        dey
        clc
        adc     [_sum_words__p],y
        cpy     #0
        bne     @loop                   ; dampf: at most 8
        rtl
@none:  lda     #0
        rtl
.endproc

; void bump(uint16_t by): counter += by
        .segment "C_bump"
.proc _bump
        lda     _counter
        clc
        adc     z:_bump__by
        sta     _counter
        rtl
.endproc
