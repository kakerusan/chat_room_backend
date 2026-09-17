package fun.hatsumi.chatbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * chatroom.* 业务配置项。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chatroom")
public class ChatroomProperties {

    /** 文件存储根目录。 */
    private String storageRoot = "./data/storage";

    /** 网络测试 CSV 日志目录。 */
    private String logRoot = "./data/logs/network-tests";

    /** 分块上传差错模拟概率（0.0~1.0）。 */
    private double errorSimulationRate = 0.20;

    /** 差错模拟随机种子，null/未配置表示运行时随机。 */
    private Long errorSimulationSeed;

    /** TCP Echo 服务端口。 */
    private int tcpPort = 9001;

    /** UDP Echo 服务端口。 */
    private int udpPort = 9002;

    /** Blocking 基准服务端口。 */
    private int blockingBenchmarkPort = 9101;

    /** NIO 基准服务端口。 */
    private int nioBenchmarkPort = 9102;

    /** 文件存储后端配置。 */
    private Storage storage = new Storage();

    /** WebSocket 相关参数。 */
    private Ws ws = new Ws();

    /**
     * 文件存储后端：local（本地磁盘，缺省）/ s3（RustFS 对象存储）。
     */
    @Data
    public static class Storage {

        /** local | s3。 */
        private String type = "local";

        /** S3 API 端点（仅 s3 模式生效）。 */
        private String endpoint = "http://localhost:9000";

        /** 访问密钥。 */
        private String accessKey = "chatroom-dev";

        /** 私有密钥。 */
        private String secretKey = "chatroom-dev-secret";

        /** 存储桶名。 */
        private String bucket = "chatroom-files";

        /** 签名区域（RustFS 默认 us-east-1）。 */
        private String region = "us-east-1";
    }

    @Data
    public static class Ws {

        /** 连接建立后首帧 AUTH 超时（秒）。 */
        private int authTimeoutSeconds = 5;

        /** 心跳间隔（秒）。 */
        private int heartbeatSeconds = 20;

        /** 无响应清理时间（秒）。 */
        private int idleTimeoutSeconds = 60;

        /** 单条文本消息大小上限（字节）。 */
        private int maxTextMessageSize = 65536;
    }
}
