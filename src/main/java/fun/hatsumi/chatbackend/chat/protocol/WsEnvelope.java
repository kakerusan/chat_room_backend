package fun.hatsumi.chatbackend.chat.protocol;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 消息信封：{"type","requestId","timestamp","payload"}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WsEnvelope {

    private String type;

    private String requestId;

    private long timestamp;

    private Map<String, Object> payload;

    public static WsEnvelope of(String type, String requestId, Map<String, Object> payload) {
        return new WsEnvelope(type, requestId, System.currentTimeMillis(), payload == null ? Map.of() : payload);
    }
}
