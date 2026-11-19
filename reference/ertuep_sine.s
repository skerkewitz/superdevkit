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
; dampf's calling convention (naive code generator): the parameter in the function's static
; frame, the result in A (an int8_t sign-extended to 16 bits), JSL/RTL, A/X/Y 16 bit, DBR = $7E,
; D = 0. The table lies in a ROM bank of its own: a long address.

        .p816
        .smart  off
        .a16
        .i16
        .import _sine__a, _SINE
        .export _sine

        .segment "C_sine"
.proc _sine
        lda     _sine__a                ; the byte, and the one after it in the frame ...
        and     #$003F                  ; ... a & 63
        tax
        lda     f:_SINE,x
        and     #$00FF
        cmp     #$0080                  ; sign-extend the byte
        bcc     :+
        ora     #$FF00
:       rtl
.endproc
