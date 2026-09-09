package fun.hatsumi.chatbackend.network.service;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.network.entity.NetworkTestRunEntity;
import fun.hatsumi.chatbackend.network.mapper.NetworkTestRunMapper;
import fun.hatsumi.chatbackend.network.protocol.TestPacket;

/**
 * 带宽测试：TCP 连续发送 DATA 计吞吐；UDP 按序发包统计丢包/乱序。
 */
@Service
public class BandwidthTestService {

    private static final Logger log = LoggerFactory.getLogger(BandwidthTestService.class);

    /** UDP 单报文负载上限（避免 IP 分片）。 */
    private static final int UDP_MAX_PAYLOAD = 1200 - TestPacket.HEADER_SIZE;

    private final ChatroomProperties properties;

    private final NetworkTestRunMapper runMapper;

    public BandwidthTestService(ChatroomProperties properties, NetworkTestRunMapper runMapper) {
        this.properties = properties;
        this.runMapper = runMapper;
    }

    public Map<String, Object> run(String protocol, String host, int port, int durationSeconds, int blockSize) {
        LatencyTestService.validateTarget(host);
        if (durationSeconds < 1 || durationSeconds > 60) {
            throw BusinessException.badRequest("持续时间需在 1~60 秒");
        }
        return "UDP".equals(protocol)
                ? runUdp(host, port, durationSeconds, blockSize)
                : runTcp(host, port, durationSeconds, blockSize);
    }

    // ---------------------------------------------------------------- TCP

    private Map<String, Object> runTcp(String host, int port, int durationSeconds, int blockSize) {
        if (blockSize < 1 || blockSize > 1024 * 1024) {
            blockSize = 64 * 1024;
        }
        byte[] payload = new byte[blockSize];

        long sentBytes = 0;
        long receivedBytes = 0;
        long durationMs;
        long startedAt = System.nanoTime();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(1000); // 读取回弹数据用较短超时
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // 发送阶段：持续 durationSeconds 连发 DATA 帧
            long sendDeadline = System.nanoTime() + durationSeconds * 1_000_000_000L;
            long seq = 0;
            while (System.nanoTime() < sendDeadline) {
                byte[] frame = TestPacket.encode(TestPacket.TYPE_DATA, seq, System.nanoTime(), payload);
                out.write(frame);
                out.flush();
                sentBytes += frame.length;
                seq += 1;
            }

            // 接收阶段：读尽回弹（最多再等 3 秒收尾）
            socket.setSoTimeout(3000);
            long readDeadline = System.currentTimeMillis() + 3000;
            try {
                while (System.currentTimeMillis() < readDeadline) {
                    byte[] frame = readFrame(in);
                    if (frame == null) {
                        break;
                    }
                    receivedBytes += frame.length;
                }
            } catch (SocketTimeoutException ignored) {
                // 收尾
            }
            durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        } catch (IOException e) {
            throw BusinessException.badRequest("TCP 带宽测试失败: " + e.getMessage());
        }

        double mbps = LatencyTestService.round2(receivedBytes * 8 / (durationMs / 1000.0) / 1e6);
        double mibps = LatencyTestService.round2(receivedBytes / (durationMs / 1000.0) / (1024 * 1024));

