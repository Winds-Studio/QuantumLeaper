# QuantumLeaper

A launcher for the [Leaf](https://github.com/Winds-Studio/Leaf) Minecraft server software, built on
[Paperclip](https://github.com/PaperMC/Paperclip).

## Features

**Library downloads**
- Downloads libraries when the server starts to save jar size
- Configurable Maven Central mirrors
- Simple retry strategies on downloading failed (Except 429 Rate limits and 403 forbidden responses)

**Auto update**
- Configurable update channel
- Downloads and always use the newest build
- Notifies you when the loader itself needs an update

## Building

QuantumLeaper is just a bootstrap utility; building it is only for testing and developing purposes.

```bash
./gradlew build
```

## Credits

QuantumLeaper is a fork of **[Paperclip](https://github.com/PaperMC/Paperclip)** by the PaperMC team and inherits its license. See [LICENSE](LICENSE.txt).
