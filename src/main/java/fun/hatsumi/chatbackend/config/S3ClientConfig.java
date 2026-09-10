package fun.hatsumi.chatbackend.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3 客户端装配：仅 chatroom.storage.type=s3 时生效。
 *
 * <p>面向 RustFS（S3 兼容存储）的必要配置：</p>
 * <ul>
 *   <li>forcePathStyle：RustFS 默认 path-style 访问；</li>
 *   <li>WHEN_REQUIRED 校验策略：AWS SDK v2 &ge; 2.30.0 默认对 PutObject 附带 CRC32
 *       校验头，会导致部分 S3 兼容存储拒绝请求，必须显式关闭。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "chatroom.storage.type", havingValue = "s3")
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(ChatroomProperties properties) {
        ChatroomProperties.Storage storage = properties.getStorage();
        return S3Client.builder()
                .endpointOverride(URI.create(storage.getEndpoint()))
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }
}
