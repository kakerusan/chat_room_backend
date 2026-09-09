package fun.hatsumi.chatbackend.network.service;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.network.entity.NetworkTestRunEntity;
import fun.hatsumi.chatbackend.network.mapper.NetworkTestRunMapper;
import fun.hatsumi.chatbackend.network.protocol.TestPacket;

/**
 * TCP/UDP 延时测试：后端作为客户端向 Echo 服务发 PING，统计 RTT 并写 CSV。
 */
@Service
public class LatencyTestService {

    private static final Logger log = LoggerFactory.getLogger(LatencyTestService.class);

    private final ChatroomProperties properties;

    private final NetworkTestRunMapper runMapper;

    public LatencyTestService(ChatroomProperties properties, NetworkTestRunMapper runMapper) {
        this.properties = properties;
        this.runMapper = runMapper;
    }

    public record Sample(int sequence, Double rttMs, String status, long sentAtNs, long receivedAtNs) {
    }

    /**
     * 执行延时测试。返回 runId 与每个样本结果。
     */
    public Map<String, Object> run(String protocol, String host, int port, int samples, int intervalMs,
            int timeoutMs, String scenario) {
        validateTarget(host);
        List<Sample> result = "UDP".equals(protocol)
                ? runUdp(host, port, samples, intervalMs, timeoutMs)
                : runTcp(host, port, samples, intervalMs, timeoutMs);

        String runId = UUID.randomUUID().toString().replace("-", "");
        Path csvPath = writeCsv(runId, scenario, protocol, host, port, result);
        persistRun(runId, protocol, host, port, scenario, result, csvPath);

        List<Map<String, Object>> samplePayload = new ArrayList<>();
        for (Sample s : result) {
            samplePayload.add(Map.of(
                    "sequence", s.sequence(),
                    "rttMs", s.rttMs() == null ? "" : s.rttMs(),
                    "status", s.status()));
        }
        return Map.of("runId", runId, "protocol", protocol, "scenario", scenario, "samples", samplePayload);
    }

    // ---------------------------------------------------------------- TCP

