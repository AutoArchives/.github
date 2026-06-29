///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS ch.qos.logback:logback-core:1.4.8
//DEPS ch.qos.logback:logback-classic:1.4.8
//DEPS info.picocli:picocli:4.6.3
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.kohsuke:github-api:1.327
//DEPS com.fasterxml.jackson.core:jackson-core:2.15.2
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.2
//DEPS club.minnced:discord-webhooks:0.8.2

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import club.minnced.discord.webhook.WebhookClient;
import club.minnced.discord.webhook.send.WebhookEmbed;
import club.minnced.discord.webhook.send.WebhookEmbedBuilder;
import club.minnced.discord.webhook.send.WebhookMessageBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.kohsuke.github.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static picocli.CommandLine.Parameters.NULL_VALUE;

sealed interface SyncAction permits
    SyncAction.CreateBranch, SyncAction.UpdateBranch, SyncAction.ArchiveOrphanBranch,
    SyncAction.CreateTag, SyncAction.ReportTagMoved {
    record CreateBranch(String branch, String sha) implements SyncAction {
    }
    record UpdateBranch(String branch, String oldSha, String newSha) implements SyncAction {
    }
    // branch is gone from upstream, preserve its tip as an archived/* tag then delete
    // it so the fork stays an exact mirror (also clears any name clash for new branches)
    record ArchiveOrphanBranch(String branch, String sha) implements SyncAction {
    }
    record CreateTag(String tag, String sha) implements SyncAction {
    }
    record ReportTagMoved(String tag, String oldSha, String newSha) implements SyncAction {
    }
}

// The few ref mutations the executor needs, abstracted away so the conflict
// logic can be unit-tested without touching GitHub.
interface RefOps {
    void updateBranch(String branch, String sha, boolean force) throws IOException;

    void createBranch(String branch, String sha) throws IOException;

    void createArchivedTag(String tagName, String commitSha, String message) throws IOException;

    void createTag(String tag, String sha) throws IOException;

    void deleteBranch(String branch) throws IOException;

    void setDefaultBranch(String branch) throws IOException;
}

@Command(name = "archiver", mixinStandardHelpOptions = true, description = "Archive management system", subcommands = {
    archiver.CommandAdd.class, archiver.CommandSync.class, archiver.CommandMod.class
})
class archiver implements Runnable {
    static final Path DATA_FILE = Path.of("data.json");
    static final Path STATE_FILE = Path.of("state.json");
    static final Path SETTINGS_FILE = Path.of("settings.properties");
    static final Logger logger = LoggerFactory.getLogger("Archiver");
    static final ObjectMapper mapper = new ObjectMapper();

    static Properties getSettings() throws IOException {
        var properties = new Properties();
        properties.load(Files.newBufferedReader(SETTINGS_FILE));
        return properties;
    }

