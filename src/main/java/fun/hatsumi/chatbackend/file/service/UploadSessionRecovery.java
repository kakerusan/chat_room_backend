package fun.hatsumi.chatbackend.file.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.file.entity.UploadSessionEntity;
import fun.hatsumi.chatbackend.file.mapper.UploadSessionMapper;
import fun.hatsumi.chatbackend.file.storage.StorageBackend;

/**
 * 启动恢复：清理上次运行遗留的未完成上传会话。
 *
 * <p>应用崩溃/重启后，INIT/MERGING 状态会话的后端上传上下文（S3 multipart）
 * 已不可信，统一 abort 并标记 EXPIRED，客户端重新发起上传。
 * local 模式下 backendUploadId 为空，abortUpload 退化为临时目录清理。</p>
 */
@Component
public class UploadSessionRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UploadSessionRecovery.class);

    private final UploadSessionMapper sessionMapper;

    private final StorageBackend storage;

    public UploadSessionRecovery(UploadSessionMapper sessionMapper, StorageBackend storage) {
        this.sessionMapper = sessionMapper;
        this.storage = storage;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<UploadSessionEntity> dangling = sessionMapper.selectList(new LambdaQueryWrapper<UploadSessionEntity>()
                .in(UploadSessionEntity::getStatus, "INIT", "MERGING"));
        for (UploadSessionEntity session : dangling) {
            try {
                storage.abortUpload(session.getUploadId(), session.getBackendUploadId(), session.getTargetKey());
                storage.cleanupUpload(session.getUploadId());
                session.setStatus("EXPIRED");
                sessionMapper.updateById(session);
                log.info("Recovered dangling upload session: {} -> EXPIRED", session.getUploadId());
            } catch (Exception e) {
                // 单会话恢复失败不阻断启动
                log.warn("Recover session {} failed: {}", session.getUploadId(), e.getMessage());
            }
        }
        if (!dangling.isEmpty()) {
            log.info("Upload session recovery finished: {} dangling session(s) expired", dangling.size());
        }
    }
}
