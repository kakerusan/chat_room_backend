package fun.hatsumi.chatbackend.network.tcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.network.protocol.TestPacket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Netty TCP Echo 服务（默认 9001）：
 * LengthFieldBasedFrameDecoder 处理粘包/半包；PING→PONG，DATA 原样回弹。
 */
public class TcpEchoServer {

    private static final Logger log = LoggerFactory.getLogger(TcpEchoServer.class);

    private final int port;

    private NioEventLoopGroup bossGroup;

    private NioEventLoopGroup workerGroup;

    private io.netty.channel.Channel serverChannel;

    public TcpEchoServer(ChatroomProperties properties) {
        this.port = properties.getTcpPort();
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 头 26 字节：length 字段位于偏移 22，长度 4 字节，帧含完整头
                        ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(1 << 20, 22, 4, 0, 0));
                        ch.pipeline().addLast(new EchoHandler());
                    }
                });
        serverChannel = bootstrap.bind(port).sync().channel();
        log.info("TCP echo server started on port {}", port);
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        log.info("TCP echo server stopped");
    }

    /**
     * 回弹逻辑：PING→PONG，DATA 原样，其余丢弃（异常报文只影响当前请求）。
     */
    static class EchoHandler extends SimpleChannelInboundHandler<ByteBuf> {

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf frame) {
            if (frame.readableBytes() < TestPacket.HEADER_SIZE) {
                return;
            }
            byte type = frame.getByte(5);
            byte[] bytes = new byte[frame.readableBytes()];
            frame.getBytes(0, bytes);

            byte replyType;
            switch (type) {
                case TestPacket.TYPE_PING -> replyType = TestPacket.TYPE_PONG;
                case TestPacket.TYPE_DATA -> replyType = TestPacket.TYPE_DATA;
                default -> {
                    return;
                }
            }
            ctx.writeAndFlush(Unpooled.wrappedBuffer(TestPacket.rewriteType(bytes, replyType)));
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
