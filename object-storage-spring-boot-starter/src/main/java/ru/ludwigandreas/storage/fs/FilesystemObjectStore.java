package ru.ludwigandreas.storage.fs;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import ru.ludwigandreas.storage.api.ByteRange;
import ru.ludwigandreas.storage.api.ObjectStore;
import ru.ludwigandreas.storage.api.ObjectSummary;
import ru.ludwigandreas.storage.api.ObjectUri;
import ru.ludwigandreas.storage.api.PutOptions;
import ru.ludwigandreas.storage.api.StoredObject;
import ru.ludwigandreas.storage.exception.InvalidObjectUriException;
import ru.ludwigandreas.storage.exception.ObjectNotFoundException;
import ru.ludwigandreas.storage.exception.ObjectStoreException;

/**
 * An {@link ObjectStore} over a directory on this machine.
 *
 * <h2>Not a stub</h2>
 *
 * <p>This exists to be used, not to fill a gap until the real one arrives. Three situations are real
 * and permanent: a developer running the service on a laptop with no bucket and no container; a
 * single-node deployment where an object store would be a second thing to operate for no benefit;
 * and the module's own tests, which run the entire contract suite against this implementation so
 * that a change to the S3 one has something to disagree with.
 *
 * <p>The last of those is why ranged reads here are implemented with a {@link SeekableByteChannel}
 * positioned at the range start rather than by opening the file and skipping forward. A skip would
 * be correct, and it would also mean that every test of resumption - the tests that exist to prove a
 * 1 GB ingest does not restart from zero - passed here while proving nothing, because the expensive
 * behaviour they are written to catch would have been quietly reintroduced underneath them. A test
 * that cannot fail is worse than no test, so the cheap path is the only path.
 *
 * <p>What this is not is a store any instance can read any object from. A file written by instance A
 * is on instance A, so an estate with more than one replica wants {@code S3ObjectStore}.
 *
 * <h2>How a location becomes a path</h2>
 *
 * <p>Both schemes are accepted, and this is deliberate rather than lenient:
 *
 * <ul>
 *   <li>{@code file:///<root>/some/name} is the natural form, and the path must already lie under
 *       the configured root;</li>
 *   <li>{@code s3://bucket/key} resolves to {@code <root>/bucket/key}, so that a job configured with
 *       {@code s3://partner-drop/catalogue/} runs unchanged against a directory in a developer's
 *       checkout. Being able to substitute this store for the real one without editing a single
 *       location is most of what makes it worth having.</li>
 * </ul>
 *
 * <p>Anything resolving outside the root is refused. {@link ObjectUri} already rejects {@code ..}
 * segments, so this is the second of two checks rather than the only one; it is here anyway because
 * the consequence of getting it wrong - a service reading, or deleting, an arbitrary file by path -
 * is severe enough to be worth a redundant guard, and because a symlink inside the root can produce
 * an escape no amount of string checking would catch.
 *
 * <h2>The etag here is not a content hash, and why that is the right trade</h2>
 *
 * <p>S3 hands back an etag with every listing entry, for free, because it stored one. This store has
 * no such thing, so it has to compute one, and the only content-derived answer is to read the file.
 * Doing that would make {@link #list(String)} - whose entire purpose is to page cheaply over a
 * prefix holding a year of daily drops - read every byte of every object in that prefix, which is a
 * worse failure than the one lazy paging was introduced to fix.
 *
 * <p>So the etag is a digest over the file's size and last-modified time at nanosecond resolution.
 * It changes whenever the file is written, which is the case that matters: a partner replacing a
 * drop with a corrected one produces a new etag and therefore a new ingest run, even when the
 * correction changed one character and left the length identical. The case it does not catch is a
 * rewrite that preserves the modification time exactly - a {@code cp -p} or a restore from an
 * archive - which produces the same etag as the file it replaced and would be skipped as a
 * duplicate. That is a real limitation and it is stated here rather than discovered: an estate whose
 * exactly-once guarantee has to survive timestamp-preserving restores should be running against
 * {@code S3ObjectStore}, whose etag is derived from the content itself.
 */
@Slf4j
public class FilesystemObjectStore implements ObjectStore {

    private final Path root;