    static DataSchema getData() throws IOException {
        if (Files.isRegularFile(DATA_FILE)) {
            return mapper.readValue(Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8), DataSchema.class);
        } else {
            return new DataSchema(new HashMap<>());
        }
    }

    static void saveData(DataSchema data) throws IOException {
        Files.writeString(DATA_FILE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data), StandardCharsets.UTF_8);
    }

    static StateSchema getState() throws IOException {
        if (Files.isRegularFile(STATE_FILE)) {
            return mapper.readValue(Files.newBufferedReader(STATE_FILE, StandardCharsets.UTF_8), StateSchema.class);
        }
        return new StateSchema(null, new TreeMap<>());
    }

    static void saveState(StateSchema state) throws IOException {
        Files.writeString(STATE_FILE, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(state),
            StandardCharsets.UTF_8);
    }

    // Read a repo's refs of one type ("heads" or "tags") as name to sha, sorted.
    // nothing gets transferred, just the ref list
    static Map<String, String> listRefs(GHRepository repo, String refType) throws IOException {
        var out = new TreeMap<String, String>();
        int strip = ("refs/" + refType + "/").length();
        try {
            for (GHRef ref : repo.getRefs(refType)) {
                var full = ref.getRef();
                if (full.length() > strip) {
                    out.put(full.substring(strip), ref.getObject().getSha());
                }
            }
        } catch (GHFileNotFoundException e) {
            // repo has no refs of this type (like no tags), treat as empty
        }
        return out;
    }

    // Project the fork's post-sync ref state from what it had plus what we just
    // did, so we can record state.json without re-listing.
    static RepoState computePostState(String defaultBranch, Map<String, String> forkHeads,
                                      Map<String, String> forkTags, List<SyncAction> actions, String now) {
        var branches = new TreeMap<>(forkHeads);
        var tags = new TreeMap<>(forkTags);
        for (SyncAction a : actions) {
            if (a instanceof SyncAction.CreateBranch cb) {
                branches.put(cb.branch(), cb.sha());
            } else if (a instanceof SyncAction.UpdateBranch ub) {
                branches.put(ub.branch(), ub.newSha());
            } else if (a instanceof SyncAction.ArchiveOrphanBranch ob) {
                // archived as a tag and deleted; the tag shows up next sync
                branches.remove(ob.branch());
            } else if (a instanceof SyncAction.CreateTag ct) {
                tags.put(ct.tag(), ct.sha());
            }
            // ReportTagMoved: we don't move tags, so the fork keeps its value.
        }
        return new RepoState(defaultBranch, now, branches, tags);
    }

    static String shortSha(String sha) {
        return sha != null && sha.length() > 7 ? sha.substring(0, 7) : sha;
    }

    // Keep only the repos worth mirroring and drop forks, private ones and the .github meta repo.
    static List<String> selectArchivable(List<RepoCandidate> candidates) {
        return candidates.stream()
            .filter(c -> !c.fork() && !c.privateRepo() && !isMetaRepo(c.fullName()))
            .map(RepoCandidate::fullName)
            .collect(Collectors.toList());
    }

    static boolean isMetaRepo(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return fullName.substring(slash + 1).equalsIgnoreCase(".github");
    }

    // A ref write that 404s almost always means the token is missing the 'workflow'
    // scope, github blocks creating or updating refs whose commits touch
    // .github/workflows unless the token has it, and unhelpfully reports it as a
    // plain 404 instead of saying so
    static String workflowScopeHint(Throwable e) {
        var msg = String.valueOf(e.getMessage());
        var is404 = e instanceof GHFileNotFoundException
            || (e instanceof HttpException h && h.getResponseCode() == 404);
        if (is404 && msg.contains("/git/refs")) {
            return " (likely the token is missing the 'workflow' scope, needed when an upstream"
                + " commit changes .github/workflows files)";
        }
        return "";
    }

    // github answers a fork request on an empty (no commits) repo with a 403 saying it
    // "contains no Git content", there's nothing to mirror so bulk-add just skips those
    static boolean isEmptyRepo(HttpException e) {
        return e.getResponseCode() == 403
            && String.valueOf(e.getMessage()).contains("Empty repositories cannot be forked");
    }

    // Diff upstream against the fork and decide what to do. Sorted so the action
    // list (and therefore the change report) is deterministic.
    static List<SyncAction> classify(Map<String, String> upstreamHeads, Map<String, String> forkHeads,
                                     Map<String, String> upstreamTags, Map<String, String> forkTags) {
        var actions = new ArrayList<SyncAction>();
        // Orphans first, archiving + deleting them keeps the fork an exact mirror and
        // clears any path a new upstream branch needs (refs/heads/dev vs dev/26.1).
        for (var b : new TreeSet<>(forkHeads.keySet())) {
            if (!upstreamHeads.containsKey(b)) {
                actions.add(new SyncAction.ArchiveOrphanBranch(b, forkHeads.get(b)));
            }
        }
        for (var b : new TreeSet<>(upstreamHeads.keySet())) {
            var up = upstreamHeads.get(b);
            var fork = forkHeads.get(b);
            if (fork == null) {
                actions.add(new SyncAction.CreateBranch(b, up));
            } else if (!fork.equals(up)) {
                actions.add(new SyncAction.UpdateBranch(b, fork, up));
            }
        }
        for (var t : new TreeSet<>(upstreamTags.keySet())) {
            var up = upstreamTags.get(t);
            var fork = forkTags.get(t);
            if (fork == null) {
                actions.add(new SyncAction.CreateTag(t, up));
            } else if (!fork.equals(up)) {
                actions.add(new SyncAction.ReportTagMoved(t, fork, up));
            }
        }
        return actions;
    }

    public static void main(String... args) {
        // Initialize fancy logging, kinda overkill for a CLI app but gives us nice structured output for the picocli bits.
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        try {
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            context.reset();
            configurator.doConfigure("logback.xml");
        } catch (JoranException ignored) {
            // StatusPrinter will handle this
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);

        // Let picocli do the POSIX compliant cli interpretation
        int exitCode = new CommandLine(new archiver()).execute(args);
        System.exit(exitCode);
    }

    static void log(String markupText, Object... params) {
        logger.info(Ansi.AUTO.string(markupText), params);
    }

    // Move a mirror branch onto its upstream tip. Try a fast-forward first; the
    // 422 GitHub returns when that isn't possible, that means force-push/history-reset
    // When that happens we stash the old tip under an annotated archived/*
    // tag *before* forcing the branch, so nothing is ever lost.
    static BranchOutcome applyBranchUpdate(RefOps ops, String branch, String oldSha, String newSha) throws IOException {
        try {
            ops.updateBranch(branch, newSha, false);
            return BranchOutcome.FAST_FORWARD;
        } catch (HttpException e) {
            if (e.getResponseCode() != 422) {
                throw e; // something other than a rejected fast-forward; let the caller deal with it
            }
            var tag = "archived/" + branch + "/" + shortSha(oldSha);
            ops.createArchivedTag(tag, oldSha, "Preserved before upstream rewrite on " + Instant.now());
            ops.updateBranch(branch, newSha, true);
            return BranchOutcome.REWRITE_PRESERVED;
        }
    }

    // Archive a branch's tip as a tag then delete it, preserving history before
    // removing the branch.
    private static void archiveOrphan(String repoId, SyncAction.ArchiveOrphanBranch ob, RefOps ops,
                                      boolean dryRun, List<String> changes) throws IOException {
        var tag = "archived/" + ob.branch() + "/" + shortSha(ob.sha());
        if (dryRun) {
            changes.add("[%s] Would archive orphan branch '%s' as tag '%s' and delete it"
                .formatted(repoId, ob.branch(), tag));
        } else {
            // preserve the tip before deleting, so the history is never lost
            ops.createArchivedTag(tag, ob.sha(), "Preserved orphaned branch on " + Instant.now());
            ops.deleteBranch(ob.branch());
            changes.add("[%s] Branch '%s' gone from upstream; preserved as tag '%s' and deleted"
                .formatted(repoId, ob.branch(), tag));
        }
    }

    // Applies an action list against one repo and return human-readable change lines for the report.
    // Dry run describes what would happen and touches nothing.
    //
    // Order matters here so drop non-default orphans first (clears name clashes for new branches),
    // then create/update upstream branches, then point the fork's default at upstream's (it exists here now),
    // and only then drop the old default orphan, github won't let us delete a branch while it's the default.
    static List<String> applyActions(String repoId, List<SyncAction> actions, RefOps ops, boolean dryRun,
                                     String upstreamDefault, String forkDefault) throws IOException {
        var changes = new ArrayList<String>();

        // orphans that aren't the current default
        for (SyncAction a : actions) {
            if (a instanceof SyncAction.ArchiveOrphanBranch ob && !ob.branch().equals(forkDefault)) {
                archiveOrphan(repoId, ob, ops, dryRun, changes);
            }
        }

        // create new upstream branches and update changed ones
        for (SyncAction a : actions) {
            if (a instanceof SyncAction.CreateBranch cb) {
                if (dryRun) {
                    changes.add("[%s] Would create branch '%s' -> %s".formatted(repoId, cb.branch(), shortSha(cb.sha())));
                } else {
                    ops.createBranch(cb.branch(), cb.sha());
                    changes.add("[%s] New branch '%s' -> %s".formatted(repoId, cb.branch(), shortSha(cb.sha())));
                }
            } else if (a instanceof SyncAction.UpdateBranch ub) {
                if (dryRun) {
                    changes.add("[%s] Would update branch '%s' %s -> %s"
                        .formatted(repoId, ub.branch(), shortSha(ub.oldSha()), shortSha(ub.newSha())));
                } else {
                    var outcome = applyBranchUpdate(ops, ub.branch(), ub.oldSha(), ub.newSha());
                    if (outcome == BranchOutcome.FAST_FORWARD) {
                        changes.add("[%s] Branch '%s' updated -> %s".formatted(repoId, ub.branch(), shortSha(ub.newSha())));
                    } else {
                        changes.add("[%s] Branch '%s' history rewritten; preserved %s as tag 'archived/%s/%s', reset -> %s"
                            .formatted(repoId, ub.branch(), shortSha(ub.oldSha()), ub.branch(), shortSha(ub.oldSha()),
                                shortSha(ub.newSha())));
                    }
                }
            }
        }

        // match upstream's default branch (now that it exists in the fork)
        if (upstreamDefault != null && !upstreamDefault.equals(forkDefault)) {
            if (dryRun) {
                changes.add("[%s] Would set default branch to '%s'".formatted(repoId, upstreamDefault));
            } else {
                ops.setDefaultBranch(upstreamDefault);
                changes.add("[%s] Default branch set to '%s'".formatted(repoId, upstreamDefault));
            }
        }

        // the old default orphan can be removed now that it isn't the default
        for (SyncAction a : actions) {
            if (a instanceof SyncAction.ArchiveOrphanBranch ob && ob.branch().equals(forkDefault)) {
                archiveOrphan(repoId, ob, ops, dryRun, changes);
            }
        }

        // do tags
        for (SyncAction a : actions) {
            if (a instanceof SyncAction.CreateTag ct) {
                if (dryRun) {
                    changes.add("[%s] Would create tag '%s' -> %s".formatted(repoId, ct.tag(), shortSha(ct.sha())));
                } else {
                    ops.createTag(ct.tag(), ct.sha());
                    changes.add("[%s] New tag '%s' -> %s".formatted(repoId, ct.tag(), shortSha(ct.sha())));
                }
            } else if (a instanceof SyncAction.ReportTagMoved rt) {
                // log only, we don't move tags so this never converges, putting it in
                // `changes` would re-ping Discord on every run forever
                logger.info("[{}] Upstream moved tag '{}' {} -> {} (left as-is)",
                    repoId, rt.tag(), shortSha(rt.oldSha()), shortSha(rt.newSha()));
            }
        }
        return changes;
    }

    @Override
    public void run() {
        // No subcommand provided
        log("@|green Type archiver --help for available commands.|@");
    }

    enum BranchOutcome {FAST_FORWARD, REWRITE_PRESERVED}

    @Command(name = "mod", description = "Modify an archived repo flags", mixinStandardHelpOptions = true)
    static class CommandMod implements Callable<Integer> {
        @Option(names = {"--upstreamGone", "-D"}, description = "Set if upstream is gone/deleted.", defaultValue = NULL_VALUE)
        Boolean upstreamGone;
        @Parameters(index = "0", description = "Repository name within the archives.", defaultValue = "")
        private String id;

        @Override
        public Integer call() throws Exception {
            id = id.toLowerCase(Locale.ROOT);
            var data = getData();
            var repo = data.repos().get(id);

            if (repo == null) {
                log("@|red Repository not found within the archives.|@");
                return 1;
            }

            var newRepo = new ArchivedRepo(repo.upstreamName(),
                upstreamGone == null ? repo.upstreamGone() : upstreamGone);
            data.repos().put(id, newRepo);
            saveData(data);

            logger.info("Updated {} to {}", repo, newRepo);
            return 0;
        }
    }

    @Command(name = "sync", description = "Refreshes all archived repos from upstream via the GitHub API.", mixinStandardHelpOptions = true)
    static class CommandSync implements Callable<Integer> {
        @Option(names = {"--token", "-T"}, description = "GitHub token", defaultValue = "")
        String token;
        @Option(names = {"--webhook", "-W"}, description = "Discord Webhook", defaultValue = "")
        String webhook;
        @Option(names = {"--debug", "-D"}, description = "Print changes to console")
        boolean debug;
        @Option(names = {"--dry-run", "-n"}, description = "Print planned actions without mutating any repository")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            var settings = getSettings();
            var orgName = settings.getProperty("archiver.org");

            var token = System.getProperty("archiver.github.token", System.getenv("GITHUB_TOKEN"));
            if (token == null || token.isBlank()) {
                token = this.token;
            }
            var webhook = System.getProperty("archiver.webhook", System.getenv("DISCORD_WEBHOOK"));
            if (webhook == null || webhook.isBlank()) {
                webhook = this.webhook;
            }

            var github = new GitHubBuilder().withOAuthToken(token).build();
            if (!github.isCredentialValid() || github.isAnonymous()) {
                log("@|red GitHub credentials missing from environment!|@");
                return 1;
            }

            log(dryRun ? "Planning archive refresh (dry run)..." : "Refreshing archives via the GitHub API...");

            // Same error split as before: unexpected failures go in `errors` and fail
            // the run (so I get an email) without aborting the rest; everything else is
            // reported as a change.
            var errors = new HashMap<String, Exception>();
            var data = getData();
            var state = getState();
            var newState = new TreeMap<String, RepoState>();
            var changes = new ArrayList<String>();
            var now = Instant.now().toString();

            for (Map.Entry<String, ArchivedRepo> entry : data.repos().entrySet()) {
                var id = entry.getKey();
                if (entry.getValue().upstreamGone()) {
                    log("Skipping {}", id);
                    var prev = state.repos().get(id);
                    if (prev != null) {
                        newState.put(id, prev); // nothing to refresh, keep the last known state
                    }
                    continue;
                }

                log("Checking {}", id);
                try {
                    var upstream = github.getRepository(entry.getValue().upstreamName());
                    var fork = github.getRepository(orgName + '/' + id);

                    var upstreamHeads = listRefs(upstream, "heads");
                    var forkHeads = listRefs(fork, "heads");
                    var upstreamTags = listRefs(upstream, "tags");
                    var forkTags = listRefs(fork, "tags");

                    var actions = classify(upstreamHeads, forkHeads, upstreamTags, forkTags);
                    var upstreamDefault = upstream.getDefaultBranch();
                    var forkDefault = fork.getDefaultBranch();
                    changes.addAll(applyActions(id, actions, new GitHubRefOps(fork), dryRun, upstreamDefault, forkDefault));

                    // record the post-sync default (we point it at upstreams unless this is a dry run)
                    var newDefault = !dryRun && upstreamDefault != null && !upstreamDefault.equals(forkDefault)
                        ? upstreamDefault : forkDefault;
                    newState.put(id, computePostState(newDefault, forkHeads, forkTags,
                        dryRun ? List.of() : actions, now));
                } catch (Exception e) {
                    changes.add("[%s] Fatal error: %s%s".formatted(id, e.getMessage(), workflowScopeHint(e)));
                    errors.put(id, e);
                    var prev = state.repos().get(id);
                    if (prev != null) {
                        newState.put(id, prev); // couldn't refresh; don't drop its state
                    }
                }
            }

            logger.info("Archives checked, now reporting changes.");

            // Gotta be on the safe side and never leak the token into the report.
            var finalToken = token;
            changes = changes.stream().map(s -> s.replace(finalToken, "<TOKEN>"))
                .collect(Collectors.toCollection(ArrayList::new));

            if (debug && !changes.isEmpty()) {
                logger.info("Changes to report:");
                changes.forEach(logger::info);
            }

            if (!dryRun) {
                saveState(new StateSchema(now, newState));
            }

            if (!dryRun && !webhook.isBlank() && !changes.isEmpty()) {
                try (var client = WebhookClient.withUrl(webhook)) {
                    var message = new WebhookMessageBuilder().setContent("Archive changes:")
                        .addFile("changes.log", new ByteArrayInputStream(String.join("\n", changes)
                            .getBytes(StandardCharsets.UTF_8))).build();
                    client.send(message).get();
                } catch (Throwable e) {
                    logger.error("Unable to send webhook, suppressing exception and exiting cleanly....", e);
                }
            }

            if (!errors.isEmpty()) {
                logger.warn("Errors occurred while syncing:");
                for (Map.Entry<String, Exception> en : errors.entrySet()) {
                    logger.error("Unable to sync {}", en.getKey(), en.getValue());
                }
                return 1; // Mark it as failed and spam my emails.
            }
            return 0;
        }
    }

    @Command(name = "add", description = "Adds a new GitHub repository to the arquives.", mixinStandardHelpOptions = true)
    static class CommandAdd implements Callable<Integer> {
        @Option(names = {"--token", "-T"}, description = "GitHub token", defaultValue = "")
        String token;
        @Option(names = {"--webhook", "-W"}, description = "Discord Webhook", defaultValue = "")
        String webhook;
        @Option(names = {"--dry-run", "-n"}, description = "List what would be archived without forking anything")
        boolean dryRun;
        @Parameters(description = "GitHub repository id (owner/repo), or a bare owner/org to bulk-add", defaultValue = "")
        private String[] ghRepoIds;

        @Override
        public Integer call() throws Exception {
            var settings = getSettings();
            var token = System.getProperty("archiver.github.token", System.getenv("GITHUB_TOKEN"));
            if (token == null || token.isBlank()) {
                token = this.token;
            }
            var webhook = System.getProperty("archiver.webhook", System.getenv("DISCORD_WEBHOOK"));
            if (webhook == null || webhook.isBlank()) {
                webhook = this.webhook;
            }

            var orgName = settings.getProperty("archiver.org");
            var github = new GitHubBuilder().withOAuthToken(token).build();
            if (!github.isCredentialValid() || github.isAnonymous()) {
                log("@|red GitHub credentials missing from environment!|@");
                return 1;
            }

            if (ghRepoIds.length == 0) {
                log("@|red Repository name must be provided!|@");
                return 1;
            }

            // Resolve the inputs into concrete owner/repo targets.
            // Anything with a '/' is taken as-is and a bare owner gets expanded into its public,
            // non-fork repos so you can hand it a whole user or org and let it figure out the rest.
            var orgLower = orgName.toLowerCase(Locale.ROOT);
            var targets = new ArrayList<String>();
            for (String input : ghRepoIds) {
                if (input.toLowerCase(Locale.ROOT).startsWith(orgLower)) {
                    log("@|red Cannot add an archiver repo to the archives!|@");
                    return 1;
                }
                if (input.contains("/")) {
                    targets.add(input);
                } else {
                    try {
                        var expanded = expandOwner(github, input);
                        if (expanded.isEmpty()) {
                            log("@|yellow No archivable repos found for {}.|@", input);
                        }
                        targets.addAll(expanded);
                    } catch (GHFileNotFoundException e) {
                        // bail out early; nothing's been forked yet so we lose no progress
                        log("@|red Owner '%s' not found.|@", input);
                        return 1;
                    }
                }
            }

            var data = getData();
            int added = 0;
            int skipped = 0;
            for (String target : targets) {
                var ghRepoId = target.toLowerCase(Locale.ROOT);
                var newId = ghRepoId.replace('/', '-');
                if (data.repos().containsKey(newId)) {
                    log("@|yellow Already archived: {}}|@", newId);
                    skipped++;
                    continue;
                }

                if (dryRun) {
                    log("Would archive {} as {}", target, newId);
                    added++;
                    continue;
                }

                logger.info("Forking {}", ghRepoId);
                var originalGitHubRepo = github.getRepository(ghRepoId);
                var org = github.getOrganization(orgName);
                GHRepository fork;
                try {
                    // Maybe a previous run already forked it; reuse rather than duplicate.
                    fork = github.getRepository(orgName + '/' + newId);
                    log("@|red Found an existing fork with the same name... using it instead.|@");
                } catch (Exception e) {
                    // Name the fork at creation instead of forking then renaming.
                    // The rename path makes github retire the fork's old namespace and grab a lock for it,
                    // and against a just-forked repo that lock sometimes fails with a 422 "name retired namespace
                    // lock could not be acquired" (hello popular repos like minecraftforge).
                    // Forking straight to the final name skips the whole dance,
                    // and the builder waits out the async fork for us so we can drop the old sleep/retry loop too.
                    try {
                        fork = originalGitHubRepo.createFork().organization(org).name(newId).create();
                    } catch (HttpException ex) {
                        // nothing to mirror in an empty upstream; skip it and keep the bulk run going
                        if (isEmptyRepo(ex)) {
                            log("@|yellow Skipping {}, upstream is empty with no content to fork.|@", ghRepoId);
                            continue;
                        }
                        throw ex;
                    }
                }

                // The fork is the archive now; record it right away and save, so if a later
                // repo in a bulk run blows up we don't throw away the ones already forked.
                data.repos().put(newId, new ArchivedRepo(originalGitHubRepo.getFullName(), false));
                saveData(data);
                added++;

                if (!webhook.isBlank()) {
                    try (var client = WebhookClient.withUrl(webhook)) {
                        WebhookEmbed embed = new WebhookEmbedBuilder()
                            .setColor(0xF69000) // Nice
                            .setTitle(new WebhookEmbed.EmbedTitle("New repository archived as " + newId, fork.getHtmlUrl().toString()))
                            .setDescription("Archived from [%s](%s)".formatted(originalGitHubRepo.getFullName(),
                                originalGitHubRepo.getHtmlUrl().toString()))
                            .build();
                        client.send(embed).get();
                    } catch (Throwable e) {
                        logger.error("Unable to send webhook, suppressing exception and exiting cleanly....", e);
                    }
                }
            }

            if (dryRun) {
                log("Dry run: {} to add, {} already archived.", added, skipped);
            }
            return 0;
        }

        // List an owner's (user or org) public repos worth archiving.
        // github.getUser works for both since it just hits /users/{login}/repos,
        // and PagedIterable walks the pages for us; selectArchivable drops the forks and private ones.
        private static List<String> expandOwner(GitHub github, String owner) throws IOException {
            var candidates = new ArrayList<RepoCandidate>();
            for (GHRepository repo : github.getUser(owner).listRepositories()) {
                candidates.add(new RepoCandidate(repo.getFullName(), repo.isFork(), repo.isPrivate()));
            }
            return selectArchivable(candidates);
        }
    }
}