        String runId = UUID.randomUUID().toString().replace("-", "");
        persist(runId, "TCP", host, port, mbps, 0, 0);
        return Map.of(
                "runId", runId,
                "protocol", "TCP",
                "throughputMbps", mbps,
                "throughputMiBps", mibps,
                "sentBytes", sentBytes,
                "receivedBytes", receivedBytes,
                "lossRate", 0.0,
                "disorderRate", 0.0,
                "durationMs", durationMs);
    }

    private byte[] readFrame(java.io.DataInputStream in) throws IOException {
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

    private Map<String, Object> runUdp(String host, int port, int durationSeconds, int blockSize) {
        int payloadSize = Math.min(Math.max(blockSize, 64), UDP_MAX_PAYLOAD);
        byte[] payload = new byte[payloadSize];

        long sent = 0;
        long received = 0;
        long disorder = 0;
        long durationMs;
        long startedAt = System.nanoTime();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(10);
            InetAddress address = InetAddress.getByName(host);

            // 接收线程：统计回弹包的 seq（requestId 字段携带序号）
            AtomicLong receivedCounter = new AtomicLong();
            AtomicLong disorderCounter = new AtomicLong();
            AtomicLong lastSeq = new AtomicLong(-1);
            AtomicBoolean sending = new AtomicBoolean(true);
            byte[] buf = new byte[2048];
            Thread receiver = new Thread(() -> {
                while (sending.get()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                        TestPacket p = TestPacket.decode(data);
                        receivedCounter.incrementAndGet();
                        long prev = lastSeq.get();
                        if (prev >= 0 && p.requestId <= prev) {
                            disorderCounter.incrementAndGet();
                        }
                        lastSeq.set(p.requestId);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // 继续收
                    } catch (IOException | IllegalArgumentException e) {
                        break;
                    }
                }
                // 发送结束后再收 1 秒尾包
                long deadline = System.currentTimeMillis() + 1000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        socket.receive(packet);
                        byte[] data = new byte[packet.getLength()];
                        System.arraycopy(packet.getData(), packet.getOffset(), data, 0, packet.getLength());
                        TestPacket p = TestPacket.decode(data);
                        receivedCounter.incrementAndGet();
                        long prev = lastSeq.get();
                        if (prev >= 0 && p.requestId <= prev) {
                            disorderCounter.incrementAndGet();
                        }
                        lastSeq.set(p.requestId);
                    } catch (java.net.SocketTimeoutException ignored) {
                        // 继续
                    } catch (IOException | IllegalArgumentException e) {
                        break;
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();

            // 发送阶段：durationSeconds 内尽量发送（微间隔避免撑爆发送缓冲）
            long sendDeadline = System.nanoTime() + durationSeconds * 1_000_000_000L;
            long seq = 0;
            byte[] frame = TestPacket.encode(TestPacket.TYPE_DATA, seq, System.nanoTime(), payload);
            while (System.nanoTime() < sendDeadline) {
                frame = TestPacket.encode(TestPacket.TYPE_DATA, seq, System.nanoTime(), payload);
                socket.send(new DatagramPacket(frame, frame.length, address, port));
                sent += 1;
                seq += 1;
                if (seq % 64 == 0) {
                    Thread.sleep(0, 200_000); // ~0.2ms 微间隔
                }
            }
            sending.set(false);
            receiver.join(2000);
            durationMs = (System.nanoTime() - startedAt) / 1_000_000;

            received = receivedCounter.get();
            disorder = disorderCounter.get();
        } catch (IOException | InterruptedException e) {
            throw BusinessException.badRequest("UDP 带宽测试失败: " + e.getMessage());
        }

        long receivedBytes = received * (TestPacket.HEADER_SIZE + payloadSize);
        double mbps = LatencyTestService.round2(receivedBytes * 8 / (durationMs / 1000.0) / 1e6);
        double mibps = LatencyTestService.round2(receivedBytes / (durationMs / 1000.0) / (1024 * 1024));
        double lossRate = sent == 0 ? 0 : LatencyTestService.round2((double) (sent - received) / sent);
        double disorderRate = received == 0 ? 0 : LatencyTestService.round2((double) disorder / received);

        String runId = UUID.randomUUID().toString().replace("-", "");
        persist(runId, "UDP", host, port, mbps, lossRate, disorderRate);
        return Map.of(
                "runId", runId,
                "protocol", "UDP",
                "throughputMbps", mbps,
                "throughputMiBps", mibps,
                "sentBytes", sent * (TestPacket.HEADER_SIZE + payloadSize),
                "receivedBytes", receivedBytes,
                "lossRate", lossRate,
                "disorderRate", disorderRate,
                "durationMs", durationMs);
    }

    private void persist(String runId, String protocol, String host, int port, double mbps,
            double lossRate, double disorderRate) {
        try {
            NetworkTestRunEntity run = new NetworkTestRunEntity();
            run.setTestType("BANDWIDTH");
            run.setProtocol(protocol);
            run.setTargetHost(host);
            run.setTargetPort(port);
            run.setThroughputMbps(mbps);
            run.setPacketLossRate(lossRate);
            run.setStartedAt(LocalDateTime.now());
            run.setFinishedAt(LocalDateTime.now());
            runMapper.insert(run);
        } catch (Exception e) {
            log.warn("Persist bandwidth run failed: {}", e.getMessage());
        }
    }
}
