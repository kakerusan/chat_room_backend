package fun.hatsumi.chatbackend.file.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.file.entity.UploadChunkEntity;
import fun.hatsumi.chatbackend.file.entity.UploadSessionEntity;
import fun.hatsumi.chatbackend.file.mapper.UploadChunkMapper;
import fun.hatsumi.chatbackend.file.mapper.UploadSessionMapper;
import fun.hatsumi.chatbackend.file.storage.StorageService;

/**
 * 分块上传业务：会话创建、差错模拟、摘要校验、幂等、合并。
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadSessionMapper sessionMapper;

    private final UploadChunkMapper chunkMapper;

    private final fun.hatsumi.chatbackend.file.mapper.StoredFileMapper fileMapper;

    private final StorageService storage;

    private final ChatroomProperties properties;

    /** 差错模拟随机源；配置固定种子时结果可复现（自动化测试）。 */
    private final Random errorRandom;

    public UploadService(UploadSessionMapper sessionMapper, UploadChunkMapper chunkMapper,
            fun.hatsumi.chatbackend.file.mapper.StoredFileMapper fileMapper,
            StorageService storage, ChatroomProperties properties) {
        this.sessionMapper = sessionMapper;
        this.chunkMapper = chunkMapper;
        this.fileMapper = fileMapper;
        this.storage = storage;
        this.properties = properties;
        this.errorRandom = properties.getErrorSimulationSeed() != null
                ? new Random(properties.getErrorSimulationSeed())
                : new Random();
    }

    /**
     * 创建上传会话，返回会话与已上传块（断点续传时非空）。
     */
    public UploadSessionEntity createSession(Long userId, String fileName, long fileSize, int chunkSize,
            int totalChunks, String fileSha256, String scope) {
        if (!"PUBLIC".equals(scope) && !"PRIVATE".equals(scope)) {
            throw BusinessException.badRequest("scope 必须为 PUBLIC 或 PRIVATE");
        }
        UploadSessionEntity session = new UploadSessionEntity();
        session.setUploadId(UUID.randomUUID().toString().replace("-", ""));
        session.setOwnerId(userId);
        session.setScope(scope);
        session.setFileName(fileName);
        session.setFileSize(fileSize);
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setFileSha256(fileSha256);
        session.setStatus("INIT");
        session.setExpireAt(LocalDateTime.now().plusHours(24));
        session.setCreatedAt(LocalDateTime.now());
        sessionMapper.insert(session);
        return session;
    }

    /**
     * 上传单个分块：先按配置概率模拟丢失，再校验摘要、幂等落盘。
     */
    public void uploadChunk(Long userId, String uploadId, int chunkIndex, byte[] data, String clientSha256) {
        UploadSessionEntity session = requireOwnedSession(userId, uploadId);
        if (chunkIndex < 0 || chunkIndex >= session.getTotalChunks()) {
            throw BusinessException.badRequest("分块序号越界");
        }
        if (!"INIT".equals(session.getStatus())) {
            throw BusinessException.conflict("上传会话已结束");
        }

        // 差错模拟：按概率直接返回失败，客户端退避重试
        if (simulateLoss()) {
            throw new BusinessException("模拟网络差错，分块丢失（重试可恢复）", HttpStatus.INTERNAL_SERVER_ERROR);
        }

        // 幂等：已存在直接成功
        Long existing = chunkMapper.selectCount(new LambdaQueryWrapper<UploadChunkEntity>()
                .eq(UploadChunkEntity::getUploadId, uploadId)
                .eq(UploadChunkEntity::getChunkIndex, chunkIndex));
        if (existing != null && existing > 0) {
            return;
        }

        String serverSha256 = sha256Hex(data);
        if (clientSha256 == null || !clientSha256.equalsIgnoreCase(serverSha256)) {
            throw BusinessException.unprocessable("分块 SHA-256 校验失败");
        }

        storage.writeChunk(uploadId, chunkIndex, data);

        UploadChunkEntity chunk = new UploadChunkEntity();
        chunk.setUploadId(uploadId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkSize(data.length);
        chunk.setChunkSha256(serverSha256);
        chunk.setCreatedAt(LocalDateTime.now());
        try {
            chunkMapper.insert(chunk);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一索引兜底幂等
        }
    }

    /**
     * 查询已上传块序号。
     */
    public List<Integer> uploadedChunkIndexes(String uploadId) {
        return chunkMapper.selectList(new LambdaQueryWrapper<UploadChunkEntity>()
                        .eq(UploadChunkEntity::getUploadId, uploadId))
                .stream().map(UploadChunkEntity::getChunkIndex).sorted().collect(Collectors.toList());
    }

    /**
     * 完成上传：块齐全校验 → 流式合并 → 完整摘要校验 → 原子落位 → 写库。
     */
    public fun.hatsumi.chatbackend.file.entity.StoredFileEntity complete(Long userId, String uploadId) {
        UploadSessionEntity session = requireOwnedSession(userId, uploadId);
        List<Integer> uploaded = uploadedChunkIndexes(uploadId);
        if (uploaded.size() != session.getTotalChunks()) {
            throw BusinessException.conflict("分块不完整：已传 " + uploaded.size() + "/" + session.getTotalChunks());
        }

        session.setStatus("MERGING");
        sessionMapper.updateById(session);

        try {
            var target = storage.randomStoredPath(session.getScope(), session.getOwnerId(), session.getFileName());
            var tempTarget = target.resolveSibling(target.getFileName() + ".merging");

            String mergedSha256 = storage.mergeChunks(uploadId, session.getTotalChunks(), tempTarget);

            String expected = session.getFileSha256();
            if (expected != null && !expected.isBlank() && !expected.equalsIgnoreCase(mergedSha256)) {
                storage.cleanupTemp(uploadId);
                storage.delete(relativeOf(tempTarget));
                throw BusinessException.unprocessable("完整文件 SHA-256 不一致");
            }

            storage.atomicMove(tempTarget, target);

            var file = new fun.hatsumi.chatbackend.file.entity.StoredFileEntity();
            file.setOwnerId("PUBLIC".equals(session.getScope()) ? null : session.getOwnerId());
            file.setScope(session.getScope());
            file.setOriginalName(session.getFileName());
            file.setStoredName(target.getFileName().toString());
            file.setRelativePath(relativeOf(target));
            file.setSizeBytes(session.getFileSize());
            file.setSha256(mergedSha256);
            file.setCreatedAt(LocalDateTime.now());
            fileMapper.insert(file);

            session.setStatus("COMPLETED");
            sessionMapper.updateById(session);
            storage.cleanupTemp(uploadId);
            log.info("Upload completed: uploadId={}, file={}, sha={}", uploadId, session.getFileName(), mergedSha256);
            return file;
        } catch (BusinessException e) {
            session.setStatus("INIT"); // 允许重试
            sessionMapper.updateById(session);
            throw e;
        }
    }

    /**
     * 定时清理过期上传会话及其临时分块（每小时一次）。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000)
    public void evictExpiredSessions() {
        List<UploadSessionEntity> expired = sessionMapper.selectList(new LambdaQueryWrapper<UploadSessionEntity>()
                .lt(UploadSessionEntity::getExpireAt, LocalDateTime.now())
                .ne(UploadSessionEntity::getStatus, "COMPLETED"));
        for (UploadSessionEntity session : expired) {
            storage.cleanupTemp(session.getUploadId());
            session.setStatus("EXPIRED");
            sessionMapper.updateById(session);
            log.info("Upload session expired: {}", session.getUploadId());
        }
    }

    // ---------------------------------------------------------------- helpers

    private UploadSessionEntity requireOwnedSession(Long userId, String uploadId) {
        UploadSessionEntity session = sessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSessionEntity>().eq(UploadSessionEntity::getUploadId, uploadId));
        if (session == null) {
            throw BusinessException.notFound("上传会话不存在");
        }
        if (!session.getOwnerId().equals(userId)) {
            throw BusinessException.forbidden("无权操作他人上传会话");
        }
        return session;
    }

    private synchronized boolean simulateLoss() {
        return errorRandom.nextDouble() < properties.getErrorSimulationRate();
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 计算流内容 SHA-256（下载校验/内部复用）。 */
    public static String sha256Hex(InputStream in) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String relativeOf(Path path) {
        return path.toString().replace('\\', '/');
    }
}
