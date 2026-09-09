package fun.hatsumi.chatbackend.file.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;

/**
 * 磁盘存储服务：所有路径 normalize 后必须仍位于 storage root（防路径穿越）。
 * 大文件一律流式处理，不使用 readAllBytes。
 */
@Service
public class StorageService {

    private final Path storageRoot;

    public StorageService(ChatroomProperties properties) {
        this.storageRoot = Paths.get(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

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
    public void writeChunk(String uploadId, int chunkIndex, byte[] data) {
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
    public String mergeChunks(String uploadId, int totalChunks, Path target) {
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
    public void atomicMove(Path source, Path target) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BusinessException("文件落盘失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    public long size(String relativePath) {
        try {
            return Files.size(resolve(relativePath));
        } catch (IOException e) {
            throw BusinessException.notFound("文件不存在");
        }
    }

    public InputStream openStream(String relativePath) {
        try {
            return Files.newInputStream(resolve(relativePath));
        } catch (IOException e) {
            throw BusinessException.notFound("文件不存在");
        }
    }

    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException e) {
            // 删除失败不阻断业务，仅记录
        }
    }

    /**
     * 清理上传会话的临时分块目录。
     */
    public void cleanupTemp(String uploadId) {
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

    public Path randomStoredPath(String scope, Long ownerId, String originalName) {
        String storedName = System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8)
                + sanitizeExtension(originalName);
        String dir = "PUBLIC".equals(scope) ? "public" : "users/" + ownerId;
        return resolve(dir + "/" + storedName);
    }

    /** 仅保留安全的扩展名字符，防止存储名携带路径分隔符。 */
    private static String sanitizeExtension(String originalName) {
        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot).replaceAll("[^a-zA-Z0-9.]", "");
        return ext.length() > 16 ? ext.substring(0, 16) : ext;
    }
}
