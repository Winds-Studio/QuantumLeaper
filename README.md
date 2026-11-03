QuantumLeaper
=============
A launcher for the [Leaf](https://github.com/Winds-Studio/Leaf) Minecraft server, built on
[Paperclip](https://github.com/PaperMC/Paperclip).

QuantumLeaper ships only the patches, not the patched server. On the first run it applies the
[bsdiff](http://www.daemonology.net/bsdiff/) patches to the vanilla Minecraft server jar, so the
Leaf server is generated locally, and then runs it. This avoids the legal problems of redistributing
the patched jar.

Features
--------

- **Generates the server on first run** from the vanilla jar and the bundled patches, with SHA-256
  checks of every input, patch and output. The result is cached, so later starts are fast.
- **Libraries are downloaded at runtime** from Maven repositories instead of being bundled in the jar.
  Maven Central mirrors are configurable; the PaperMC and Leaf repositories are always tried after them.
- **Auto update** (optional): on start, it downloads the newest build of the `stable` or `experimental`
  channel and uses its patches. The loader itself is not updated automatically.
- **Java version check**: the Java version required by the Minecraft version is read from `version.json`
  and checked before starting, instead of failing with an obscure error.
- **Safe downloads**: failed downloads are retried from the same repository, then the next one is tried.
  Rate limits (429) and forbidden responses (403) are not retried.
- **Compatible with Paperweight and Origami**: the `*.list` metadata format of the Paperclip jar is kept,
  and the `-Dpaperclip.patchonly=true` mode used by the Origami Gradle plugin still works.

Configuration
-------------

On the first start, QuantumLeaper writes `config/leaf-loader.yml` with the default settings and exits,
so you can check it first. Later starts fill in missing options and keep your changes.

```yaml
# Maven Central mirrors, tried in order. Setting this replaces the default mirror.
maven-central-mirrors:
- https://maven-central.storage-download.googleapis.com/maven2/
# How many times to retry a failed download from the same repository
download-retries: 2
auto-update:
  # Download the newest build of the channel on every start
  enabled: false
  # stable or experimental
  channel: stable
```

An invalid file is reported as an error and left untouched, instead of silently falling back to the defaults.

Building
--------

Building QuantumLeaper creates a runnable jar without the patch data. The
[paperweight](https://github.com/PaperMC/paperweight) Gradle plugin generates the patches and the
config file and adds them to the jar, which then works as a complete launcher.

Credits
-------

QuantumLeaper is based on **[Paperclip](https://github.com/PaperMC/Paperclip)** by the PaperMC team,
the launcher that makes binary patch distribution for Paper possible. Thanks also to the
[paperweight](https://github.com/PaperMC/paperweight) project, which generates the patches, and to
[Leaf](https://github.com/Winds-Studio/Leaf), the server it launches.
