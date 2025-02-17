/**
 * Copyright Pravega Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.pravega.storage.filesystem;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

/**
 * Wrapper for File System calls.
 */
@Slf4j
public class FileSystemWrapper {
    /**
     * Set of PosixFilePermission for readonly file.
     */
    static final Set<PosixFilePermission> READ_ONLY_PERMISSION = ImmutableSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    /**
     * Set of PosixFilePermission for writable file.
     */
    static final Set<PosixFilePermission> READ_WRITE_PERMISSION = ImmutableSet.of(
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    @Getter
    private final Cache<String, FileChannel> readCache;
    @Getter
    private final Cache<String, FileChannel> writeCache;

    /**
     * Constructs new instance of FileSystemWrapper.
     * @param readCacheSize Read cache size
     * @param writeCacheSize Write cache size
     * @param readCacheExpirationDuration Read Cache Expiration Duration
     * @param writeCacheExpirationDuration Write Cache Expiration Duration
     */
    public FileSystemWrapper(int readCacheSize, int writeCacheSize, Duration readCacheExpirationDuration, Duration writeCacheExpirationDuration) {
        readCache = CacheBuilder.newBuilder()
                .maximumSize(readCacheSize)
                .expireAfterAccess(readCacheExpirationDuration)
                .removalListener(this::handleRemovalNotification)
                .build();
        writeCache = CacheBuilder.newBuilder()
                .maximumSize(writeCacheSize)
                .expireAfterAccess(writeCacheExpirationDuration)
                .removalListener(this::handleRemovalNotification)
                .build();
    }

    void handleRemovalNotification(RemovalNotification<String, FileChannel> notification) {
        if (notification.getCause() != RemovalCause.REPLACED) {
            val channel = notification.getValue();
            try {
                channel.close();
                log.info("FileSystemWrapper: closing file {}", notification.getKey());
            } catch (IOException e) {
                log.warn("FileSystemWrapper: Error while closing channel", e);
            }
        }
    }

    /**
     * Creates a file by calling {@link Files#createFile(Path, FileAttribute[])} .
     * @param fileAttributes File attributes.
     * @param path Path for the file to create.
     * @return Path of created file.
     * @throws IOException Exception thrown by file system call.
     */

    Path createFile(FileAttribute<Set<PosixFilePermission>> fileAttributes, Path path) throws IOException {
        readCache.invalidate(path.toString());
        writeCache.invalidate(path.toString());
        return Files.createFile(path, fileAttributes);
    }

    /**
     * Create a directories for the given path by calling {@link Files#createDirectories(Path, FileAttribute[])}.
     * @param parent Path to the parent.
     * @return Path of created directory.
     * @throws IOException Exception thrown by file system call.
     */
    Path createDirectories(Path parent) throws IOException {
        return Files.createDirectories(parent);
    }

    /**
     * Deletes given path by calling {@link Files#delete(Path)}.
     * @param path File/directory to delete.
     * @throws IOException Exception thrown by file system call.
     */
    void delete(Path path) throws IOException {
        readCache.invalidate(path.toString());
        writeCache.invalidate(path.toString());
        Files.delete(path);
    }

    /**
     * Checks whether given file is a regular file
     * by calling {@link Files#isRegularFile(Path, LinkOption...)} and {@link Files#isDirectory(Path, LinkOption...)}.
     * @param path File path.
     * @return
     */
    boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * Checks whether given file is writable by calling {@link Files#isWritable(Path)}.
     * @param path File path.
     * @return true if file is writable, false otherwise.
     */
    boolean isWritable(Path path) {
        return Files.isWritable(path);
    }

    /**
     * Checks whether given file exists by calling {@link Files#exists(Path, LinkOption...)}.
     * @param path File path.
     * @return True if file exists, false otherwise.
     */
    boolean exists(Path path) {
        return Files.exists(path);
    }

    /**
     * Creates a {@link FileChannel} for given path.
     * @param path File path.
     * @param openOption Open options. {@link StandardOpenOption}
     * @return
     * @throws IOException Exception thrown by file system call.
     */
    FileChannel getFileChannel(Path path, StandardOpenOption openOption) throws IOException {
        FileChannel channel = null;
        val fullPath = path.toString();
        if (openOption.equals(StandardOpenOption.READ)) {
            channel = readCache.getIfPresent(fullPath);
            if (null == channel) {
                channel = FileChannel.open(path, openOption);
                log.info("FileSystemWrapper: opening file for read {}", fullPath);
                readCache.put(fullPath, channel);
            }
        }
        if (openOption.equals(StandardOpenOption.WRITE) || openOption.equals(StandardOpenOption.CREATE) || openOption.equals(StandardOpenOption.CREATE_NEW)) {
            channel = writeCache.getIfPresent(fullPath);
            if (null == channel) {
                channel = FileChannel.open(path, openOption);
                log.info("FileSystemWrapper: opening file for write {}", fullPath);
                writeCache.put(fullPath, channel);
            }
        }
        return channel;
    }

    /**
     * Gets the size of file in bytes.
     * @param path File path.
     * @return Size of the file.
     * @throws IOException Exception thrown by file system call.
     */
    long  getFileSize(Path path) throws IOException {
        return Files.size(path);
    }

    /**
     * Gets the used space in bytes corresponding to the partition/volume for given path.
     * @param path File path.
     * @return Used space in bytes.
     */
    long getUsedSpace(Path path) {
        File file = path.toFile();
        return file.getTotalSpace() - file.getUsableSpace();
    }

    /**
     * Sets a file's POSIX permissions by calling {@link Files#setPosixFilePermissions(Path, Set)}.
     * @param path Path to the file.
     * @param permissions The new set of permissions.
     * @return Path
     * @throws IOException
     */
    Path setPermissions(Path path, Set<PosixFilePermission>  permissions) throws IOException {
        return Files.setPosixFilePermissions(path, permissions);
    }
}
