package org.jabref.logic.exporter;

import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import org.jabref.logic.util.io.FileUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A file output stream that is similar to the standard {@link FileOutputStream}.
 *
 * <p>
 * In detail, the strategy is to:
 * <ol>
 * <li>Create a backup (with .bak suffix) of the original file (if it exists) in the same directory.</li>
 * <li>Write to the target file</li>
 * <li>Delete the backup file (if configured to do so).</li>
 * </ol>
 * If all goes well, no backup files will remain on disk after closing the stream.
 * <p>
 * Errors are handled as follows:
 * <ol>
 * <li>If anything goes wrong while writing to the target file, the backup file will replace the original file.</li>
 * </ol>
 * <p>
 */
public class AtomicFileOutputStream extends FilterOutputStream {

    private static final Logger LOGGER = LoggerFactory.getLogger(AtomicFileOutputStream.class);

    private static final String BACKUP_EXTENSION = ".bak";

    /**
     * The file we want to create/replace.
     */
    private final Path targetFile;
    private final FileLock targetFileLock;

    /**
     * A backup of the target file (if it exists), created when the stream is closed
     */
    private final Path backupFile;
    private final boolean keepBackup;

    /**
     * Creates a new output stream to write to or replace the file at the specified path.
     *
     * @param path       the path of the file to write to or replace
     * @param keepBackup whether to keep the backup file after a successful write process
     */
    public AtomicFileOutputStream(Path path, boolean keepBackup) throws IOException {
        super(Files.newOutputStream(path));

        this.targetFile = path;
        this.backupFile = getPathOfBackupFile(path);
        this.keepBackup = keepBackup;

        try {
            // Lock files (so that at least not another JabRef instance writes at the same time to the same target file)
            if (out instanceof FileOutputStream) {
                targetFileLock = ((FileOutputStream) out).getChannel().lock();
            } else {
                targetFileLock = null;
            }
        } catch (OverlappingFileLockException exception) {
            throw new IOException("Could not obtain write access to " + targetFile + ". Maybe another instance of JabRef is currently writing to the same file?", exception);
        }
    }

    /**
     * Creates a new output stream to write to or replace the file at the specified path. The backup file is deleted when the write was successful.
     *
     * @param path the path of the file to write to or replace
     */
    public AtomicFileOutputStream(Path path) throws IOException {
        this(path, false);
    }

    private static Path getPathOfBackupFile(Path targetFile) {
        return FileUtil.addExtension(targetFile, BACKUP_EXTENSION);
    }

    /**
     * Returns the path of the backup copy of the original file (may not exist)
     */
    public Path getBackup() {
        return backupFile;
    }
    
    public void createBackup() throws IOException {
         // First, make backup of original file and try to save file permissions to restore them later (by default: 664)
        Set<PosixFilePermission> oldFilePermissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ);
        if (Files.exists(targetFile)) {
            Files.copy(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            if (FileUtil.IS_POSIX_COMPILANT) {
                try {
                    oldFilePermissions = Files.getPosixFilePermissions(targetFile);
                } catch (IOException exception) {
                    LOGGER.warn("Error getting file permissions for file {}.", targetFile, exception);
                }
            }
        }
    }

    /**
     * Override for performance reasons.
     */
    @Override
    public void write(byte b[], int off, int len) throws IOException {
        try {
            out.write(b, off, len);
        } catch (IOException exception) {
            cleanup();
            throw exception;
        }
    }

    /**
     * Closes the write process to the target file.
     */
    public void abort() {
        try {
            super.close();
            Files.deleteIfExists(backupFile);
        } catch (IOException exception) {
            LOGGER.debug("Unable to abort writing to file " + this.targetFile, exception);
        }
    }

    private void cleanup() {
        try {
            if (targetFileLock != null) {
                targetFileLock.release();
            }
        } catch (IOException exception) {
            LOGGER.warn("Unable to release lock on file " + targetFile, exception);
        }
    }

    // Perform the final operations to check the target file has been written correctly
    @Override
    public void close() throws IOException {
        try {
            try {
                // Make sure we have written everything to the target file
                flush();
                if (out instanceof FileOutputStream) {
                    ((FileOutputStream) out).getFD().sync();
                }
            } catch (IOException exception) {
                // Try to close nonetheless
                super.close();
                throw exception;
            }
            super.close();

            // We successfully wrote everything to the target file, now restore file permissions
            if (FileUtil.IS_POSIX_COMPILANT) {
                try {
                    Files.setPosixFilePermissions(targetFile, oldFilePermissions);
                } catch (IOException exception) {
                    LOGGER.warn("Error writing file permissions to file {}.", targetFile, exception);
                }
            }

            if (!keepBackup) {
                // Remove backup file
                Files.deleteIfExists(backupFile);
            }
        } finally {
            // Remove backup file
            cleanup();
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            super.flush();
        } catch (IOException exception) {
            cleanup();
            throw exception;
        }
    }

    @Override
    public void write(int b) throws IOException {
        try {
            super.write(b);
        } catch (IOException exception) {
            cleanup();
            throw exception;
        }
    }
}

