#!/usr/bin/env python3
"""M0 subset scanner.

Parses C files with clang (JSON AST) and reports which C constructs they use,
so the dampf subset can be derived from real code. M0 tooling: the subset is
now enforced by dampf itself (dampf check); this stays for scanning other code.

Usage:
    tools/subset_scan.py [--details] -I<dir> ... file.c ...

Only nodes located in the given files (or headers next to them) are counted;
system headers are ignored.
"""
import collections
import json
import os
import re
import subprocess
import sys

FIXED = re.compile(r'\b(u?int(8|16|32)_t|fx8_8)\b')
PLAIN = re.compile(r'\b(int|short|long|unsigned|signed|char|_Bool)\b')


class Scan:
    def __init__(self, roots):
        self.roots = roots
        self.count = collections.Counter()
        self.where = collections.defaultdict(list)
        self.cur_file = None
        self.cur_line = 0
        self.locals = set()

    # --- helpers -----------------------------------------------------------
    def note(self, key, detail=''):
        self.count[key] += 1
        self.where[key].append(f'{os.path.basename(self.cur_file or "?")}:{self.cur_line} {detail}'.rstrip())

    def track_loc(self, node):
        for loc in (node.get('loc', {}), node.get('range', {}).get('begin', {})):
            if 'expansionLoc' in loc:
                loc = loc['expansionLoc']
            if 'file' in loc:
                self.cur_file = loc['file']
            if 'line' in loc:
                self.cur_line = loc['line']

    def mine(self):
        f = self.cur_file or ''
        return any(os.path.abspath(f).startswith(r) for r in self.roots)

    @staticmethod
    def qt(node):
        return node.get('type', {}).get('qualType', '')

    @staticmethod
    def strip_casts(node):
        while node.get('kind') in ('ImplicitCastExpr', 'ParenExpr') and node.get('inner'):
            node = node['inner'][0]
        return node

    @staticmethod
    def const_value(node):
        n = Scan.strip_casts(node)
        if n.get('kind') == 'IntegerLiteral':
            return int(n['value'])
        if n.get('kind') == 'ConstantExpr' and 'value' in n:
            return int(n['value'])
        return None

    @staticmethod
    def is_constant(node):
        """True if the expression reads no variable and calls nothing (folds at compile time)."""
        if node.get('kind') == 'CallExpr':
            return False
        if node.get('kind') == 'DeclRefExpr':
            return node.get('referencedDecl', {}).get('kind') == 'EnumConstantDecl'
        return all(Scan.is_constant(c) for c in node.get('inner', []) or [])

    def source_type(self, node):
        """Type of an operand before the integer promotions."""
        n = node
        while n.get('kind') in ('ImplicitCastExpr', 'ParenExpr') and n.get('inner'):
            if n.get('kind') == 'ImplicitCastExpr' and n.get('castKind') == 'IntegralCast':
                n = n['inner'][0]
                return self.qt(n)
            n = n['inner'][0]
        return self.qt(n)

    def decl_type(self, kind, t, name):
        if '(' in t:  # function type: check return and params separately
            return
        if PLAIN.search(FIXED.sub('', t)) and 'char' not in t:
            self.note('plain integer type in declaration', f'{kind} {name}: {t}')
        if 'char' in t:
            self.note('char type', f'{kind} {name}: {t}')
        if t.startswith('enum ') or (t and t[0].isupper() and 'struct' not in t and '*' not in t and '[' not in t
                                     and t in self.enum_typedefs):
            self.note('enum-typed object', f'{kind} {name}: {t}')
        if re.search(r'\]\s*\[', t):
            self.note('multi-dimensional array', f'{name}: {t}')
        if 'u?int32' in t or re.search(r'\bu?int32_t\b', t):
            self.note('32-bit object', f'{kind} {name}: {t}')

    # --- walk --------------------------------------------------------------
    def walk(self, node, func=None):
        self.track_loc(node)
        k = node.get('kind')
        mine = self.mine()
        if node.get('isImplicit'):
            return
        if mine:
            self.visit(node, k, func)
        if k == 'FunctionDecl' and node.get('inner'):
            func = node['name']
        for c in node.get('inner', []) or []:
            self.walk(c, func)
            # restore location context of the parent for later siblings
        # end

    def visit(self, n, k, func):
        t = self.qt(n)
        if k == 'TypedefDecl':
            under = n.get('type', {}).get('qualType', '')
            if under.startswith('enum'):
                self.enum_typedefs.add(n['name'])
            return
        if k == 'FunctionDecl':
            if n.get('inner') and any(c.get('kind') == 'CompoundStmt' for c in n['inner']):
                self.note('function definition', n['name'])
                params = [c for c in n['inner'] if c.get('kind') == 'ParmVarDecl']
                self.count['max params'] = max(self.count['max params'], len(params))
                ret = t.split('(')[0].strip()
                if ret.startswith('struct') or (ret and ret[0].isupper() and '*' not in ret and ret not in self.enum_typedefs):
                    self.note('struct returned by value?', f'{n["name"]}: {ret}')
                if n.get('variadic'):
                    self.note('variadic function', n['name'])
                if n.get('inline'):
                    self.note('inline function', n['name'])
            return
        if k == 'ParmVarDecl':
            self.decl_type('param', t, n.get('name', '?'))
            if (t.startswith('struct') or (t[:1].isupper() and '*' not in t and t not in self.enum_typedefs)) and '[' not in t:
                self.note('struct parameter by value?', f'{n.get("name")}: {t}')
            if '*' in t:
                self.note('pointer parameter', f'{func}({n.get("name")}: {t})')
            return
        if k == 'VarDecl':
            name = n.get('name', '?')
            self.decl_type('var', t, name)
            if func and n.get('storageClass') == 'static':
                self.note('static local', f'{func}.{name}: {t}')
            if func and n.get('storageClass') != 'static':
                self.locals.add(n.get('id'))
            if '*' in t and '(*)' not in t:
                self.note('pointer object', f'{name}: {t}')
            if '(*' in t:
                self.note('function pointer', f'{name}: {t}')
            return
        if k == 'FieldDecl':
            self.decl_type('field', t, n.get('name', '?'))
            if n.get('isBitfield'):
                self.note('bitfield', n.get('name'))
            if '*' in t:
                self.note('pointer field', f'{n.get("name")}: {t}')
            return
        if k == 'RecordDecl':
            if n.get('tagUsed') == 'union':
                self.note('union', n.get('name', '<anon>'))
            elif n.get('completeDefinition'):
                self.note('struct definition', n.get('name', '<anon>'))
            return
        if k == 'EnumDecl':
            self.note('enum definition', n.get('name', '<anon>'))
            return
        if k == 'SwitchStmt':
            cases = sum(1 for _ in self.iter_kind(n, 'CaseStmt'))
            self.note('switch', f'{func}: {cases} cases')
            return
        if k in ('ForStmt', 'WhileStmt', 'DoStmt'):
            self.note('loop ' + k[:-4].lower(), func)
            return
        if k in ('GotoStmt', 'LabelStmt', 'IndirectGotoStmt'):
            self.note('goto/label', func)
            return
        if k == 'ConditionalOperator':
            self.note('?: operator')
            return
        if k == 'StringLiteral':
            self.note('string literal')
            return
        if k == 'CompoundLiteralExpr':
            self.note('compound literal', func)
            return
        if k == 'DesignatedInitExpr':
            self.note('designated initializer', func)
            return
        if k == 'GCCAsmStmt':
            self.note('inline asm', func)
            return
        if k == 'UnaryExprOrTypeTraitExpr':
            self.note('sizeof', func)
            return
        if k == 'CallExpr':
            callee = self.strip_casts(n['inner'][0])
            if callee.get('kind') != 'DeclRefExpr':
                self.note('indirect call', func)
            return
        if k == 'UnaryOperator':
            op = n.get('opcode')
            if op == '&':
                target = self.strip_casts(n['inner'][0])
                ref = target.get('referencedDecl', {}) if target.get('kind') == 'DeclRefExpr' else {}
                if ref.get('id') in self.locals:
                    self.note('address of local', f'{func}: &{ref.get("name")}')
                elif ref.get('kind') == 'FunctionDecl':
                    self.note('address of function', f'{func}: &{ref.get("name")}')
                else:
                    self.note('address of other', f'{func}')
            if op in ('++', '--') and '*' in t:
                self.note('pointer increment', func)
            return
        if k == 'MemberExpr':
            self.note('member ->' if n.get('isArrow') else 'member .')
            return
        if k == 'ArraySubscriptExpr':
            self.note('array subscript')
            return
        if k in ('BinaryOperator', 'CompoundAssignOperator'):
            op = n.get('opcode')
            lhs, rhs = n['inner'][0], n['inner'][1]
            lt, rt = self.source_type(lhs), self.source_type(rhs)
            lc, rc = self.const_value(lhs), self.const_value(rhs)
            if self.is_constant(lhs) and self.is_constant(rhs) and op not in ('=',):
                self.note('constant expression (folds)')
                return
            if op == '=' and (t.startswith('struct') or (t[:1].isupper() and t not in self.enum_typedefs and '*' not in t)):
                self.note('struct assignment', f'{func}: {t}')
            if op in ('+', '-') and ('*' in self.qt(lhs) or '*' in self.qt(rhs)):
                self.note('pointer arithmetic', func)
            if op in ('/', '%', '/=', '%='):
                signed = not lt.startswith('u') and not lt.startswith('unsigned')
                kind = 'const' if rc is not None else 'variable'
                pow2 = rc is not None and rc > 0 and rc & (rc - 1) == 0
                self.note(f'{op[0]} by {kind}{" power of 2" if pow2 else ""}, {"signed" if signed else "unsigned"} dividend',
                          f'{func}: {lt} {op} {rt if rc is None else rc}')
            if op in ('*', '*='):
                if lc is None and rc is None:
                    self.note('* variable x variable', f'{func}: {lt} * {rt}')
                else:
                    self.note('* by constant', f'{func}: {lt} * {rc if rc is not None else lc}')
            if op in ('>>', '>>='):
                signed = lt.startswith('int') or lt.startswith('fx8') or lt == 'int'
                self.note(f'>> {"signed" if signed else "unsigned"} by {"const" if rc is not None else "variable"}',
                          f'{func}: {lt} >> {rc if rc is not None else rt}')
            if op in ('<<', '<<=') and rc is None:
                self.note('<< by variable', f'{func}: {lt} << {rt}')
            if op == ',':
                self.note('comma operator', func)
            if re.search(r'\bu?int32_t\b', lt + rt) and op not in ('=', ',', '&&', '||'):
                self.note('32-bit operation', f'{func}: {lt} {op} {rt}')
            return
        if k == 'CStyleCastExpr':
            self.note('cast')
            return

    def iter_kind(self, node, kind):
        if node.get('kind') == kind:
            yield node
        for c in node.get('inner', []) or []:
            yield from self.iter_kind(c, kind)


