package com.equity.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The console has to reach the classpath by every build path, not just one.
 *
 * <p>It first built into {@code target/classes/static}, which keeps generated files out of the
 * source tree and is wrong for a different reason: {@code target/classes} is written by anything
 * that compiles, and an IDE build does that without ever running the Maven phase that produced the
 * console. The result was a packaged jar that worked and a dev run that returned 404 for every page,
 * three times, with nothing in the logs to explain it.</p>
 *
 * <p>It is now an ordinary resource in {@code src/main/resources/static}, git-ignored and built by
 * vite. Maven copies it, the IDE copies it, {@code spring-boot:run} copies it.</p>
 */
class ConsoleBundlingTest {

    private static final Path SOURCE_CONSOLE = Path.of("src", "main", "resources", "static");
    private static final Path COMPILED_CONSOLE = Path.of("target", "classes", "static");

    /**
     * Skipped when the console has not been built — {@code -Dskip.ui=true} is supported, and a test
     * that failed for that reason would be failing for the wrong one.
     */
    @Test
    void theBuiltConsoleIsAnOrdinaryResourceSoEveryBuildPathCopiesIt() {
        if (!Files.exists(SOURCE_CONSOLE.resolve("index.html"))) return;

        assertThat(COMPILED_CONSOLE.resolve("index.html"))
                .as("the console is in src/main/resources but did not reach target/classes — "
                        + "whatever compiled this did not copy resources, and every page will 404")
                .exists();
        assertThat(COMPILED_CONSOLE.resolve("assets"))
                .as("vite emits hashed bundles into assets/; without them index.html loads nothing")
                .exists();
    }

    /**
     * The console must not be committed.
     *
     * <p>It moved into the source tree to survive every build path, which brings the risk it moved
     * away from: a generated bundle checked in, then served stale for as long as nobody notices.
     * The ignore rule is the thing preventing that, so it is worth asserting rather than trusting.</p>
     */
    @Test
    void theGeneratedConsoleIsGitIgnored() throws Exception {
        String ignores = Files.readString(Path.of(".gitignore"));

        assertThat(ignores)
                .as("src/main/resources/static is vite output; committing it means shipping a "
                        + "front end built from a different commit than the back end")
                .contains("src/main/resources/static/");
    }
}
