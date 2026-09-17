package fun.hatsumi.chatbackend.file.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.auth.interceptor.CurrentUser;
import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.file.entity.StoredFileEntity;
import fun.hatsumi.chatbackend.file.mapper.StoredFileMapper;
import fun.hatsumi.chatbackend.file.storage.StorageBackend;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.mapper.UserMapper;

/**
 * 文件业务：列表、授权下载、删除。授权只按服务端解析的 Token 用户判断，不信任客户端参数。
 */
@Service
public class FileService {

    private final StoredFileMapper fileMapper;

    private final UserMapper userMapper;

    private final StorageBackend storage;

    public FileService(StoredFileMapper fileMapper, UserMapper userMapper, StorageBackend storage) {
        this.fileMapper = fileMapper;
        this.userMapper = userMapper;
        this.storage = storage;
    }

    /**
     * 文件列表：PUBLIC 对所有登录用户可见；PRIVATE 只返回本人文件。
     */
    public List<Map<String, Object>> listFiles(Long userId, String scope) {
        LambdaQueryWrapper<StoredFileEntity> wrapper = new LambdaQueryWrapper<StoredFileEntity>()
                .eq(StoredFileEntity::getScope, scope)
                .orderByDesc(StoredFileEntity::getId);
        if ("PRIVATE".equals(scope)) {
            wrapper.eq(StoredFileEntity::getOwnerId, userId);
        }
        List<StoredFileEntity> files = fileMapper.selectList(wrapper);

        // 一次查出涉及的 owner 展示名
        Map<Long, String> nameById = new HashMap<>();
        List<Long> ownerIds = files.stream().map(StoredFileEntity::getOwnerId).filter(id -> id != null).distinct().toList();
        if (!ownerIds.isEmpty()) {
            for (UserEntity u : userMapper.selectBatchIds(ownerIds)) {
                nameById.put(u.getId(), u.getDisplayName());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (StoredFileEntity f : files) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", f.getId());
            item.put("scope", f.getScope());
            item.put("originalName", f.getOriginalName());
            item.put("sizeBytes", f.getSizeBytes());
            item.put("sha256", f.getSha256());
            item.put("uploader", f.getOwnerId() == null ? "公共" : nameById.getOrDefault(f.getOwnerId(), "未知用户"));
            item.put("createdAt", f.getCreatedAt() == null ? null : f.getCreatedAt().toString());
            result.add(item);
        }
        return result;
    }

    /**
     * 下载授权检查：返回实体（含相对路径）。PRIVATE 仅 owner 可访问。
     */
    public StoredFileEntity authorizeDownload(CurrentUser user, Long fileId) {
        StoredFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            throw BusinessException.notFound("文件不存在");
        }
        if ("PRIVATE".equals(file.getScope()) && !file.getOwnerId().equals(user.getUserId())) {
            throw BusinessException.forbidden("无权访问他人私人文件");
        }
        return file;
    }

    /**
     * 删除：私人文件仅 owner；公共文件需 ADMIN。同时删除磁盘文件。
     */
    public void deleteFile(CurrentUser user, Long fileId) {
        StoredFileEntity file = fileMapper.selectById(fileId);
        if (file == null) {
            throw BusinessException.notFound("文件不存在");
        }
        boolean owner = file.getOwnerId() != null && file.getOwnerId().equals(user.getUserId());
        if ("PRIVATE".equals(file.getScope()) ? !owner : !"ADMIN".equals(user.getRole())) {
            throw BusinessException.forbidden("无权删除该文件");
        }
        fileMapper.deleteById(fileId);
        storage.delete(file.getRelativePath());
    }

    public StorageBackend backend() {
        return storage;
    }
}