def main(argv):
    details = '--details' in argv
    args = [a for a in argv if a != '--details']
    flags = [a for a in args if a.startswith('-')]
    files = [a for a in args if not a.startswith('-')]
    roots = sorted({os.path.abspath(os.path.dirname(f)) for f in files})
    scan = Scan(roots)
    scan.enum_typedefs = set()
    seen_headers = set()
    for f in files:
        out = subprocess.run(['clang', '-std=c99', '-fsyntax-only', '-Xclang', '-ast-dump=json', *flags, f],
                             capture_output=True, text=True)
        if out.returncode != 0:
            sys.stderr.write(out.stderr)
            sys.exit(1)
        ast = json.loads(out.stdout)
        scan.cur_file = f
        scan.locals = set()
        for top in ast.get('inner', []):
            scan.track_loc(top)
            # headers are parsed once per file: count each header declaration only once
            key = (scan.cur_file, scan.cur_line, top.get('kind'), top.get('name'))
            if scan.cur_file and os.path.abspath(scan.cur_file) != os.path.abspath(f):
                if key in seen_headers:
                    continue
                seen_headers.add(key)
            scan.walk(top)
    for key in sorted(scan.count):
        print(f'{scan.count[key]:6}  {key}')
        if details and key != 'max params':
            for w in scan.where[key][:400]:
                print(f'          {w}')


if __name__ == '__main__':
    main(sys.argv[1:])
