///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-engine:5.12.2
//DEPS org.junit.platform:junit-platform-console:1.12.2

//SOURCES archiver.java

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.platform.console.ConsoleLauncher;

// JUnit suite for archiver.java. Add @Test methods here as more pieces get covered.
public class ArchiverTest {

    @Test
    void keepsPlainOriginals() {
        var in = List.of(new RepoCandidate("Owner/Repo", false, false));
        assertEquals(List.of("Owner/Repo"), archiver.selectArchivable(in));
    }

    @Test
    void dropsForks() {
        var in = List.of(new RepoCandidate("Owner/AFork", true, false));
        assertTrue(archiver.selectArchivable(in).isEmpty());
    }

    @Test
    void dropsPrivate() {
        var in = List.of(new RepoCandidate("Owner/Secret", false, true));
        assertTrue(archiver.selectArchivable(in).isEmpty());
    }

    @Test
    void keepsGithubArchivedAndPreservesOrder() {
        // A GitHub-archived upstream is neither a fork nor private, so it stays.
        var in = List.of(
            new RepoCandidate("Owner/KeptArchived", false, false),
            new RepoCandidate("Owner/Fork", true, false),
            new RepoCandidate("Owner/Priv", false, true),
            new RepoCandidate("Owner/AlsoKept", false, false));
        assertEquals(List.of("Owner/KeptArchived", "Owner/AlsoKept"), archiver.selectArchivable(in));
    }

    // Boots the JUnit console launcher so `jbang run ArchiverTest.java` runs the tests and exits non-zero on failure.
    // Scans jbang's cache jars (where it drops the compiled sources) for @Test classes.
    public static void main(final String... args) {
        String jarsList = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .filter(path -> path.contains("/cache/jars/"))
            .reduce((a, b) -> a + File.pathSeparator + b)
            .orElse("");
        ConsoleLauncher.main("execute", "--scan-class-path", "--fail-if-no-tests", "-cp", jarsList);
    }
}
