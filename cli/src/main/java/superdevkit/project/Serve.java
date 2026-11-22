package superdevkit.project;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * {@code superdevkit run web}: serves build/web on this machine (127.0.0.1, port 8080 or the next
 * free one) and opens the page in the browser, until Ctrl+C. A browser loads WebAssembly only from
 * a server, with the type application/wasm, not from a file. Nothing is cached, so a new build is
 * there with a reload.
 *
 * <p>The browser: {@code BROWSER} names one (a command that takes the address; {@code BROWSER=true}
 * opens none), else xdg-open on Linux, Windows' default browser on Windows and from WSL.
 */
final class Serve {
    private Serve() {
    }

    static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "wasm", "application/wasm",
            "data", "application/octet-stream",
            "png", "image/png",
            "txt", "text/plain; charset=utf-8");

    static Build.Step step(Project p) {
        Path root = Web.output(p).getParent();
        return new Build.Step("serve", Build.rel(p, root) + " on http://127.0.0.1:8080/ (or the next free port), in the browser",
                List.of(), out -> {
                    HttpServer server = start(root);
                    String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
                    out.println("serving " + Build.rel(p, root) + " at " + url + " (Ctrl+C ends)");
                    open(url, out);
                    new CountDownLatch(1).await();
                });
    }

    /** A server of the folder's files on 127.0.0.1, from port 8080 on. */
    static HttpServer start(Path root) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        for (int port = 8080; ; port++) {
            try {
                HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port < 8100 ? port : 0), 0);
                s.createContext("/", x -> answer(base, x));
                s.start();
                return s;
            } catch (BindException e) {
                if (port >= 8100) {
                    throw e;
                }
            }
        }
    }

    private static void answer(Path base, HttpExchange x) throws IOException {
        try (x) {
            String path = x.getRequestURI().getPath();
            Path f = base.resolve(path.substring(1)).normalize();
            if (path.endsWith("/")) {
                f = f.resolve("index.html");
            }
            if (!x.getRequestMethod().equals("GET") && !x.getRequestMethod().equals("HEAD")) {
                x.sendResponseHeaders(405, -1);
                return;
            }
            if (!f.startsWith(base) || !Files.isRegularFile(f)) {
                x.sendResponseHeaders(404, -1);
                return;
            }
            String name = f.getFileName().toString();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
            x.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
            x.getResponseHeaders().set("Cache-Control", "no-store");
            byte[] body = Files.readAllBytes(f);
            boolean head = x.getRequestMethod().equals("HEAD");
            x.sendResponseHeaders(200, head ? -1 : body.length);
            if (!head) {
                try (OutputStream o = x.getResponseBody()) {
                    o.write(body);
                }
            }
        }
    }

    /** Opens the address in the browser; says so when it cannot. */
    private static void open(String url, PrintStream out) {
        String browser = System.getenv("BROWSER");
        List<String> cmd;
        if (browser != null && !browser.isBlank()) {
            cmd = List.of(browser, url);
        } else if (Desktop.windows()) {
            cmd = List.of("cmd", "/c", "start", "", url);
        } else if (Desktop.wsl()) {
            cmd = List.of("cmd.exe", "/c", "start", "", url);
        } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac")) {
            cmd = List.of("open", url);
        } else {
            cmd = List.of("xdg-open", url);
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
            if (Desktop.wsl() && cmd.getFirst().equals("cmd.exe") && Files.isDirectory(Path.of("/mnt/c"))) {
                pb.directory(new java.io.File("/mnt/c"));   // cmd.exe warns in a folder of WSL's
            }
            pb.start();
        } catch (IOException e) {
            out.println("open " + url + " in a browser (" + cmd.getFirst() + ": " + e.getMessage() + ")");
        }
    }
}
