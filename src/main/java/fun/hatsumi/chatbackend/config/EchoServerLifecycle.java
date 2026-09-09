package fun.hatsumi.chatbackend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import fun.hatsumi.chatbackend.network.tcp.TcpEchoServer;
import fun.hatsumi.chatbackend.network.udp.UdpEchoServer;

/**
 * Spring Boot 启动后异步拉起 TCP/UDP Echo 服务，关闭时优雅释放 Channel 与 EventLoopGroup。
 */
@Component
public class EchoServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EchoServerLifecycle.class);

    private final ChatroomProperties properties;

    private TcpEchoServer tcpEchoServer;

    private UdpEchoServer udpEchoServer;

    private volatile boolean running = false;

    public EchoServerLifecycle(ChatroomProperties properties) {
        this.properties = properties;
    }

    @Override
    public void start() {
        Thread starter = new Thread(() -> {
            try {
                tcpEchoServer = new TcpEchoServer(properties);
                tcpEchoServer.start();
            } catch (Exception e) {
                log.error("TCP echo server failed to start: {}", e.getMessage());
            }
            try {
                udpEchoServer = new UdpEchoServer(properties);
                udpEchoServer.start();
            } catch (Exception e) {
                log.error("UDP echo server failed to start: {}", e.getMessage());
            }
        }, "echo-server-starter");
        starter.setDaemon(true);
        starter.start();
        running = true;
    }

    @Override
    public void stop() {
        if (tcpEchoServer != null) {
            tcpEchoServer.stop();
        }
        if (udpEchoServer != null) {
            udpEchoServer.stop();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
