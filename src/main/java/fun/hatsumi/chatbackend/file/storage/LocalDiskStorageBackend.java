package fun.hatsumi.chatbackend.file.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;

/**
 * 本地磁盘存储后端（chatroom.storage.type=local，缺省）。
 *
 * <p>所有路径 normalize 后必须仍位于 storage root（防路径穿越）。
 * 大文件一律流式处理，不使用 readAllBytes。</p>
 */
@Service
@ConditionalOnProperty(name = "chatroom.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalDiskStorageBackend implements StorageBackend {

    private final Path storageRoot;

    public LocalDiskStorageBackend(ChatroomProperties properties) {
        this.storageRoot = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    // ---------------------------------------------------------------- StorageBackend

    @Override
    public String initUpload(String uploadId, String targetKey) {
        return null; // 本地模式无后端上传上下文
    }

    @Override
    public String putChunk(String uploadId, String backendUploadId, String targetKey, int chunkIndex, byte[] data) {
        writeChunk(uploadId, chunkIndex, data);
        return null;
    }

    @Override
    public CompletedUpload completeUpload(String uploadId, String backendUploadId, String targetKey,
            java.util.List<UploadedPart> parts) {
        Path target = resolve(targetKey);
        Path tempTarget = target.resolveSibling(target.getFileName() + ".merging");
        String sha256 = mergeChunks(uploadId, parts.size(), tempTarget);
        atomicMove(tempTarget, target);
        return new CompletedUpload(targetKey, sha256);
    }

    @Override
    public void abortUpload(String uploadId, String backendUploadId, String targetKey) {
        cleanupTemp(uploadId);
    }

    @Override
    public void cleanupUpload(String uploadId) {
        cleanupTemp(uploadId);
    }

    @Override
    public InputStream openStream(String key, Long start, Long end) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(resolve(key), StandardOpenOption.READ);
            if (start != null) {
                channel.position(start); // FileChannel 精确定位，无 skip 短读歧义
            }
            InputStream in = Channels.newInputStream(channel);
            if (end != null) {
                long from = start == null ? 0 : start;
                in = new BoundedInputStream(in, end - from + 1); // 含端点，限长读取
            }
            return in;
        } catch (IOException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw BusinessException.notFound("文件不存在");
        }
    }

    /** 限长输入流：最多读取 limit 字节后报告 EOF。 */
    private static final class BoundedInputStream extends java.io.FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream delegate, long limit) {
            super(delegate);
            this.remaining = limit;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int c = super.read();
            if (c >= 0) {
                remaining--;
            }
            return c;
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(resolve(key));
        } catch (IOException e) {
            throw BusinessException.notFound("文件不存在");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            // 删除失败不阻断业务，仅记录
        }
    }

    // ---------------------------------------------------------------- 内部实现（自原 StorageService 平移）

    /**
     * 相对路径解析 + 越界校验。
     */
    public Path resolve(String relativePath) {
        Path resolved = storageRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(storageRoot)) {
            throw BusinessException.badRequest("非法文件路径");
        }
        return resolved;
    }

    /**
     * 写入分块临时文件：temp/{uploadId}/{index}.part。
     */
    void writeChunk(String uploadId, int chunkIndex, byte[] data) {
        Path chunkPath = resolve("temp/" + uploadId + "/" + chunkIndex + ".part");
        try {
            Files.createDirectories(chunkPath.getParent());
            Files.write(chunkPath, data);
        } catch (IOException e) {
            throw new BusinessException("分块写入失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 顺序合并分块到临时目标文件，返回完整文件 SHA-256（流式摘要）。
     */
    String mergeChunks(String uploadId, int totalChunks, Path target) {
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (OutputStream out = Files.newOutputStream(target)) {
                for (int i = 0; i < totalChunks; i++) {
                    Path chunkPath = resolve("temp/" + uploadId + "/" + i + ".part");
                    if (!Files.exists(chunkPath)) {
                        throw BusinessException.conflict("缺少分块 #" + i);
                    }
                    try (InputStream in = Files.newInputStream(chunkPath)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            digest.update(buffer, 0, n);
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (IOException e) {
            throw new BusinessException("分块合并失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 同目录原子移动（同盘 rename）。
     */
    void atomicMove(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("文件落盘失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 清理上传会话的临时分块目录。
     */
    void cleanupTemp(String uploadId) {
        try {
            Path dir = resolve("temp/" + uploadId);
            if (Files.isDirectory(dir)) {
                try (var stream = Files.list(dir)) {
                    stream.forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignored) {
        }
    }

    Path randomStoredPath(String scope, Long ownerId, String originalName) {
        return resolve(StorageBackend.newTargetKey(scope, ownerId, originalName));
    }
}
