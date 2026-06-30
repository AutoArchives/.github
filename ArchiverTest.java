///usr/bin/env jbang "$0" "$@" ; exit $?

//DEPS org.junit.jupiter:junit-jupiter-engine:5.12.2
//DEPS org.junit.platform:junit-platform-console:1.12.2

//SOURCES archiver.java

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.platform.console.ConsoleLauncher;
import org.kohsuke.github.HttpException;

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
    void dropsGithubMetaRepo() {
        // owner/.github is the profile repo, never a bulk archive target
        var in = List.of(new RepoCandidate("Owner/.github", false, false));
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

    private static HttpException http(int code, String body) {
        return new HttpException(body, code, code + "", "https://api.github.com/repos/x/y");
    }

    @Test
    void flagsDmcaBlockAsAccessBlocked() {
        // real payload from a dmca'd repo (craftbukkit/cauldron); skip these, can't fork them
        var e = http(451, "{\"message\":\"Repository access blocked\",\"block\":{\"reason\":\"dmca\"}}");
        assertTrue(archiver.isAccessBlocked(e));
        assertFalse(archiver.isRateLimited(e));
        assertFalse(archiver.isEmptyRepo(e));
    }

    @Test
    void flagsSubmittedTooQuicklyAsRateLimited() {
        // the secondary rate limit github throws when forks come in too fast; stop, don't skip
        var e = http(403, "{\"message\":\"was submitted too quickly\",\"status\":\"403\"}");
        assertTrue(archiver.isRateLimited(e));
        assertFalse(archiver.isAccessBlocked(e));
        assertFalse(archiver.isEmptyRepo(e));
    }

    @Test
    void flagsPlain429AsRateLimited() {
        assertTrue(archiver.isRateLimited(http(429, "{\"message\":\"Too Many Requests\"}")));
    }

    @Test
    void flagsEmptyUpstreamAsEmptyRepo() {
        var e = http(403, "{\"message\":\"Empty repositories cannot be forked.\"}");
        assertTrue(archiver.isEmptyRepo(e));
        assertFalse(archiver.isRateLimited(e));
        assertFalse(archiver.isAccessBlocked(e));
    }

    @Test
    void leavesOrdinaryErrorsUnclassified() {
        var e = http(404, "{\"message\":\"Not Found\"}");
        assertFalse(archiver.isAccessBlocked(e));
        assertFalse(archiver.isRateLimited(e));
        assertFalse(archiver.isEmptyRepo(e));
    }

    // a 401 here, valid token, but GitHub's is being odd
    private static HttpException badCredentials() {
        return http(401, "{\"message\":\"Bad credentials\",\"status\":\"401\"}");
    }

    @Test
    void retryOn401RunsOnceWhenNothingFails() throws Exception {
        var calls = new AtomicInteger();
        var result = archiver.retryOn401(() -> {
            calls.incrementAndGet();
            return "ok";
        }, 3, 0);
        assertEquals("ok", result);
        assertEquals(1, calls.get(), "no failure means no retry");
    }

    @Test
    void retryOn401RidesOutTransient401sThenSucceeds() throws Exception {
        var calls = new AtomicInteger();
        // first two attempts get the random 401, third one goes through
        var result = archiver.retryOn401(() -> {
            if (calls.incrementAndGet() < 3) throw badCredentials();
            return "ok";
        }, 3, 0);
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retryOn401GivesUpAndRethrowsAfterExhaustingAttempts() {
        var calls = new AtomicInteger();
        var e = assertThrows(HttpException.class, () -> archiver.retryOn401(() -> {
            calls.incrementAndGet();
            throw badCredentials();
        }, 3, 0));
        assertEquals(401, e.getResponseCode());
        assertEquals(3, calls.get(), "should try exactly maxAttempts times before giving up");
    }

    @Test
    void retryOn401DoesNotRetryOtherErrors() {
        var calls = new AtomicInteger();
        var e = assertThrows(HttpException.class, () -> archiver.retryOn401(() -> {
            calls.incrementAndGet();
            throw http(404, "{\"message\":\"Not Found\"}");
        }, 3, 0));
        assertEquals(404, e.getResponseCode());
        assertEquals(1, calls.get());
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
