package fun.hatsumi.chatbackend.network.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 二进制协议编解码测试（Big Endian / 拆包安全由帧头 length 保证）。
 */
class TestPacketTest {

    @Test
    @DisplayName("编码后可无损解码：字段逐项一致")
    void encode_decode_roundtrip() {
        byte[] payload = "hello-network-lab".getBytes(StandardCharsets.UTF_8);
        byte[] frame = TestPacket.encode(TestPacket.TYPE_PING, 42L, 123456789L, payload);

        TestPacket packet = TestPacket.decode(frame);
        assertEquals(TestPacket.TYPE_PING, packet.type);
        assertEquals(42L, packet.requestId);
        assertEquals(123456789L, packet.sentAtNs);
        assertArrayEquals(payload, packet.payload);
    }

    @Test
    @DisplayName("头长度固定 26 字节，帧总长 = 头 + 负载")
    void headerSize() {
        byte[] frame = TestPacket.encode(TestPacket.TYPE_DATA, 1, 1, new byte[100]);
        assertEquals(26, TestPacket.HEADER_SIZE);
        assertEquals(126, frame.length);
    }

    @Test
    @DisplayName("magic 不匹配拒绝解码")
    void decode_badMagic_rejected() {
        byte[] frame = TestPacket.encode(TestPacket.TYPE_PING, 1, 1, new byte[0]);
        frame[0] = 0; // 破坏 magic
        assertThrows(IllegalArgumentException.class, () -> TestPacket.decode(frame));
    }

    @Test
    @DisplayName("半包（长度不足）拒绝解码")
    void decode_shortFrame_rejected() {
        byte[] frame = TestPacket.encode(TestPacket.TYPE_PING, 1, 1, new byte[10]);
        byte[] truncated = new byte[15];
        System.arraycopy(frame, 0, truncated, 0, 15);
        assertThrows(IllegalArgumentException.class, () -> TestPacket.decode(truncated));
    }

    @Test
    @DisplayName("rewriteType 只改 type 字段，其余原样（PING→PONG 回弹）")
    void rewriteType_pingToPong() {
        byte[] frame = TestPacket.encode(TestPacket.TYPE_PING, 7, 99, new byte[]{1, 2, 3});
        byte[] pong = TestPacket.rewriteType(frame, TestPacket.TYPE_PONG);

        TestPacket packet = TestPacket.decode(pong);
        assertEquals(TestPacket.TYPE_PONG, packet.type);
        assertEquals(7L, packet.requestId);
        assertArrayEquals(new byte[]{1, 2, 3}, packet.payload);
        // 原帧不被修改
        assertEquals(TestPacket.TYPE_PING, TestPacket.decode(frame).type);
    }
}
