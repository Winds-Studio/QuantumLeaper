package cn.dreeam.leaper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

record FileEntry(byte[] hash, String id, String path) {

    static FileEntry[] parse(final BufferedReader reader) throws IOException {
        var result = new FileEntry[8];

        int index = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            final FileEntry data = parseLine(line);
            if (index == result.length) {
                result = Arrays.copyOf(result, index * 2);
            }
            result[index++] = data;
        }

        if (index != result.length) {
            return Arrays.copyOf(result, index);
        } else {
            return result;
        }
    }

    private static FileEntry parseLine(final String line) {
        final var parts = line.split("\t");
        if (parts.length != 3) {
            throw new IllegalStateException("Malformed library entry: " + line);
        } else {
            return new FileEntry(Util.fromHex(parts[0]), parts[1], parts[2]);
        }
    }

    void extractFile(
        final Resources resources,
        final Map<String, URL> urls,
        final PatchEntry[] patches,
        final String targetName,
        final Path originalRootDir,
        final String baseDir,
        final Path outputDir,
        final boolean checkCache,
        final LibraryDownloader downloader
    ) throws IOException {
        for (final PatchEntry patch : patches) {
            if (patch.location().equals(targetName) && patch.outputPath().equals(this.path)) {
                // This file will be created from a patch
                return;
            }
        }

        // 1. The local cache
        final Path outputFile = outputDir.resolve(this.path);
        if (checkCache && Files.exists(outputFile) && Util.isFileValid(outputFile, this.hash)) {
            urls.put(this.path, outputFile.toUri().toURL());
            return;
        }

        // 2. Our jar: files the build keeps bundled, e.g. libraries built by the project itself,
        //    which can't be downloaded from Maven Central
        // 3. The original (Mojang) jar: vanilla libraries, which are not on Maven Central either
        // 4. Libraries only: download from the Maven repositories
        final String filePath = Util.endingSlash(baseDir) + this.path;
        InputStream fileStream = resources.open(filePath);
        if (fileStream == null && originalRootDir != null) {
            final Path originalFile = originalRootDir.resolve(filePath);
            if (Files.exists(originalFile)) {
                fileStream = Files.newInputStream(originalFile);
            }
        }
        if (fileStream == null) {
            if (downloader == null) {
                throw new IllegalStateException(this.path + " not found in our jar or in the original jar");
            }
            downloader.download(this, outputFile);
            urls.put(this.path, outputFile.toUri().toURL());
            return;
        }

        final Path tempFile = Util.tempFileFor(outputFile);
        try {
            try (
                final InputStream stream = fileStream;
                final ReadableByteChannel inputChannel = Channels.newChannel(stream);
                final FileChannel outputChannel = FileChannel.open(tempFile, WRITE, TRUNCATE_EXISTING)
            ) {
                outputChannel.transferFrom(inputChannel, 0, Long.MAX_VALUE);
            }

            if (!Util.isFileValid(tempFile, this.hash)) {
                throw new IllegalStateException("Hash check failed for extract filed " + outputFile);
            }

            Util.moveAtomically(tempFile, outputFile);
        } finally {
            Files.deleteIfExists(tempFile);
        }

        urls.put(this.path, outputFile.toUri().toURL());
    }
}