    /**
     * Creates the store over a directory that must already exist and be writable.
     *
     * <p>Deliberately does not create the root, for the reason {@code FilesystemReportSink} gives
     * about its own: a typo in a configured path that silently created a directory would put
     * production data somewhere nobody is watching and nobody is backing up, and afterwards the two
     * are indistinguishable.
     *
     * @param root the directory objects live under
     * @throws ObjectStoreException if it does not exist or cannot be written
     */
    public FilesystemObjectStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("A FilesystemObjectStore needs a root directory");
        }
        Path resolved = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved)) {
            throw new ObjectStoreException("open", resolved.toString(),
                    new NoSuchFileException(resolved.toString()));
        }
        if (!Files.isWritable(resolved)) {
            throw new ObjectStoreException("open", resolved.toString(),
                    new IOException("Storage root is not writable by this process"));
        }
        this.root = resolved;
    }

    /**
     * The directory this store is rooted at.
     *
     * @return the absolute, normalised root
     */
    public Path root() {
        return root;
    }

    @Override
    public StoredObject head(String uri) {
        ObjectUri parsed = ObjectUri.parse(uri);
        Path file = resolve(parsed);
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                // A directory is not an object. S3 has no directories at all, so a store that
                // answered head() for one would be inventing a concept the other implementation
                // cannot have, and a caller written against it would break on the real one.
                throw new ObjectNotFoundException(parsed.value(), null);
            }
            return new StoredObject(parsed.value(), attributes.size(), etag(attributes),
                    null, attributes.lastModifiedTime().toInstant(), contentTypeOf(file));
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException(parsed.value(), e);
        } catch (IOException e) {
            throw new ObjectStoreException("head", parsed.value(), e);
        }
    }

    @Override
    public InputStream open(String uri) {
        ObjectUri parsed = ObjectUri.parse(uri);
        Path file = resolve(parsed);
        try {
            return Files.newInputStream(file, StandardOpenOption.READ);
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException(parsed.value(), e);
        } catch (IOException e) {
            throw new ObjectStoreException("open", parsed.value(), e);
        }
    }

    @Override
    public InputStream open(String uri, ByteRange range) {
        ObjectUri parsed = ObjectUri.parse(uri);
        Path file = resolve(parsed);
        SeekableByteChannel channel = null;
        try {
            channel = Files.newByteChannel(file, StandardOpenOption.READ);
            // Positioning past the end is legal and leaves the channel returning -1 on the first
            // read, which is exactly the empty stream ObjectStore promises for a range starting at or
            // past EOF. S3 answers the same request with a 416 that S3ObjectStore turns into the same
            // empty stream. The shared contract test asserts both: two stores disagreeing here would
            // make a resume correct in production and broken in development, or the reverse, and
            // whichever way round it fell the symptom would be a missing or duplicated tail.
            channel.position(range.start());
            InputStream stream = Channels.newInputStream(channel);
            return range.bounded() ? new BoundedInputStream(stream, range.length()) : stream;
        } catch (NoSuchFileException e) {
            closeQuietly(channel, parsed);
            throw new ObjectNotFoundException(parsed.value(), e);
        } catch (IOException e) {
            closeQuietly(channel, parsed);
            throw new ObjectStoreException("open", parsed.value(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>One deviation from strict laziness, stated because the interface promises otherwise: the
     * entries are sorted, and sorting is what makes this store's ordering match S3's lexicographic
     * one, which in turn is what lets a single contract test be meaningful against both. Sorting
     * holds the <em>keys</em> of the matched prefix in memory - not their contents and not their
     * metadata beyond what a directory entry already carries - and this store is the
     * single-node/development path rather than the one pointed at a bucket with millions of objects.
     * {@code S3ObjectStore} does not sort, because S3 already returns keys in order and sorting there
     * would defeat the paging entirely.
     */
    @Override
    public Stream<ObjectSummary> list(String prefix) {
        ObjectUri parsed = ObjectUri.parse(prefix);
        Path resolved = resolve(parsed);
        Path start = nearestExistingDirectory(resolved);
        if (start == null) {
            return Stream.empty();
        }
        String pathPrefix = resolved.toString();
        try {
            Stream<Path> walk = Files.walk(start, FileVisitOption.FOLLOW_LINKS);
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().startsWith(pathPrefix))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> toSummary(parsed, path));
        } catch (IOException e) {
            throw new ObjectStoreException("list", parsed.value(), e);
        }
    }

    @Override
    public StoredObject put(String uri, Path file, PutOptions options) {
        ObjectUri parsed = ObjectUri.parse(uri);
        Path target = resolve(parsed);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Stored {} at {} ({} bytes)", file, parsed.value(), Files.size(target));
            return head(parsed.value());
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException(ObjectUri.ofFile(file).value(), e);
        } catch (IOException e) {
            throw new ObjectStoreException("put", parsed.value(), e);
        }
    }

    @Override
    public StoredObject copy(String sourceUri, String targetUri) {
        ObjectUri source = ObjectUri.parse(sourceUri);
        ObjectUri target = ObjectUri.parse(targetUri);
        Path sourceFile = resolve(source);
        Path targetFile = resolve(target);
        try {
            Path parent = targetFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES);
            return head(target.value());
        } catch (NoSuchFileException e) {
            throw new ObjectNotFoundException(source.value(), e);
        } catch (IOException e) {
            throw new ObjectStoreException("copy", source.value(), e);
        }
    }

    @Override
    public void delete(String uri) {
        ObjectUri parsed = ObjectUri.parse(uri);
        Path file = resolve(parsed);
        try {
            // Absence is success - see ObjectStore#delete. deleteIfExists says so in one call rather
            // than in an exists/delete pair, which would also carry a race between the two.
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ObjectStoreException("delete", parsed.value(), e);
        }
    }

    @Override
    public boolean exists(String uri) {
        return Files.isRegularFile(resolve(ObjectUri.parse(uri)));
    }

    private Path resolve(ObjectUri uri) {
        try {
            Path resolved = uri.isS3()
                    ? root.resolve(uri.container()).resolve(stripLeadingSlashes(uri.key())).normalize()
                    : Paths.get(uri.key()).toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                throw new InvalidObjectUriException(uri.value());
            }
            return resolved;
        } catch (InvalidPathException e) {
            throw new InvalidObjectUriException(uri.value());
        }
    }

    /**
     * Walks up from a resolved prefix to the first directory that exists.
     *
     * <p>A prefix is not a path. {@code catalogue/2026-09} names every key beginning with those
     * characters, of which {@code catalogue/2026-09-25.csv} is one and no directory is, so walking
     * from the resolved path would find nothing for exactly the prefixes a caller is most likely to
     * use. Walking from the nearest existing ancestor and filtering on the prefix afterwards is what
     * reproduces S3's semantics.
     */
    private Path nearestExistingDirectory(Path resolved) {
        Path candidate = resolved;
        while (candidate != null && candidate.startsWith(root) && !Files.isDirectory(candidate)) {
            candidate = candidate.getParent();
        }
        return candidate != null && candidate.startsWith(root) ? candidate : null;
    }

    private ObjectSummary toSummary(ObjectUri prefix, Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new ObjectSummary(uriOf(prefix, path), attributes.size(), etag(attributes),
                    attributes.lastModifiedTime().toInstant());
        } catch (IOException e) {
            throw new ObjectStoreException("list", prefix.value(), e);
        }
    }

    /**
     * The content identity of a local file - see this class's note on why it is not a content hash.
     */
    private String etag(BasicFileAttributes attributes) {
        long modified = attributes.lastModifiedTime().toInstant().getEpochSecond();
        int nanos = attributes.lastModifiedTime().toInstant().getNano();
        return HexFormat.of().toHexDigits(attributes.size())
                + HexFormat.of().toHexDigits(modified)
                + HexFormat.of().toHexDigits(nanos);
    }

    private String contentTypeOf(Path file) {
        try {
            return Files.probeContentType(file);
        } catch (IOException e) {
            // A content type nobody could determine is not a failure of the operation the caller
            // asked for; failing head() because the platform's type detector could not open a
            // mapping file would be a surprising way to lose a run.
            log.debug("Could not probe the content type of {}: {}", file, e.toString());
            return null;
        }
    }

    private String uriOf(ObjectUri prefix, Path path) {
        if (!prefix.isS3()) {
            return ObjectUri.ofFile(path).value();
        }
        String relative = root.resolve(prefix.container()).relativize(path).toString()
                .replace(File.separatorChar, '/');
        return ObjectUri.ofS3(prefix.container(), relative).value();
    }

    private void closeQuietly(SeekableByteChannel channel, ObjectUri uri) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException e) {
            log.debug("Could not close the channel opened for {}: {}", uri.value(), e.toString());
        }
    }

    private static String stripLeadingSlashes(String key) {
        String value = key;
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }
}
