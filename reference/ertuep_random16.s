; Reference version of random16() (Er-Tüp, examples/ertuep/src/objects.c), written by hand.
; It is the yardstick for dampf's code, not part of the game (reference/README.md). Linked
; instead of the compiled function with
;   dampf build @ertuep.args ... --replace random16=../../reference/ertuep_random16.s
;
; The C it replaces (a 16-bit xorshift):
;   uint16_t random16(void)
;   {
;       uint16_t x = rnd;
;       x ^= (uint16_t)(x << 7);
;       x ^= (uint16_t)((x >> 8) >> 1);
;       x ^= (uint16_t)(x << 8);
;       rnd = x;
;       return x;
;   }
;
; dampf's calling convention (naive code generator): the result in A, JSL/RTL, A/X/Y 16 bit,
; DBR = $7E, D = 0; dampf_ptr is scratch.

        .p816
        .smart  off
        .a16
        .i16
        .importzp dampf_ptr
        .import _rnd
        .export _random16

        .segment "C_random16"
.proc _random16
        lda     _rnd
        sta     z:dampf_ptr+4           ; x
        asl     a                       ; x << 7
        asl     a
        asl     a
        asl     a
        asl     a
        asl     a
        asl     a
        eor     z:dampf_ptr+4
        sta     z:dampf_ptr+4           ; x ^= x << 7
        xba                             ; x >> 8: the high byte down ...
        and     #$00FF                  ; ... and nothing above it
        lsr     a                       ; x >> 9
        eor     z:dampf_ptr+4
        sta     z:dampf_ptr+4           ; x ^= x >> 9
        xba                             ; x << 8: the low byte up ...
        and     #$FF00                  ; ... and nothing below it
        eor     z:dampf_ptr+4           ; x ^= x << 8
        sta     _rnd
        rtl
.endproc
