package superdevkit.project;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import superdevkit.gfx.Png2Snes;

/**
 * The project file and what the command makes of it: the checks of superdevkit.toml, the plan of
 * each command (--plan), init, the symbol check of the desktop and web builds, run web's server.
 */
class ProjectTest {
    @TempDir
    Path tmp;

    private Project project(String toml) throws Exception {
        Files.writeString(tmp.resolve(Project.FILE), toml);
        return Project.read(tmp);
    }

    private String error(String toml) {
        return assertThrows(Project.Error.class, () -> project(toml)).getMessage();
    }

    @Test
    void readsAProject() throws Exception {
        Project p = project("""
                name = "game"
                superdevkit = "0.6"
                sram = 16
                sources = ["src", "extra/one.c"]
                [graphics]
                tiles = ["a/sheet.png", { png = "a/boss.png", name = "boss_gfx" }]
                mode7 = [{ png = "a/m7.png", first = 33 }]
                palette = ["a/pal.png"]
                [sound]
                sfx = "a/sfx/sfx.json"
                music = ["a/music/title.ogg", { file = "a/music/1-boss.xm", name = "boss" }]
                """);
        assertEquals("game", p.name());
        assertEquals(16, p.sram());
        assertEquals(List.of(new Project.Graphic(Png2Snes.Mode.TILES, "a/sheet.png", "sheet", 1),
                new Project.Graphic(Png2Snes.Mode.TILES, "a/boss.png", "boss_gfx", 1),
                new Project.Graphic(Png2Snes.Mode.PALETTE, "a/pal.png", "pal", 1),
                new Project.Graphic(Png2Snes.Mode.MODE7, "a/m7.png", "m7", 33)), p.graphics());
        assertEquals("a/sfx/sfx.json", p.sfx());
        assertEquals(List.of(new Project.Track("a/music/title.ogg", "title"), new Project.Track("a/music/1-boss.xm", "boss")),
                p.music());
        assertEquals(List.of(tmp.resolve("src"), tmp.resolve("extra")), p.includeDirs());
    }

    @Test
    void refusesWhatItDoesNotTake() {
        String base = "name = \"g\"\nsuperdevkit = \"0.6\"\nsources = [\"src\"]\n";
        assertTrue(error(base + "colour = 1\n").contains("unknown key 'colour'"));
        assertTrue(error(base.replace("superdevkit = \"0.6\"\n", "")).contains("'superdevkit'"));
        assertTrue(error(base.replace("0.6", "six")).contains("two or three numbers"));
        assertTrue(error(base + "sram = 40000\n").contains("0 to 32768"));
        assertTrue(error(base.replace("[\"src\"]", "[]")).contains("'sources'"));
        assertTrue(error(base + "[graphics]\ntiles = [\"a/x-y.png\"]\n").contains("no C name"));
        assertTrue(error(base + "[graphics]\ntiles = [\"a/x.png\"]\npalette = [\"b/x.png\"]\n").contains("two pictures are called x"));
        assertTrue(error(base + "[graphics]\nmode7 = [{ png = \"m.png\", first = 0 }]\n").contains("1 to 255"));
        assertTrue(error(base + "[sound]\nmusic = 1\n").contains("music is an array"));
        assertTrue(error(base + "[sound]\nmusic = [\"a/x.ogg\", \"b/x.wav\"]\n").contains("two tracks are called x"));
        assertTrue(error(base + "[sound]\nmusic = [\"a/1.ogg\"]\n").contains("no C name"));
        assertTrue(error(base + "[sound]\nvoices = 2\n").contains("unknown key 'voices'"));
        assertTrue(error("name = \"g g\"\n").contains("'name'"));
    }

