; Reference version of sine() (Er-Tüp, examples/ertuep/src/objects.c), written by hand.
; It is the yardstick for dampf's code, not part of the game (reference/README.md). Linked
; instead of the compiled function with
;   dampf build @ertuep.args ... --replace sine=../../reference/ertuep_sine.s
;
; The C it replaces (a table of 64 signed bytes in ROM):
;   int8_t sine(uint8_t a)
;   {
;       return SINE[a & 63];
;   }
;
; dampf's convention for functions written in assembly (docs/hints.md, DAMPF_ASM): the parameter
; in its place in the frame (imported), an 8-bit result in the low byte of A (the caller extends
; it), JSL/RTL, A/X/Y 16 bit, DBR = $7E, D = 0. The table lies in a ROM bank of its own: a long
; address.

        .p816
        .smart  off
        .a16
        .i16
        .importzp _sine__a
        .import _SINE
        .export _sine

        .segment "C_sine"
.proc _sine
        lda     z:_sine__a              ; the byte, and the undefined one after it ...
        and     #$003F                  ; ... a & 63
        tax
        lda     f:_SINE,x               ; the low byte is the result
        rtl
.endproc
