package com.actionow.task.service;

import com.actionow.common.core.result.Result;
import com.actionow.task.dto.AvailableProviderResponse;
import com.actionow.task.feign.AiFeignClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Provider 路由器：在排除已失败 provider 后挑选下一优先 provider。
 *
 * <p>仅做选择，不做副作用；与 Resilience4j 同 provider 内重试是正交的两层重试：
 * <ul>
 *   <li>第 1 层（Resilience4j）：单 provider 内的网络抖动重试</li>
 *   <li>第 2 层（本组件）：跨 provider 的故障转移，由 BatchJobItem.failed_provider_ids 排除已坏点</li>
 * </ul>
 *
 * @author Actionow
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderRouter {

    private final AiFeignClient aiFeignClient;

    /**
     * 选择下一个可用 provider（排除已失败列表，按 priority DESC 取首位）。
     *
     * @param generationType 生成类型，如 IMAGE / VIDEO
     * @param excludedProviderIds 已失败的 provider ID 集合（含当前刚失败的那个）
     * @return 下一个候选 provider，若无可用则 empty
     */
    public Optional<AvailableProviderResponse> selectFallback(String generationType,
                                                              Collection<String> excludedProviderIds) {
        if (generationType == null || generationType.isBlank()) {
            return Optional.empty();
        }
        Set<String> excluded = excludedProviderIds == null ? Set.of() : Set.copyOf(excludedProviderIds);

        Result<List<AvailableProviderResponse>> resp = aiFeignClient.getAvailableProviders(generationType);
        if (!resp.isSuccess() || CollectionUtils.isEmpty(resp.getData())) {
            log.debug("[ProviderRouter] no available providers for type={}", generationType);
            return Optional.empty();
        }

        return resp.getData().stream()
                .filter(p -> p.getId() != null && !excluded.contains(p.getId()))
                .max(Comparator.comparing(p -> p.getPriority() == null ? 0 : p.getPriority()));
    }
}