    private static String run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        int status = superdevkit.MainAccess.run(args, new PrintStream(out), new PrintStream(err));
        return status + "\n" + out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theSnesPlanConvertsThenCompiles() {
        String plan = run("build", "snes", "examples/catchme", "--plan");
        assertTrue(plan.startsWith("0\n"), plan);
        assertTrue(plan.contains("png    tiles assets/maze_gfx.png maze_gfx build/gen"), plan);
        assertTrue(plan.contains("png    palette assets/pal_hot.png pal_hot build/gen"), plan);
        assertTrue(plan.contains("--entry main --sram 8 -o build/snes/catchme.sfc src build/gen/"), plan);
        assertTrue(plan.contains("write  build/compile_commands.json"), plan);
        assertTrue(plan.contains("music  1 tracks: build/gen/music.h"), plan);
        assertTrue(plan.contains("everything the build needs is there"), plan);
        assertTrue(plan.indexOf("png    tiles") < plan.indexOf("dampf  build"), plan);
    }

    @Test
    void theDesktopPlanCompilesEachFileChecksAndLinks() {
        assumeTrue(!System.getProperty("os.name", "").toLowerCase().startsWith("windows"));
        String plan = run("build", "linux", "examples/catchme", "--plan");
        assertTrue(plan.startsWith("0\n"), plan);
        assertTrue(plan.contains("-nostdinc -ffreestanding -fno-tree-loop-distribute-patterns"), plan);
        assertTrue(plan.contains("-c src/game.c -o build/linux/obj/src_game.o"), plan);
        assertTrue(plan.contains("check  the game's objects call nothing but lib/include/sdevkit"), plan);
        assertTrue(plan.contains("-DGAME_NAME=\"catchme\""), plan);
        assertTrue(plan.contains("-o build/linux/catchme"), plan);
        assertTrue(plan.contains("music  1 tracks into build/linux/music"), plan);
        assertTrue(plan.contains("build/linux/obj/music_files.c"), plan);
        String run = run("run", "linux", "examples/catchme", "--plan");
        assertTrue(run.contains("start  ") && run.contains("examples/catchme/build/linux/catchme"), run);
        String snes = run("run", "snes", "examples/catchme", "--plan");
        assertTrue(snes.contains("start  ") && snes.contains("build/snes/catchme.sfc"), snes);
    }

    @Test
    void theWebPlanCompilesEachFileChecksAndLinksWithEmcc() {
        String plan = run("build", "web", "examples/catchme", "--plan");
        assertTrue(plan.startsWith("0\n"), plan);
        assertTrue(plan.contains("raylib 5.5 for web in "), plan);
        assertTrue(plan.contains("-nostdinc -ffreestanding -isystem "), plan);
        assertTrue(plan.contains("-c src/game.c -o build/web/obj/src_game.o"), plan);
        assertTrue(plan.contains("check  the game's objects call nothing but lib/include/sdevkit"), plan);
        assertTrue(plan.contains("lib/web/web.c"), plan);
        assertTrue(plan.contains("-sASYNCIFY") && plan.contains("-lidbfs.js"), plan);
        assertTrue(plan.contains("--preload-file build/web/music@/music"), plan);
        assertTrue(plan.contains("-o build/web/index.html"), plan);
        assertTrue(plan.indexOf("check  ") < plan.indexOf("-o build/web/index.html"), plan);
        String run = run("run", "web", "examples/catchme", "--plan");
        assertTrue(run.contains("serve  build/web on http://127.0.0.1:8080/"), run);
        // the debug view only with --debug (ADR-6FD7KEJV)
        assertTrue(!plan.contains("SDEVKIT_WEB_DEBUG") && plan.contains(" -O2 "), plan);
        String debug = run("build", "web", "examples/catchme", "--plan", "--debug");
        assertTrue(debug.lines().anyMatch(l -> l.contains("-c src/game.c") && l.contains(" -O0 ") && l.contains(" -DSDEVKIT_WEB_DEBUG ")),
                debug);   // the game's files too
        assertTrue(debug.lines().anyMatch(l -> l.contains("-o build/web/index.html") && l.contains(" -DSDEVKIT_WEB_DEBUG ")), debug);
    }

