package fun.hatsumi.chatbackend.network.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 网络测试运行记录实体（network_test_runs），原始样本另存 CSV。
 */
@Data
@TableName("network_test_runs")
public class NetworkTestRunEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("test_type")
    private String testType;

    @TableField("protocol")
    private String protocol;

    @TableField("target_host")
    private String targetHost;

    @TableField("target_port")
    private Integer targetPort;

    @TableField("params")
    private String params;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField("stat_min_ms")
    private Double statMinMs;

    @TableField("stat_max_ms")
    private Double statMaxMs;

    @TableField("stat_avg_ms")
    private Double statAvgMs;

    @TableField("stat_variance")
    private Double statVariance;

    @TableField("stat_stddev")
    private Double statStddev;

    @TableField("stat_p50_ms")
    private Double statP50Ms;

    @TableField("stat_p95_ms")
    private Double statP95Ms;

    @TableField("throughput_mbps")
    private Double throughputMbps;

    @TableField("packet_loss_rate")
    private Double packetLossRate;

    @TableField("error_count")
    private Integer errorCount;

    @TableField("scenario_name")
    private String scenarioName;

    @TableField("csv_path")
    private String csvPath;
}