// Overkill typed json
record DataSchema(Map<String, ArchivedRepo> repos) {
}

// upstreamGone marks a repo whose upstream is gone for good, so sync leaves it alone.
record ArchivedRepo(String upstreamName, boolean upstreamGone) {
}

// A repo spotted while expanding an owner; just the bits the archivable filter cares about.
record RepoCandidate(String fullName, boolean fork, boolean privateRepo) {
}

// Generated each sync. The git diff of state.json is the archive's changelog.
record StateSchema(String generated, Map<String, RepoState> repos) {
}

record RepoState(String defaultBranch, String lastSynced,
                 Map<String, String> branches, Map<String, String> tags) {
}

// Real RefOps. Works purely over the GitHub API: because a fork shares an
// object network with its parent, repointing a branch ref to an upstream SHA
// moves no objects.
final class GitHubRefOps implements RefOps {
    private final GHRepository fork;

    GitHubRefOps(GHRepository fork) {
        this.fork = fork;
    }

    public void updateBranch(String branch, String sha, boolean force) throws IOException {
        fork.getRef("refs/heads/" + branch).updateTo(sha, force);
    }

    public void createBranch(String branch, String sha) throws IOException {
        fork.createRef("refs/heads/" + branch, sha);
    }

    public void createArchivedTag(String tagName, String commitSha, String message) throws IOException {
        GHTagObject tag = fork.createTag(tagName, message, commitSha, "commit");
        try {
            fork.createRef("refs/tags/" + tagName, tag.getSha());
        } catch (HttpException e) {
            // 422 means the ref already exists, probably a previous run made the tag but
            // died before the force-push, the old tip is already preserved under it so we
            // can just carry on
            if (e.getResponseCode() != 422) throw e;
        }
    }

    public void createTag(String tag, String sha) throws IOException {
        fork.createRef("refs/tags/" + tag, sha);
    }

    public void deleteBranch(String branch) throws IOException {
        fork.getRef("refs/heads/" + branch).delete();
    }

    public void setDefaultBranch(String branch) throws IOException {
        fork.setDefaultBranch(branch);
    }
}
