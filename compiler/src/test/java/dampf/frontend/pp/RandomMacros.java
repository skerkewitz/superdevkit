package dampf.frontend.pp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Random preprocessor input for {@link RandomMacrosTest}: macros that call macros, take macro names
 * as arguments and invoke them, refer to themselves, stringify and paste their parameters, take
 * empty and variadic arguments, invocations over several lines, {@code #undef} and redefinition,
 * and {@code #if}/{@code #elif} with {@code defined} and arithmetic. Valid C99 by construction: a
 * pasted parameter only ever gets a name, a number or nothing, divisors in {@code #if} are odd,
 * and shift counts small.
 */
final class RandomMacros {
    private final Random rnd;
    private final StringBuilder out = new StringBuilder();
    /** The macros defined so far: name, parameter count (-1: object-like), variadic, pasted parameters. */
    private record Def(String name, int params, boolean variadic, boolean[] pasted) {}
    private final List<Def> defs = new ArrayList<>();
    private int next;

    RandomMacros(long seed) {
        this.rnd = new Random(seed);
    }

    String generate() {
        int n = 4 + rnd.nextInt(12);
        for (int k = 0; k < n; k++) {
            if (!defs.isEmpty() && rnd.nextInt(8) == 0) {
                Def d = defs.get(rnd.nextInt(defs.size()));
                out.append("#undef ").append(d.name).append('\n');
                defs.remove(d);
            }
            define();
            if (rnd.nextInt(3) == 0) {
                conditional();
            }
            for (int u = rnd.nextInt(3); u >= 0; u--) {
                use();
            }
        }
        return out.toString();
    }

    private String name() {
        return (rnd.nextBoolean() ? "M" : "f") + next++;
    }

    private void define() {
        String name = rnd.nextInt(6) == 0 && !defs.isEmpty() ? defs.get(rnd.nextInt(defs.size())).name : name();
        defs.removeIf(d -> d.name.equals(name));
        int kind = rnd.nextInt(5);
        if (kind == 0) {
            out.append("#define ").append(name).append(' ').append(body(List.of(), new boolean[0], name)).append('\n');
            defs.add(new Def(name, -1, false, new boolean[0]));
            return;
        }
        int params = rnd.nextInt(4);
        boolean variadic = rnd.nextInt(5) == 0;
        List<String> ps = new ArrayList<>();
        for (int p = 0; p < params; p++) {
            ps.add("p" + p);
        }
        if (variadic) {
            ps.add("__VA_ARGS__");
        }
        boolean[] pasted = new boolean[ps.size()];
        String body = body(ps, pasted, name);
        out.append("#define ").append(name).append('(');
        for (int p = 0; p < params; p++) {
            out.append(p > 0 ? ", " : "").append("p").append(p);
        }
        if (variadic) {
            out.append(params > 0 ? ", " : "").append("...");
        }
        out.append(") ").append(body).append('\n');
        defs.add(new Def(name, params, variadic, pasted));
    }

    /** A replacement list: names, numbers, punctuators, other macros, parameters plain, # and ##. */
    private String body(List<String> ps, boolean[] pasted, String self) {
        List<String> parts = new ArrayList<>();
        int n = 1 + rnd.nextInt(7);
        for (int k = 0; k < n; k++) {
            int c = rnd.nextInt(10);
            if (!ps.isEmpty() && c < 3) {
                int p = rnd.nextInt(ps.size());
                parts.add(ps.get(p));
            } else if (!ps.isEmpty() && c == 3) {
                parts.add("#" + ps.get(rnd.nextInt(ps.size())));
            } else if (c == 4) {
                // a paste of two names or numbers: a parameter that is pasted only ever gets those
                String a = pasteOperand(ps, pasted);
                String b = pasteOperand(ps, pasted);
                parts.add(a + " ## " + b);
            } else if (c == 5 && !defs.isEmpty()) {
                Def d = defs.get(rnd.nextInt(defs.size()));
                parts.add(d.params < 0 || rnd.nextInt(4) == 0 ? d.name : d.name + "(" + args(d, ps) + ")");
            } else if (c == 6) {
                parts.add(self);
            } else {
                parts.add(new String[] {"+", "x", "1", "(", ")", ",", "y", "2u", "-", "<<", "\"s\"", "'c'"}[rnd.nextInt(12)]);
            }
        }
        return String.join(rnd.nextBoolean() ? " " : "  ", parts);
    }

    private String pasteOperand(List<String> ps, boolean[] pasted) {
        if (!ps.isEmpty() && rnd.nextBoolean()) {
            int p = rnd.nextInt(ps.size());
            if (!ps.get(p).equals("__VA_ARGS__")) {
                pasted[p] = true;
                return ps.get(p);
            }
        }
        return rnd.nextBoolean() ? "n" + rnd.nextInt(10) : Integer.toString(rnd.nextInt(10));
    }

    /** Arguments for d: a pasted parameter gets a name, a number or nothing; others anything balanced. */
    private String args(Def d, List<String> callerParams) {
        List<String> as = new ArrayList<>();
        int count = d.params + (d.variadic ? rnd.nextInt(3) : 0);
        for (int a = 0; a < count; a++) {
            boolean pasted = a < d.pasted.length && d.pasted[a] || d.variadic && a >= d.params && d.pasted[d.params];
            as.add(pasted ? simple() : arg(callerParams, 2));
        }
        if (d.params == 0 && !d.variadic) {
            return rnd.nextInt(4) == 0 ? " " : "";
        }
        return String.join(rnd.nextBoolean() ? "," : ", ", as);
    }

    private String simple() {
        return switch (rnd.nextInt(3)) {
            case 0 -> "";
            case 1 -> "a" + rnd.nextInt(5);
            default -> Integer.toString(rnd.nextInt(100));
        };
    }

    private String arg(List<String> callerParams, int depth) {
        int c = rnd.nextInt(8);
        if (c == 0) {
            return "";
        }
        if (c == 1 && !callerParams.isEmpty() && !callerParams.contains("__VA_ARGS__")) {
            return callerParams.get(rnd.nextInt(callerParams.size()));
        }
        if (c == 2 && !defs.isEmpty()) {
            return defs.get(rnd.nextInt(defs.size())).name;   // a macro name as an argument
        }
        if (c == 3 && depth > 0) {
            return "(" + arg(callerParams, depth - 1) + ", " + arg(callerParams, depth - 1) + ")";
        }
        if (c == 4 && depth > 0 && !defs.isEmpty()) {
            Def d = defs.get(rnd.nextInt(defs.size()));
            return d.params < 0 ? d.name : d.name + "(" + args(d, callerParams) + ")";
        }
        return new String[] {"a", "b + 1", "-3", "\"t\"", "x[2]", "'q'", "c  d"}[rnd.nextInt(7)];
    }

    /** A line of text that invokes macros, sometimes over several lines. */
    private void use() {
        StringBuilder s = new StringBuilder();
        int n = 1 + rnd.nextInt(4);
        for (int k = 0; k < n && !defs.isEmpty(); k++) {
            Def d = defs.get(rnd.nextInt(defs.size()));
            s.append(d.name);
            if (d.params >= 0 && rnd.nextInt(6) != 0) {
                s.append(rnd.nextInt(5) == 0 ? "\n  (" : "(");
                String a = args(d, List.of());
                s.append(rnd.nextInt(4) == 0 ? a.replaceFirst(",", ",\n    ") : a).append(')');
            }
            s.append(rnd.nextBoolean() ? " ; " : " + ");
        }
        out.append(s).append('\n');
    }

    private void conditional() {
        out.append("#if ").append(expr(3)).append('\n');
        use();
        if (rnd.nextBoolean()) {
            out.append("#elif ").append(expr(2)).append('\n');
            define();
        }
        if (rnd.nextBoolean()) {
            out.append("#else\n");
            use();
        }
        out.append("#endif\n");
    }

    private String expr(int depth) {
        if (depth == 0 || rnd.nextInt(3) == 0) {
            return switch (rnd.nextInt(5)) {
                case 0 -> defs.isEmpty() ? "1" : "defined(" + defs.get(rnd.nextInt(defs.size())).name + ")";
                case 1 -> "UNDEF" + rnd.nextInt(3);
                case 2 -> rnd.nextInt(300) + (rnd.nextBoolean() ? "u" : "");
                case 3 -> "-" + rnd.nextInt(10);
                default -> "0x" + Integer.toHexString(rnd.nextInt(4096));
            };
        }
        String a = expr(depth - 1);
        String b = expr(depth - 1);
        return switch (rnd.nextInt(12)) {
            case 0 -> "(" + a + " + " + b + ")";
            case 1 -> "(" + a + " - " + b + ")";
            case 2 -> "(" + a + " * " + b + ")";
            case 3 -> "(" + a + " / ((" + b + ") | 1))";
            case 4 -> "(" + a + " % ((" + b + ") | 1))";
            case 5 -> "(" + a + " << (" + rnd.nextInt(8) + "))";
            case 6 -> "(" + a + " >> (" + rnd.nextInt(8) + "))";
            case 7 -> "(" + a + " < " + b + ")";
            case 8 -> "(" + a + " == " + b + ")";
            case 9 -> "(" + a + " && " + b + ")";
            case 10 -> "(!" + a + " || " + b + ")";
            default -> "(" + a + " ? " + b + " : " + expr(depth - 1) + ")";
        };
    }
}
