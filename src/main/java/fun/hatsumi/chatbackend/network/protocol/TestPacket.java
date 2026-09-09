package fun.hatsumi.chatbackend.network.protocol;

import java.nio.ByteBuffer;

/**
 * 统一二进制测试报文（Big Endian）：
 * magic(4B)=0x43484154 | version(1B)=1 | type(1B) | requestId(8B) | sentAtNs(8B) | payloadLength(4B) | payload
 */
public final class TestPacket {

    public static final int MAGIC = 0x43484154;

    public static final byte VERSION = 1;

    /** 消息类型。 */
    public static final byte TYPE_PING = 1;

    public static final byte TYPE_PONG = 2;

    public static final byte TYPE_DATA = 3;

    public static final byte TYPE_END = 4;

    public static final int HEADER_SIZE = 4 + 1 + 1 + 8 + 8 + 4;

    public final byte type;

    public final long requestId;

    public final long sentAtNs;

    public final byte[] payload;

    private TestPacket(byte type, long requestId, long sentAtNs, byte[] payload) {
        this.type = type;
        this.requestId = requestId;
        this.sentAtNs = sentAtNs;
        this.payload = payload;
    }

    public static TestPacket of(byte type, long requestId, long sentAtNs, byte[] payload) {
        return new TestPacket(type, requestId, sentAtNs, payload == null ? new byte[0] : payload);
    }

    public static byte[] encode(byte type, long requestId, long sentAtNs, byte[] payload) {
        byte[] data = payload == null ? new byte[0] : payload;
        ByteBuffer buf = ByteBuffer.allocate(HEADER_SIZE + data.length);
        buf.putInt(MAGIC);
        buf.put(VERSION);
        buf.put(type);
        buf.putLong(requestId);
        buf.putLong(sentAtNs);
        buf.putInt(data.length);
        buf.put(data);
        return buf.array();
    }

    /**
     * 解码完整帧（含头）。
     */
    public static TestPacket decode(byte[] frame) {
        if (frame.length < HEADER_SIZE) {
            throw new IllegalArgumentException("帧长度不足");
        }
        ByteBuffer buf = ByteBuffer.wrap(frame);
        int magic = buf.getInt();
        if (magic != MAGIC) {
            throw new IllegalArgumentException("magic 不匹配: " + Integer.toHexString(magic));
        }
        buf.get(); // version
        byte type = buf.get();
        long requestId = buf.getLong();
        long sentAtNs = buf.getLong();
        int len = buf.getInt();
        if (frame.length < HEADER_SIZE + len) {
            throw new IllegalArgumentException("payload 长度不完整");
        }
        byte[] payload = new byte[len];
        buf.get(payload);
        return new TestPacket(type, requestId, sentAtNs, payload);
    }

    /**
     * 把帧中 type 字段改写（echo 回弹：PING→PONG，DATA 原样）。
     */
    public static byte[] rewriteType(byte[] frame, byte newType) {
        byte[] copy = frame.clone();
        copy[5] = newType;
        return copy;
    }
}
