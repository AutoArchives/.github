///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 17+
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.GpgConfig;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;

import static picocli.CommandLine.Parameters.NULL_VALUE;

@Command(name = "archiver", mixinStandardHelpOptions = true, description = "Archive management system", subcommands = {
        CommandAdd.class
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
}

@Command(name = "add", description = "Adds a new GitHub repository to the arquives", mixinStandardHelpOptions = true)
class CommandAdd implements Callable<Integer> {
    @Parameters(index = "0", description = "GitHub repository id (owner/repo) to add to the archives", defaultValue = NULL_VALUE)
    private String ghRepoId;
    @Option(names = {"--token", "-T"}, description = "GitHub token", defaultValue = "")
    String token;
    @Option(names = {"--webhook", "-W"}, description = "Discord Webhook", defaultValue = "")
    String webhook;

    // Do let exceptions propagate, they should provide enough information about why they happened and this script is
    //  not meant to be executed by humans.
    @Override
    public Integer call() throws Exception {
        try (var localGit = Git.open(archiver.GIT_PATH.toAbsolutePath().toFile())) {
            var settings = archiver.getSettings();
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
                archiver.log("@|red GitHub credentials missing from environment!|@");
                return 1;
            }

            if (ghRepoId == null) {
                archiver.log("@|red Repository name must be provided!|@");
                return 1;
            } else {
                ghRepoId = ghRepoId.toLowerCase(Locale.ROOT);
                if (ghRepoId.startsWith(orgName.toLowerCase(Locale.ROOT))) {
                    archiver.log("@|red Cannot add an archiver repo to the archives!|@");
                    return 1;
                }
            }
            var newId = ghRepoId.toLowerCase().replace('/', '-');
            var data = archiver.getData();

            if (data.repos().containsKey(newId)) {
                archiver.log("@|red Repo is already archived!|@");
                return 1;
            }

            var originalGitHubRepo = github.getRepository(ghRepoId);
            var org = github.getOrganization(orgName);
            GHRepository fork;
            try {
                // Maybe the script crashed during the brittle submodule handling
                fork = github.getRepository(orgName + '/' + newId);
                archiver.log("@|red Found archived fork with the same name... Data corruption? Using the already existing repo instead.|@");
            } catch (Exception e) {
                // No repo found, let's fork it!
                fork = originalGitHubRepo.forkTo(org);
                fork.renameTo(newId);
            }

            // When adding submodules you *must* commit the added submodule, otherwise weird things happen....
            var submodulePath = "archives/" + newId;
            localGit.submoduleAdd().setPath(submodulePath).setURI(fork.getHttpTransportUrl())
                    .call().close();
            localGit.add().addFilepattern(".gitmodules").addFilepattern(submodulePath).call();
            localGit.commit().setAuthor(orgName, "").setMessage("Submodule created")
                    .setCommitter(orgName, "").setSign(false).setGpgConfig(new GpgConfig(new Config()))
                    .call();

            // Maybe this is not needed? Better just deinit just to be on the safe side.
            localGit.submoduleDeinit().setForce(true).addPath(submodulePath).call();

            // Everything went right (I think), we can now save the new archived repo and let auto commit handle the changed data.
            data.repos().put(newId, new ArchivedRepo(originalGitHubRepo.getFullName()));
            archiver.saveData(data);

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
                    archiver.logger.error("Unable to send webhook, suppressing exception and exiting cleanly....", e);
                }
            }

            return 0;
        }
    }
}

// Overkill typed json
record DataSchema(Map<String, ArchivedRepo> repos) {
}

record ArchivedRepo(String upstreamName) {
}
