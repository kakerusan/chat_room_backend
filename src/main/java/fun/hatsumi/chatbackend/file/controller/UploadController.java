package fun.hatsumi.chatbackend.file.controller;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.hatsumi.chatbackend.auth.interceptor.UserContext;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.file.entity.StoredFileEntity;
import fun.hatsumi.chatbackend.file.entity.UploadSessionEntity;
import fun.hatsumi.chatbackend.file.service.UploadService;

/**
 * 分块上传：创建会话 / 上传分块 / 查询缺块 / 合并完成。
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    public record CreateUploadRequest(String fileName, Long fileSize, Integer chunkSize,
            Integer totalChunks, String fileSha256, String scope) {
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody CreateUploadRequest request) {
        if (request.fileName() == null || request.fileSize() == null || request.totalChunks() == null
                || request.totalChunks() <= 0) {
            throw BusinessException.badRequest("fileName / fileSize / totalChunks 必填");
        }
        UploadSessionEntity session = uploadService.createSession(
                UserContext.currentUserId(),
                request.fileName(),
                request.fileSize(),
                request.chunkSize() == null ? UploadService.MIN_CHUNK_SIZE : request.chunkSize(),
                request.totalChunks(),
                request.fileSha256(),
                request.scope() == null ? "PUBLIC" : request.scope());
        // chunkSize 回传：客户端必须以服务端实际生效值为准切块
        return ApiResponse.ok(Map.of(
                "uploadId", session.getUploadId(),
                "uploadedChunks", List.of(),
                "chunkSize", session.getChunkSize()));
    }

    /**
     * 上传一个分块：二进制请求体 + X-Chunk-SHA256 头。
     */
    @PutMapping("/{uploadId}/chunks/{index}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> uploadChunk(
            @PathVariable String uploadId,
            @PathVariable int index,
            @RequestHeader(value = "X-Chunk-SHA256", required = false) String chunkSha256,
            java.io.InputStream body) {
        byte[] data;
        try (body; var out = new ByteArrayOutputStream()) {
            body.transferTo(out);
            data = out.toByteArray();
        } catch (java.io.IOException e) {
            throw BusinessException.badRequest("读取分块失败");
        }
        uploadService.uploadChunk(UserContext.currentUserId(), uploadId, index, data, chunkSha256);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.ok(Map.of("chunkIndex", index)));
    }

    @GetMapping("/{uploadId}")
    public ApiResponse<?> query(@PathVariable String uploadId) {
        List<Integer> uploaded = uploadService.uploadedChunkIndexes(uploadId);
        return ApiResponse.ok(Map.of("uploadedChunks", uploaded));
    }

    @PostMapping("/{uploadId}/complete")
    public ApiResponse<?> complete(@PathVariable String uploadId) {
        StoredFileEntity file = uploadService.complete(UserContext.currentUserId(), uploadId);
        return ApiResponse.ok(Map.of(
                "fileId", file.getId(),
                "sha256", file.getSha256()));
    }
}
