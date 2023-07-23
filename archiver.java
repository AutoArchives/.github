///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 18+
//DEPS ch.qos.logback:logback-core:1.4.8
//DEPS ch.qos.logback:logback-classic:1.4.8
//DEPS info.picocli:picocli:4.6.3
//DEPS org.fusesource.jansi:jansi:2.4.0
//DEPS org.kohsuke:github-api:1.315
//DEPS com.fasterxml.jackson.core:jackson-core:2.15.2
//DEPS com.fasterxml.jackson.core:jackson-databind:2.15.2
//DEPS org.projectlombok:lombok:1.18.28
//DEPS org.eclipse.jgit:org.eclipse.jgit:6.6.0.202305301015-r
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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ListBranchCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.submodule.SubmoduleWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHubBuilder;
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
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static picocli.CommandLine.Parameters.NULL_VALUE;

@Command(name = "archiver", mixinStandardHelpOptions = true, description = "Archive management system", subcommands = {
    archiver.CommandAdd.class, archiver.CommandSync.class, archiver.CommandMod.class
})
class archiver implements Runnable {
  static final Path DATA_FILE = Path.of("data.json");
  static final Path SETTINGS_FILE = Path.of("settings.properties");
  static final Path GIT_PATH = Path.of(".");
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

  public static void main(String... args) {
    // Initialize fancy logging, kinda overkill for a CLI app but helps with debugging issues from JGIT.
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

  @Override
  public void run() {
    // No subcommand provided
    log("@|green Type archiver --help for available commands.|@");
  }

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

      ArchivedRepo newRepo;
      data.repos().put(id, newRepo = ArchivedRepo.builder()
          .upstreamName(repo.getUpstreamName())
          .upstreamGone(upstreamGone == null ? repo.isUpstreamGone() : upstreamGone)
          .build());
      saveData(data);

      logger.info("Updated {} to {}", repo, newRepo);
      return 0;
    }
  }

  @Command(name = "sync", description = "Syncs all archived repos with their upstream.", mixinStandardHelpOptions = true)
  static class CommandSync implements Callable<Integer> {
    @Option(names = {"--token", "-T"}, description = "GitHub token", defaultValue = "")
    String token;
    @Option(names = {"--webhook", "-W"}, description = "Discord Webhook", defaultValue = "")
    String webhook;
    @Option(names = {"--debug", "-D"}, description = "Print changes to console")
    boolean debug;

    @Override
    public Integer call() throws Exception {
      try (var localGit = Git.open(GIT_PATH.toAbsolutePath().toFile())) {
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

        log("Attempting to update forks...");
        // We need to authenticate jgit for pull&push.
        var creds = new UsernamePasswordCredentialsProvider(token, "");

        /*
         Error handling here is a little bit weird,
         here we have two types of error to watch out, expected and unexpected ones.
         Unexpected errors when updating an archive should be added to this map,
         it will make the script return as error state
         and fail the action without failing the entire script.
         Downstream repositories will hopefully be updated
         even though the archiver repo submodules will be outdated,
         they will be corrected next successful run.
         Expected errors
         (not really expected, but things we can safely catch and report as change instead)
         are reported as changes instead and allows the script to run "successfully".
        */
        var errors = new HashMap<String, Exception>();
        var data = getData();
        List<String> changes = new ArrayList<>();
        var localRepo = localGit.getRepository();

        for (Map.Entry<String, ArchivedRepo> entry : data.repos().entrySet()) {
          if (entry.getValue().isUpstreamGone()) {
            // Nothing to update if there's no upstream to update from...
            log("Skipping {}", entry.getKey());
            continue;
          }

          log("Updating {}", entry.getKey());

          try (var submodule = Git.wrap(SubmoduleWalk.getSubmoduleRepository(localRepo, "archives/" + entry.getKey()))) {
            // Used to fetch remote data directly from GitHub.
            var upstream = github.getRepository(entry.getValue().getUpstreamName());
            var downstream = github.getRepository(orgName + '/' + entry.getKey());

            // Hack around submodule limited information and GitHub actions messing up auth...
            submodule.getRepository().getConfig().unset("http", "https://github.com/", "extraheader");
            submodule.remoteSetUrl().setRemoteName("origin").setRemoteUri(new URIish(downstream.getHttpTransportUrl())).call();
            submodule.fetch().setRemote("origin").call();

            // Get downstream branches.
            var branches = submodule.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                .stream().map(Ref::getName).filter(name -> name.startsWith("refs/remotes/origin/") && !name.equals("refs/remotes/origin/HEAD"))
                .map(Repository::shortenRefName).map(s -> s.replace("origin/", "")).collect(Collectors.toSet());
            // And local branches to avoid creating new branches.
            var localBranches = submodule.branchList().call()
                .stream().map(Ref::getName).map(Repository::shortenRefName).filter(name -> !name.equals("HEAD"))
                .collect(Collectors.toSet());

            var upstreamBranches = upstream.getBranches();
            var mainBranch = downstream.getDefaultBranch(); // Should we sync with upstream default branch?

            // Base starting point, the submodule HEAD should always point to the main branch Ref
            submodule.checkout().setName(mainBranch).setForced(true).call();
            submodule.remoteAdd().setName("upstream").setUri(new URIish(upstream.getHttpTransportUrl())).call();

            try {
              // Check for new branches to fetch in and push to downstream.
              {
                var newBranches = new HashSet<>(upstreamBranches.keySet());
                newBranches.removeAll(branches);
                if (!newBranches.isEmpty()) {
                  changes.add("[%s] New branches present on upstream: %s".formatted(entry.getKey(),
                      String.join(", ", newBranches)));
                  for (String newBranch : newBranches) {
                    // RefSpecs, fetch refs/heads/<branch> reference from remote into refs/remotes/upstream/<branch> locally
                    submodule.fetch().setRemote("upstream").setRefSpecs("refs/heads/%1$s:refs/remotes/upstream/%1$s"
                        .formatted(newBranch)).call();

                    // Create new branch locally
                    submodule.checkout().setCreateBranch(true).setName(newBranch)
                        .setStartPoint("upstream/" + newBranch)
                        .setUpstreamMode(SetupUpstreamMode.TRACK).call();

                    // Attempt to push changes without crashing out if it fails...
                    try {
                      // RefSpecs, use +<branch name> to create branch on remote.
                      submodule.push().setRemote("origin").setRefSpecs(new RefSpec("+" + newBranch))
                          .setCredentialsProvider(creds).call();
                      branches.add(newBranch);
                      localBranches.add(newBranch);
                    } catch (Exception e) {
                      changes.add("[%s] Error pushing new branch %s: %s".formatted(entry.getKey(),
                          newBranch, e.getMessage()));
                      logger.warn("Unable to push new {} to {}", newBranch, entry.getKey());
                      logger.warn("Due to", e);
                    }
                  }
                }
              }

              // Update branches with upstream.
              {
                // Fetch *all* upstream refs.
                submodule.fetch().setRemote("upstream").call();
                for (String branch : branches) {
                  if (upstreamBranches.containsKey(branch)) {
                    // Ensure we are clean
                    submodule.reset().setMode(ResetCommand.ResetType.HARD).call();

                    var checkout = submodule.checkout().setName(branch).setStartPoint("origin/" + branch).setForced(true);
                    // Ugh, I wish it was cleaner than doing this.
                    if (localBranches.contains(branch)) {
                      checkout.setForceRefUpdate(true).call();
                    } else {
                      checkout.setCreateBranch(true).call();
                    }

                    var repo = submodule.getRepository();
                    var head = repo.resolve(Constants.HEAD).getName();
                    var remoteHead = upstreamBranches.get(branch).getSHA1();
                    logger.debug("local '{}', remote '{}'", head, remoteHead);
                    // HEADS differ,
                    // either we have new commits or the branch now contains an entirely new commit history.
                    if (!head.equals(remoteHead)) {
                      try {
                        logger.info("{}-{} remote updated! HEAD now at {}", entry.getKey(), branch, remoteHead);
                        changes.add("[%s] Remote branch '%s' changed! Remote HEAD '%s'".formatted(entry.getKey(), branch, remoteHead));

                        // Pull the changes...
                        submodule.pull().setRemote("upstream").setRemoteBranchName(branch)
                            .setCredentialsProvider(creds).call();

                        // And push to our fork.
                        submodule.push().setRemote("origin").setRefSpecs(new RefSpec(branch + ":refs/heads/" + branch))
                            .setCredentialsProvider(creds).call();
                      } catch (Exception e) {
                        // Perhaps the history was rewritten? Manual intervention is required to avoid data loss.
                        changes.add("[%s] Error updating branch %s: %s".formatted(entry.getKey(),
                            branch, e.getMessage()));
                        logger.warn("Unable to push {} to {}", branch, entry.getKey());
                        logger.warn("Due to", e);
                      }
                    }
                  } else {
                    // Deleted branch? Renamed branch due to upstream rewriting the history? Keep it as it.
                    logger.warn("Upstream missing branch {}", branch);
                  }
                }
              }
            } finally {
              // Set the submodule back to the main branch and restore a common state.
              submodule.checkout().setName(mainBranch).setForced(true).call();
            }

          } catch (Exception e) {
            changes.add("[%s] Fatal error: %s".formatted(entry.getKey(), e.getMessage()));
            errors.put(entry.getKey(), e);
          }
        }

        logger.info("Forks updated, now reporting changes.");

        // Gotta be on the safe side...
        String finalToken = token;
        changes = changes.stream().map(s -> s.replaceAll(finalToken, "<TOKEN>"))
            .collect(Collectors.toList());

        if (debug && !changes.isEmpty()) {
          logger.info("Changes to report via Discord webhook:");
          changes.forEach(logger::info);
        }

        // Notify the discord server we have changes.
        if (!webhook.isBlank() && !changes.isEmpty()) {
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
          for (Map.Entry<String, Exception> entry : errors.entrySet()) {
            logger.error("Unable to sync " + entry.getKey(), entry.getValue());
          }
          return 1; // Mark it as failed and spam my emails.
        }
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
    @Parameters(description = "GitHub repository id (owner/repo) to add to the archives", defaultValue = "")
    private String[] ghRepoIds;

    // Do let exceptions propagate, they should provide enough information about why they happened and this script is
    //  not meant to be executed by humans.
    @Override
    public Integer call() throws Exception {
      try (var localGit = Git.open(GIT_PATH.toAbsolutePath().toFile())) {
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
        } else {
          for (int i = 0; i < ghRepoIds.length; i++) {
            ghRepoIds[i] = ghRepoIds[i].toLowerCase(Locale.ROOT);
            if (ghRepoIds[i].startsWith(orgName.toLowerCase(Locale.ROOT))) {
              log("@|red Cannot add an archiver repo to the archives!|@");
              return 1;
            }
          }

        }
        var data = getData();
        for (int i = 0; i < ghRepoIds.length; i++) {
          var newId = ghRepoIds[i].replace('/', '-');


          if (data.repos().containsKey(newId)) {
            log("@|red Repo is already archived!|@");
            return 1;
          }

          logger.info("Forking {}", ghRepoIds[i]);
          var originalGitHubRepo = github.getRepository(ghRepoIds[i]);
          var org = github.getOrganization(orgName);
          GHRepository fork;
          try {
            // Maybe the script crashed during the brittle submodule handling
            fork = github.getRepository(orgName + '/' + newId);
            log("@|red Found archived fork with the same name... Data corruption? Using the already existing repo instead.|@");
          } catch (Exception e) {
            // No repo found, let's fork it!
            fork = originalGitHubRepo.forkTo(org);
            fork.renameTo(newId);
            fork = github.getRepository(orgName + '/' + newId);
          }

          // When adding submodules, you *must* commit the added submodule, otherwise weird things happen....
          var submodulePath = "archives/" + newId;
          localGit.submoduleAdd().setPath(submodulePath).setURI(fork.getHttpTransportUrl())
            .call().close();
          localGit.add().addFilepattern(".gitmodules").addFilepattern(submodulePath).call();

          // Everything went right (I think), we can now save the new archived repo and let auto commit handle the changed data.
          data.repos().put(newId, ArchivedRepo.builder().upstreamName(originalGitHubRepo.getFullName()).build());

          // Notify the discord server we have a new repo!
          if (!webhook.isBlank()) {
            try (var client = WebhookClient.withUrl(webhook)) {
              // Send and log (using embed)
              WebhookEmbed embed = new WebhookEmbedBuilder()
                .setColor(0xF69000) // Nice
                .setTitle(new WebhookEmbed.EmbedTitle("New repository archived as " + newId, fork.getHtmlUrl().toString()))
                .setDescription("Archived from [%s](%s)".formatted(originalGitHubRepo.getFullName(), originalGitHubRepo.getHtmlUrl().toString()))
                .build();

              client.send(embed).get();
            } catch (Throwable e) {
              logger.error("Unable to send webhook, suppressing exception and exiting cleanly....", e);
            }
          }
        }

        localGit.commit().setAuthor(orgName, "").setMessage("Submodule created")
          .setCommitter(orgName, "").setSign(false).setGpgConfig(new GpgConfig(new Config()))
          .call();

        for (String ghRepoId : ghRepoIds) {
          var submodulePath = "archives/" + ghRepoId.replace('/', '-');
          // Maybe this is not needed? Better just deinit just to be on the safe side.
          localGit.submoduleDeinit().setForce(true).addPath(submodulePath).call();
        }

        saveData(data);

        return 0;
      }
    }
  }
}

// Overkill typed json
record DataSchema(Map<String, ArchivedRepo> repos) {
}

// We may need fancy default values in the future, use lombok to make plain boring class less boring
@Value
@Builder
@Jacksonized
@ToString
@EqualsAndHashCode
class ArchivedRepo {
  String upstreamName;
  boolean upstreamGone; // Indicates that upstream is no longer existent and no attempts at updating should be made
}
