package fun.hatsumi.chatbackend.network.udp;

import java.net.InetSocketAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.network.protocol.TestPacket;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;

/**
 * Netty UDP Echo 服务（默认 9002）：单报文（≤1200B 建议）直接回弹。
 */
public class UdpEchoServer {

    private static final Logger log = LoggerFactory.getLogger(UdpEchoServer.class);

    private final int port;

    private EventLoopGroup group;

    private Channel channel;

    public UdpEchoServer(ChatroomProperties properties) {
        this.port = properties.getUdpPort();
    }

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast(new EchoHandler());
                    }
                });
        channel = bootstrap.bind(port).sync().channel();
        log.info("UDP echo server started on port {}", port);
    }

    public void stop() {
        if (channel != null) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        log.info("UDP echo server stopped");
    }

    static class EchoHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
            byte[] bytes = new byte[packet.content().readableBytes()];
            packet.content().readBytes(bytes);
            if (bytes.length < TestPacket.HEADER_SIZE) {
                return;
            }
            byte type = bytes[5];
            byte replyType;
            switch (type) {
                case TestPacket.TYPE_PING -> replyType = TestPacket.TYPE_PONG;
                case TestPacket.TYPE_DATA -> replyType = TestPacket.TYPE_DATA;
                default -> {
                    return;
                }
            }
            InetSocketAddress sender = packet.sender();
            ctx.writeAndFlush(new DatagramPacket(
                    Unpooled.wrappedBuffer(TestPacket.rewriteType(bytes, replyType)), sender));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // UDP 无连接，异常不关闭通道
            log.debug("UDP packet error: {}", cause.getMessage());
        }
    }
}
