package dampf.frontend.pp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.function.ThrowingConsumer;

/**
 * Dynamic tests whose checks run side by side, one thread per core: each check mostly waits for a
 * process of its own (clang), so they all start at once and each test reports the result of its
 * own check, as if it had run alone.
 */
final class Concurrently {
    private Concurrently() {}

    static <T> Stream<DynamicTest> tests(List<T> items, Function<T, String> name, ThrowingConsumer<T> check) {
        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        List<Future<Throwable>> results = new ArrayList<>();
        for (T item : items) {
            results.add(pool.submit(() -> {
                try {
                    check.accept(item);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        pool.shutdown();
        List<DynamicTest> tests = new ArrayList<>();
        for (int k = 0; k < items.size(); k++) {
            Future<Throwable> r = results.get(k);
            tests.add(DynamicTest.dynamicTest(name.apply(items.get(k)), () -> {
                Throwable t = r.get();
                if (t != null) {
                    throw t;
                }
            }));
        }
        return tests.stream();
    }
}
