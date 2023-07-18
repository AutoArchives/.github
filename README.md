# archiver.java

This place where all machinery behind the automatic archival system is hosted.
Archiver is a small cli script that will add, update and manage archived repositories.

## Requirements 

To host the Archiver, you must first install [jbang](https://www.jbang.dev/),
jbang will do all the heavy lifting of managing dependendencies
and allow you to run the archiver like any other ordinary script.

The Archiver requires a GitHub personal access token (with full repo and org permission) and its own org to manage,
once you have both in hands copy over the archiver executable, GitHub actions, settings.properties and logback.xml.

To configure the Archiver,
you must change settings.properties to point to the org and repository that will host the script and actions.
For authorization, you must create a GitHub secret for the actions named "AA_TOKEN"
containing the GitHub personal access token
(and optionally, a secret named "AA_DISCORD" if you want to be notified about changes via a discord webhook).

Once everything is configured, you can either via GitHub actions or locally add new repositories to the archives,
and it should keep them updated automatically.
