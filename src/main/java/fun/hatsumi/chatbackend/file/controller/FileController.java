package fun.hatsumi.chatbackend.file.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fun.hatsumi.chatbackend.auth.interceptor.UserContext;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.file.entity.StoredFileEntity;
import fun.hatsumi.chatbackend.file.service.FileService;

/**
 * 文件列表 / 下载（含 Range 续传）/ 删除。
 */
@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "PUBLIC") String scope) {
        return ApiResponse.ok(fileService.listFiles(UserContext.currentUserId(), scope));
    }

    @GetMapping("/{fileId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable Long fileId,
            org.springframework.http.HttpEntity<Void> entity) {
        StoredFileEntity file = fileService.authorizeDownload(UserContext.get(), fileId);

        long fileSize = fileService.storage().size(file.getRelativePath());
        String rangeHeader = entity.getHeaders().getFirst(HttpHeaders.RANGE);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        String encodedName = java.net.URLEncoder.encode(file.getOriginalName(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + encodedName);

        // Range: bytes=start-[end]
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            long start = 0;
            long end = fileSize - 1;
            try {
                String spec = rangeHeader.substring("bytes=".length()).split(",")[0].trim();
                int dash = spec.indexOf('-');
                if (dash == 0) {
                    // bytes=-N：最后 N 字节
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, fileSize - suffix);
                } else {
                    start = Long.parseLong(spec.substring(0, dash));
                    if (dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1));
                    }
                }
            } catch (NumberFormatException e) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
            }
            if (start > end || start >= fileSize) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileSize).build();
            }
            end = Math.min(end, fileSize - 1);

            final long skip = start;
            final long length = end - start + 1;
            InputStream in = fileService.storage().openStream(file.getRelativePath());
            try {
                long skipped = in.skip(skip);
                if (skipped < skip) {
                    in.close();
                    return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
                }
            } catch (IOException e) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                throw new RuntimeException(e);
            }
            headers.setContentLength(length);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileSize);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .headers(headers)
                    .body(new InputStreamResource(in));
        }

        InputStream in = fileService.storage().openStream(file.getRelativePath());
        headers.setContentLength(fileSize);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .headers(headers)
                .body(new InputStreamResource(in));
    }

    @DeleteMapping("/{fileId}")
    public ApiResponse<Void> delete(@PathVariable Long fileId) {
        fileService.deleteFile(UserContext.get(), fileId);
        return ApiResponse.ok();
    }
}