    /** run web's server: the page's files with their types, nothing outside the folder. */
    @Test
    void theServerServesTheFolderWithTheTypesABrowserWants() throws Exception {
        Path web = tmp.resolve("web");
        Files.createDirectories(web);
        Files.writeString(web.resolve("index.html"), "<p>hi</p>");
        Files.write(web.resolve("index.wasm"), new byte[] { 0, 'a', 's', 'm' });
        Files.writeString(tmp.resolve("secret.txt"), "no");
        com.sun.net.httpserver.HttpServer server = Serve.start(web);
        try {
            java.net.http.HttpClient c = java.net.http.HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            var page = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base)).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertEquals("<p>hi</p>", page.body());
            assertEquals("text/html; charset=utf-8", page.headers().firstValue("Content-Type").orElse(""));
            var wasm = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "index.wasm")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            assertEquals("application/wasm", wasm.headers().firstValue("Content-Type").orElse(""));
            assertEquals("no-store", wasm.headers().firstValue("Cache-Control").orElse(""));
            var out = c.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "..%2Fsecret.txt")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(404, out.statusCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aMissingFileIsNamedByTheDryRun() throws Exception {
        Files.createDirectories(tmp.resolve("src"));
        project("""
                name = "g"
                kit = "%s"
                sources = ["src"]
                [graphics]
                tiles = ["assets/gone.png"]
                """.formatted(Path.of("").toAbsolutePath().toString().replace("\\", "/")));
        String out = run("build", "snes", tmp.toString(), "--dry-run");
        assertTrue(out.startsWith("1\n") && out.contains("missing: ") && out.contains("gone.png"), out);
    }

    /** A small game whose second file only a command before the build writes. */
    private void gameWithACommandBefore(String script) throws Exception {
        assumeTrue(!Desktop.windows(), "the test's command is a shell script");
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/g.c"), """
                #include "sdevkit/loop.h"
                uint8_t made(void);
                uint8_t v;
                void game_init(uint16_t seed) { (void)seed; }
                void game_tick(const uint16_t pad[SDEVKIT_PADS]) { (void)pad; v = made(); }
                void game_vblank(void) { }
                """);
        Files.writeString(tmp.resolve("make.sh"), script);
        project("""
                name = "g"
                kit = "%s"
                sources = ["src", "made"]
                include = ["src"]
                [build]
                before = ["sh make.sh"]
                """.formatted(Path.of("").toAbsolutePath().toString().replace("\\", "/")));
    }

    private static final String WRITES_THE_FILE = """
            mkdir -p made
            printf '#include <stdint.h>\\nuint8_t made(void) { return 7; }\\n' > made/more.c
            echo converted
            """;

    @Test
    void theCommandsBeforeTheBuildRunFirstAndCanWriteItsSources() throws Exception {
        gameWithACommandBefore(WRITES_THE_FILE);
        String out = run("build", "snes", tmp.toString(), "--verbose");
        assertTrue(out.startsWith("0\n"), out);
        assertTrue(out.indexOf("before sh make.sh") < out.indexOf("dampf  build"), out);
        assertTrue(out.contains("converted"), out);
        assertTrue(Files.isRegularFile(tmp.resolve("made/more.c")));
        assertTrue(Files.size(tmp.resolve("build/snes/g.sfc")) >= 32 * 1024);
    }

    @Test
    void aPlanShowsTheCommandsAndRunsNothing() throws Exception {
        gameWithACommandBefore(WRITES_THE_FILE);
        for (String option : List.of("--plan", "--dry-run")) {
            String out = run("build", "snes", tmp.toString(), option);
            assertTrue(out.startsWith("0\n") && !out.contains("missing:"), out);
            assertTrue(!Files.exists(tmp.resolve("made")), "a " + option + " ran the command");
        }
        String plan = run("build", "linux", tmp.toString(), "--plan");
        assertTrue(plan.contains("before sh make.sh"), plan);
    }

    @Test
    void aCommandThatFailsStopsTheBuild() throws Exception {
        gameWithACommandBefore("echo no levels\nexit 3\n");
        String out = run("build", "snes", tmp.toString());
        assertTrue(out.startsWith("1\n") && out.contains("'sh make.sh' ended with status 3") && out.contains("no levels"), out);
        assertTrue(!Files.exists(tmp.resolve("build/snes/g.sfc")));
    }

    @Test
    void aSourceTheCommandsDidNotWriteIsAnError() throws Exception {
        gameWithACommandBefore("true\n");
        String out = run("build", "snes", tmp.toString());
        assertTrue(out.startsWith("1\n") && out.contains("no such file or folder") && out.contains("made"), out);
    }

    /** A copy of an example, without its build folder, that names this repository as its kit. */
    private Path copyOfExample(String name) throws Exception {
        Path from = Path.of("examples", name);
        Path to = tmp.resolve(name);
        try (var files = Files.walk(from)) {
            for (Path f : files.filter(x -> !from.relativize(x).startsWith("build")).toList()) {
                Path t = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(f, t);
                }
            }
        }
        Path toml = to.resolve(Project.FILE);
        String kit = Path.of("").toAbsolutePath().toString().replace("\\", "/");
        Files.writeString(toml, Files.readString(toml).replaceFirst("(?m)^kit\\s*=.*$", "kit = \"" + kit + "\""));
        return to;
    }

    @Test
    void genWritesTheConversionsAndNothingElse() throws Exception {
        Path game = copyOfExample("catchme");
        String out = run("gen", game.toString());
        assertTrue(out.startsWith("0\n") && out.contains("generated build/gen"), out);
        assertTrue(Files.isRegularFile(game.resolve("build/gen/maze_gfx.c")), "the pictures");
        assertTrue(Files.isRegularFile(game.resolve("build/gen/music.h")), "the tracks' numbers");
        String list = Files.readString(game.resolve("build/gen/music/music_files.c"));
        assertTrue(list.contains("sdevkit__music_files") && list.contains("\"music/"), list);
        try (var files = Files.list(game.resolve("build/gen/music"))) {
            assertTrue(files.count() >= 2, "the tracks beside their list");
        }
        assertTrue(!Files.exists(game.resolve("build/snes")), "gen compiles nothing");
    }

    @Test
    void aBuildAfterGenGivesTheSameRom() throws Exception {
        Path game = copyOfExample("catchme");
        assertTrue(run("build", "snes", game.toString()).startsWith("0\n"));
        byte[] plain = Files.readAllBytes(game.resolve("build/snes/catchme.sfc"));
        assertTrue(run("gen", game.toString()).startsWith("0\n"));
        assertTrue(run("build", "snes", game.toString()).startsWith("0\n"));
        assertArrayEquals(plain, Files.readAllBytes(game.resolve("build/snes/catchme.sfc")),
                "the frontend's music list in build/gen/music must not be compiled into the ROM");
    }

    @Test
    void genRunsTheCommandsBeforeItAndItsPlanRunsNothing() throws Exception {
        gameWithACommandBefore(WRITES_THE_FILE);
        String plan = run("gen", tmp.toString(), "--plan");
        assertTrue(plan.startsWith("0\n") && plan.contains("before sh make.sh"), plan);
        assertTrue(!Files.exists(tmp.resolve("made")));
        String out = run("gen", tmp.toString());
        assertTrue(out.startsWith("0\n") && out.contains("nothing to generate"), out);
        assertTrue(Files.isRegularFile(tmp.resolve("made/more.c")));
    }

    @Test
    void definesReachTheGameOnTheirTargetOnly() throws Exception {
        assumeTrue(!Desktop.windows(), "the plan of linux");
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/g.c"), """
                #include "sdevkit/loop.h"
                #if !defined(ON_SNES) || LEVELS != 3 || defined(ON_DESKTOP)
                #error the defines of [snes] did not arrive
                #endif
                void game_init(uint16_t seed) { (void)seed; }
                void game_tick(const uint16_t pad[SDEVKIT_PADS]) { (void)pad; }
                void game_vblank(void) { }
                """);
        project("""
                name = "g"
                kit = "%s"
                sources = ["src"]
                [snes]
                defines = ["ON_SNES", "LEVELS=3"]
                [desktop]
                defines = ["ON_DESKTOP"]
                [web]
                defines = ["ON_WEB=1"]
                """.formatted(Path.of("").toAbsolutePath().toString().replace("\\", "/")));
        String snes = run("build", "snes", tmp.toString(), "--plan");
        assertTrue(snes.contains(" -D ON_SNES -D LEVELS=3 --entry main") && !snes.contains("ON_DESKTOP"), snes);
        String built = run("build", "snes", tmp.toString());
        assertTrue(built.startsWith("0\n"), built);
        assertTrue(Files.readString(tmp.resolve("build/snes/g.args")).contains("-D LEVELS=3\n"));
        String linux = run("build", "linux", tmp.toString(), "--plan");
        assertTrue(linux.lines().anyMatch(l -> l.contains("-c src/g.c") && l.contains(" -DON_DESKTOP ")), linux);
        assertTrue(linux.lines().noneMatch(l -> l.contains("lib/rachael") && l.contains("ON_DESKTOP")), "only the game's files: " + linux);
        String web = run("build", "web", tmp.toString(), "--plan");
        assertTrue(web.lines().anyMatch(l -> l.contains("-c src/g.c") && l.contains(" -DON_WEB=1 ")), web);
        assertTrue(web.lines().noneMatch(l -> l.contains("lib/rachael") && l.contains("ON_WEB")), "only the game's files: " + web);
    }

    @Test
    void theDefinesOfSnesDoNotReachTheKitsLibrary() throws Exception {
        // SETUP_LINES is a macro lib/snes/snes.c takes if it is defined (the margin of sdevkit_dma_fits)
        Files.createDirectories(tmp.resolve("src"));
        Files.writeString(tmp.resolve("src/g.c"), """
                #include "sdevkit/loop.h"
                #ifndef ON_SNES
                #error the defines of [snes] did not arrive
                #endif
                void game_init(uint16_t seed) { (void)seed; }
                void game_tick(const uint16_t pad[SDEVKIT_PADS]) { (void)pad; }
                void game_vblank(void) { }
                """);
        String toml = """
                name = "g"
                kit = "%s"
                sources = ["src"]
                [snes]
                defines = [%s]
                """;
        String kitPath = Path.of("").toAbsolutePath().toString().replace("\\", "/");
        Path rom = tmp.resolve("build/snes/g.sfc");
        project(toml.formatted(kitPath, "\"ON_SNES\""));
        assertTrue(run("build", "snes", tmp.toString()).startsWith("0\n"));
        byte[] without = Files.readAllBytes(rom);
        Project p = project(toml.formatted(kitPath, "\"ON_SNES\", \"SETUP_LINES=0\""));
        String plan = run("build", "snes", tmp.toString(), "--plan");
        assertTrue(plan.contains(" -D ON_SNES -D SETUP_LINES=0 --entry main") && plan.contains(" src --lib "), plan);
        String built = run("build", "snes", tmp.toString());
        assertTrue(built.startsWith("0\n"), built);
        assertArrayEquals(without, Files.readAllBytes(rom), "SETUP_LINES of [snes] must not reach lib/snes");

        // the same define does change the library when it reaches it, as it did before --lib
        Kit kit = Kit.of(p);
        Path leak = tmp.resolve("leak/g.sfc");
        dampf.Compiler.compile(Build.dampfOptions(p, kit, leak).withLibrary(List.of()));
        assertTrue(!java.util.Arrays.equals(without, Files.readAllBytes(leak)), "SETUP_LINES=0 should change lib/snes");

        String args = Files.readString(tmp.resolve("build/snes/g.args"));
        String snesC = kit.lib().resolve("snes/snes.c").toAbsolutePath().normalize().toString();
        assertTrue(args.contains("-D SETUP_LINES=0\n") && args.contains("\n--lib " + snesC + "\n")
                && args.contains("\n" + tmp.resolve("src/g.c").toAbsolutePath().normalize() + "\n"), args);
        // compile_commands.json: the game's file with the define, nothing of lib/snes
        String commands = Files.readString(tmp.resolve("build/compile_commands.json"));
        assertTrue(commands.contains("g.c\", \"arguments\"") && commands.contains("\"-DSETUP_LINES=0\""), commands);
        assertTrue(!commands.contains("snes.c"), commands);
    }

    @Test
    void aDefineWithASpaceIsRefused() {
        assertTrue(error("""
                name = "g"
                superdevkit = "0.9"
                sources = ["src"]
                [desktop]
                defines = ["A B"]
                """).contains("NAME=VALUE"));
    }

    @Test
    void anUnknownKeyOfBuildIsRefused() {
        assertTrue(error("""
                name = "g"
                superdevkit = "0.9"
                sources = ["src"]
                [build]
                after = ["x"]
                """).contains("after"));
    }

    @Test
    void anUnknownTargetIsRefused() {
        String out = run("build", "amiga", "examples/catchme");
        assertTrue(out.startsWith("2\n") && out.contains("no target 'amiga'"), out);
    }

    @Test
    void initCopiesAnExampleWithItsNameAndTheKitsVersion() throws Exception {
        Path home = Path.of("build/install/superdevkit").toAbsolutePath();
        assumeTrue(Files.isRegularFile(home.resolve("examples/catchme/superdevkit.toml")), "an installed kit with the examples");
        String old = System.getProperty("dampf.include");
        System.setProperty("dampf.include", home.resolve("include").toString());
        try {
            assertEquals(0, Init.run("mygame", "catchme", tmp, false, new PrintStream(new ByteArrayOutputStream())));
        } finally {
            if (old == null) {
                System.clearProperty("dampf.include");
            } else {
                System.setProperty("dampf.include", old);
            }
        }
        Path p = tmp.resolve("mygame");
        String toml = Files.readString(p.resolve(Project.FILE));
        assertTrue(toml.contains("name = \"mygame\""), toml);
        assertTrue(toml.contains("superdevkit = \"" + Project.major(dampf.Main.version()) + "\""), toml);
        assertTrue(!toml.matches("(?s).*(?m:^kit\\s*=).*"), toml);
        assertTrue(Files.isRegularFile(p.resolve("src/game.c")));
        assertTrue(!Files.exists(p.resolve("build")));
        String claude = Files.readString(p.resolve("CLAUDE.md"));
        assertTrue(claude.contains("lib/C_STYLE.md") && claude.contains("lib/SNES_HARDWARE.md"), claude);
        Project read = Project.read(p);
        assertEquals("mygame", read.name());
        assertThrows(Project.Error.class, () -> Init.run("mygame", "catchme", tmp, false, System.out));   // there already
    }

    /** An object that calls puts is caught; one that calls only the kit's API passes. ELF, of cc. */
    @Test
    void theSymbolCheckCatchesWhatIsNotTheKits() throws Exception {
        symbolCheck(List.of("cc"));
    }

    /** The same for WebAssembly objects, of emcc (the target web); skipped without Emscripten. */
    @Test
    void theSymbolCheckReadsWebAssemblyObjects() throws Exception {
        symbolCheck(Web.emcc());
    }

    private static boolean runs(List<String> cmd) {
        try {
            List<String> v = new java.util.ArrayList<>(cmd);
            v.add("--version");
            Process p = new ProcessBuilder(v).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (java.io.IOException | InterruptedException e) {
            return false;
        }
    }

    private void symbolCheck(List<String> cc) throws Exception {
        assumeTrue(runs(cc), "no " + String.join(" ", cc));
        Path good = tmp.resolve("good.c"), bad = tmp.resolve("bad.c");
        Files.writeString(good, "#include \"sdevkit/snes.h\"\nunsigned char shared;\nvoid f(void) { sdevkit_reg_write(SDEVKIT_PPU_INIDISP, 15); shared++; }\n");
        Files.writeString(bad, "int puts(const char *s);\nextern unsigned char shared;\nvoid g(void) { puts(\"hi\"); shared++; }\n");
        for (Path c : List.of(good, bad)) {
            List<String> cmd = new java.util.ArrayList<>(cc);
            cmd.addAll(List.of("-c", "-I", Path.of("lib/include").toAbsolutePath().toString(), c.toString(),
                    "-o", c.toString().replace(".c", ".o")));
            Process pr = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(pr.getInputStream().readAllBytes());
            assertTrue(pr.waitFor(60, TimeUnit.SECONDS) && pr.exitValue() == 0, log);
        }
        Path api = Path.of("lib/include/sdevkit");
        assertEquals(List.of(), Symbols.check(api, List.of(tmp.resolve("good.o"))));
        List<String> found = Symbols.check(api, List.of(tmp.resolve("good.o"), tmp.resolve("bad.o")));
        assertEquals(1, found.size(), found.toString());
        assertTrue(found.getFirst().contains("bad.o: uses puts"), found.toString());
    }
}
