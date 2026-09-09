package fun.hatsumi.chatbackend.network.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.network.service.BandwidthTestService;
import fun.hatsumi.chatbackend.network.service.LatencyTestService;

/**
 * 网络测试入口：前端发起后端任务并展示结果（浏览器不直接建 Socket）。
 */
@RestController
@RequestMapping("/api/network-tests")
public class NetworkTestController {

    private final LatencyTestService latencyTestService;

    private final BandwidthTestService bandwidthTestService;

    public NetworkTestController(LatencyTestService latencyTestService,
            BandwidthTestService bandwidthTestService) {
        this.latencyTestService = latencyTestService;
        this.bandwidthTestService = bandwidthTestService;
    }

    public record LatencyRequest(String protocol, String host, Integer port, Integer samples,
            Integer intervalMs, Integer timeoutMs, String scenario) {
    }

    public record BandwidthRequest(String protocol, String host, Integer port,
            Integer durationSeconds, Integer blockSize) {
    }

    @PostMapping("/latency")
    public ApiResponse<?> latency(@RequestBody LatencyRequest request) {
        if (request.host() == null || request.port() == null) {
            throw BusinessException.badRequest("host 与 port 必填");
        }
        int samples = request.samples() == null ? 30 : Math.min(Math.max(request.samples(), 1), 1000);
        return ApiResponse.ok(latencyTestService.run(
                "UDP".equals(request.protocol()) ? "UDP" : "TCP",
                request.host(),
                request.port(),
                samples,
                request.intervalMs() == null ? 100 : request.intervalMs(),
                request.timeoutMs() == null ? 1000 : request.timeoutMs(),
                request.scenario() == null ? "DEFAULT" : request.scenario()));
    }

    @PostMapping("/bandwidth")
    public ApiResponse<?> bandwidth(@RequestBody BandwidthRequest request) {
        if (request.host() == null || request.port() == null) {
            throw BusinessException.badRequest("host 与 port 必填");
        }
        return ApiResponse.ok(bandwidthTestService.run(
                "UDP".equals(request.protocol()) ? "UDP" : "TCP",
                request.host(),
                request.port(),
                request.durationSeconds() == null ? 6 : request.durationSeconds(),
                request.blockSize() == null ? 64 * 1024 : request.blockSize()));
    }
}
