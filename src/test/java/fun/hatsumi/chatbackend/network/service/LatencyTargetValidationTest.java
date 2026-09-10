package fun.hatsumi.chatbackend.network.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fun.hatsumi.chatbackend.common.exception.BusinessException;

/**
 * 网络测试目标白名单校验：仅允许本机与私有网段，拒绝公网与域名。
 */
class LatencyTargetValidationTest {

    @Test
    @DisplayName("本机回环地址允许")
    void loopbackAllowed() {
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("localhost"));
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("127.0.0.1"));
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("::1"));
    }

    @Test
    @DisplayName("私有网段允许：10/8、172.16/12、192.168/16")
    void privateRangesAllowed() {
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("10.1.2.3"));
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("172.16.0.1"));
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("172.31.255.255"));
        assertDoesNotThrow(() -> LatencyTestService.validateTarget("192.168.1.10"));
    }

    @Test
    @DisplayName("公网地址拒绝，避免任意扫描接口")
    void publicAddressRejected() {
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget("8.8.8.8"));
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget("1.1.1.1"));
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget("172.32.0.1"));
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget("11.0.0.1"));
    }

    @Test
    @DisplayName("域名与空值拒绝（仅接受 IP 字面量）")
    void domainRejected() {
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget("example.com"));
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget(""));
        assertThrows(BusinessException.class, () -> LatencyTestService.validateTarget(null));
    }
}