    private List<Sample> runTcp(String host, int port, int samples, int intervalMs, int timeoutMs) {
        List<Sample> result = new ArrayList<>();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), Math.max(timeoutMs, 3000));
            socket.setSoTimeout(timeoutMs);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            for (int i = 0; i < samples; i++) {
                long sentAt = System.nanoTime();
                out.write(TestPacket.encode(TestPacket.TYPE_PING, i, sentAt, new byte[0]));
                out.flush();
                try {
                    byte[] frame = readFrame(in);
                    long receivedAt = System.nanoTime();
                    TestPacket packet = TestPacket.decode(frame);
                    if (packet.requestId == i) {
                        result.add(new Sample(i, round2((receivedAt - sentAt) / 1e6), "OK", sentAt, receivedAt));
                    } else {
                        result.add(new Sample(i, null, "MISMATCH", sentAt, receivedAt));
                    }
                } catch (SocketTimeoutException e) {
                    result.add(new Sample(i, null, "TIMEOUT", sentAt, System.nanoTime()));
                }
                if (intervalMs > 0 && i < samples - 1) {
                    Thread.sleep(intervalMs);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw BusinessException.badRequest("TCP 测试失败: " + e.getMessage());
        }
        return result;
    }

    /** 按协议头读一帧（粘包安全）。 */
    private byte[] readFrame(DataInputStream in) throws IOException {
        byte[] header = new byte[TestPacket.HEADER_SIZE];
        in.readFully(header);
        int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8)
                | (header[3] & 0xFF);
        if (magic != TestPacket.MAGIC) {
            throw new IOException("magic 不匹配");
        }
        int payloadLength = ((header[22] & 0xFF) << 24) | ((header[23] & 0xFF) << 16)
                | ((header[24] & 0xFF) << 8) | (header[25] & 0xFF);
        byte[] payload = new byte[payloadLength];
        in.readFully(payload);
        byte[] frame = new byte[TestPacket.HEADER_SIZE + payloadLength];
        System.arraycopy(header, 0, frame, 0, TestPacket.HEADER_SIZE);
        System.arraycopy(payload, 0, frame, TestPacket.HEADER_SIZE, payloadLength);
        return frame;
    }

    // ---------------------------------------------------------------- UDP

    private List<Sample> runUdp(String host, int port, int samples, int intervalMs, int timeoutMs) {
        List<Sample> result = new ArrayList<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress address = InetAddress.getByName(host);

            for (int i = 0; i < samples; i++) {
                long sentAt = System.nanoTime();
                byte[] frame = TestPacket.encode(TestPacket.TYPE_PING, i, sentAt, new byte[0]);
                socket.send(new DatagramPacket(frame, frame.length, address, port));
                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket response = new DatagramPacket(buf, buf.length);
                    socket.receive(response);
                    long receivedAt = System.nanoTime();
                    TestPacket packet = TestPacket.decode(trim(response));
                    if (packet.requestId == i) {
                        result.add(new Sample(i, round2((receivedAt - sentAt) / 1e6), "OK", sentAt, receivedAt));
                    } else {
                        result.add(new Sample(i, null, "MISMATCH", sentAt, receivedAt));
                    }
                } catch (SocketTimeoutException e) {
                    result.add(new Sample(i, null, "TIMEOUT", sentAt, System.nanoTime()));
                }
                if (intervalMs > 0 && i < samples - 1) {
                    Thread.sleep(intervalMs);
                }
            }
        } catch (IOException | InterruptedException e) {
            throw BusinessException.badRequest("UDP 测试失败: " + e.getMessage());
        }
        return result;
    }

    private static byte[] trim(DatagramPacket packet) {
        byte[] data = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
        return data;
    }

    // ---------------------------------------------------------------- 统计/CSV/DB

    private Path writeCsv(String runId, String scenario, String protocol, String host, int port,
            List<Sample> samples) {
        try {
            Path dir = Paths.get(properties.getLogRoot());
            Files.createDirectories(dir);
            Path csv = dir.resolve(runId + ".csv");
            StringBuilder sb = new StringBuilder(
                    "runId,scenario,protocol,target,sequence,sentAtNs,receivedAtNs,rttMs,status\r\n");
            for (Sample s : samples) {
                sb.append(runId).append(',').append(scenario).append(',').append(protocol).append(',')
                        .append(host).append(':').append(port).append(',')
                        .append(s.sequence()).append(',').append(s.sentAtNs()).append(',')
                        .append(s.receivedAtNs()).append(',')
                        .append(s.rttMs() == null ? "" : s.rttMs()).append(',')
                        .append(s.status()).append("\r\n");
            }
            Files.writeString(csv, sb.toString(), StandardCharsets.UTF_8);
            return csv;
        } catch (IOException e) {
            log.warn("CSV write failed: {}", e.getMessage());
            return null;
        }
    }

    private void persistRun(String runId, String protocol, String host, int port, String scenario,
            List<Sample> samples, Path csvPath) {
        try {
            List<Double> ok = samples.stream().filter(s -> s.rttMs() != null).map(Sample::rttMs).sorted().toList();
            NetworkTestRunEntity run = new NetworkTestRunEntity();
            run.setTestType("LATENCY");
            run.setProtocol(protocol);
            run.setTargetHost(host);
            run.setTargetPort(port);
            run.setScenarioName(scenario);
            run.setStartedAt(LocalDateTime.now());
            run.setFinishedAt(LocalDateTime.now());
            if (!ok.isEmpty()) {
                double mean = ok.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                double variance = ok.size() > 1
                        ? ok.stream().mapToDouble(v -> (v - mean) * (v - mean)).sum() / (ok.size() - 1)
                        : 0;
                run.setStatMinMs(ok.get(0));
                run.setStatMaxMs(ok.get(ok.size() - 1));
                run.setStatAvgMs(round2(mean));
                run.setStatVariance(round2(variance));
                run.setStatStddev(round2(Math.sqrt(variance)));
                run.setStatP50Ms(ok.get(Math.min(ok.size() - 1, (int) Math.ceil(ok.size() * 0.50) - 1)));
                run.setStatP95Ms(ok.get(Math.min(ok.size() - 1, (int) Math.ceil(ok.size() * 0.95) - 1)));
            }
            long lost = samples.stream().filter(s -> s.rttMs() == null).count();
            run.setPacketLossRate(samples.isEmpty() ? 0.0 : (double) lost / samples.size());
            run.setErrorCount((int) lost);
            run.setCsvPath(csvPath == null ? null : csvPath.toString());
            runMapper.insert(run);
        } catch (Exception e) {
            log.warn("Persist run failed: {}", e.getMessage());
        }
    }

    /**
     * 网络测试目标限制：仅允许本机与私有网段，避免形成任意公网扫描接口。
     */
    static void validateTarget(String host) {
        if (host == null || host.isBlank()) {
            throw BusinessException.badRequest("目标主机不能为空");
        }
        boolean allowed = switch (host) {
            case "localhost", "127.0.0.1", "::1", "0.0.0.0" -> true;
            default -> {
                if (!host.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
                    yield false; // 仅 IP 字面量；域名一律拒绝
                }
                String[] parts = host.split("\\.");
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                yield first == 10 // 10.0.0.0/8
                        || (first == 172 && second >= 16 && second <= 31) // 172.16/12
                        || (first == 192 && second == 168); // 192.168/16
            }
        };
        if (!allowed) {
            throw BusinessException.badRequest("测试目标仅限本机与私有网段地址");
        }
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
