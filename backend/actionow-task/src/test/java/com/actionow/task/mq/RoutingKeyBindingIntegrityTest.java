package com.actionow.task.mq;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing key 与 binding 一致性回归测试。
 *
 * <p>历史 trigger：{@code BatchJobMissionNotifier} 曾把 BatchJob 终态消息发到
 * {@code MqConstants.BatchJob.ROUTING_COMPLETED} ({@code "batch.job.completed"})，
 * 但代码库中没有任何 {@code @Bean Binding} 绑定该 routing key。direct exchange 静默丢弃，
 * Mission 永久卡在 WAITING（事故 mission {@code 019dcfa8-…71a}）。
 *
 * <p>本测试静态扫描整个 backend 源码：
 * <ul>
 *   <li>收集所有 {@code messageProducer.sendDirect(MqConstants.X.ROUTING_*, ...)} 与
 *       {@code messageProducer.send(EXCHANGE_DIRECT, MqConstants.X.ROUTING_*, ...)} 调用使用的常量；</li>
 *   <li>收集所有 {@code BindingBuilder.bind(...).to(directExchange).with(MqConstants.X.ROUTING_*)} 中绑定的常量；</li>
 *   <li>断言「发布集合 ⊆ 绑定集合」——任何未绑定的 routing key 都会被 direct exchange 丢弃。</li>
 * </ul>
 *
 * <p>仅针对 direct exchange 执行严格校验；topic exchange 因支持通配符 binding，
 * 其完备性在此不做静态保证。
 *
 * <p>测试不依赖 Spring / RabbitMQ 运行时，直接对源代码做正则扫描，避免引入慢启动成本，
 * 同时确保 typo 在编译期之前就被 CI 拦下。
 */
class RoutingKeyBindingIntegrityTest {

    /** 项目根目录 = actionow-task/.. = backend/。 */
    private static final Path BACKEND_ROOT = Paths.get("..").toAbsolutePath().normalize();

    /**
     * 匹配 sendDirect 与 send(EXCHANGE_DIRECT, ...) 中的 routing key 常量引用。
     * 同时覆盖 messageProducer / outboxMessageProducer 等命名变体（任何方法名以 send 结尾的）。
     */
    private static final Pattern PUBLISH_DIRECT = Pattern.compile(
            "(?:sendDirect\\s*\\(|\\bsend\\s*\\(\\s*(?:MqConstants\\.)?EXCHANGE_DIRECT\\s*,\\s*)" +
                    "\\s*(MqConstants\\.\\w+\\.ROUTING_\\w+)"
    );

    /**
     * 匹配 BindingBuilder 链：bind(...).to(directExchange).with(MqConstants.X.ROUTING_*)。
     * 容许换行和任意空白。
     */
    private static final Pattern BIND_DIRECT = Pattern.compile(
            "BindingBuilder\\s*\\.\\s*bind\\s*\\([^)]*\\)\\s*" +
                    "\\.\\s*to\\s*\\(\\s*directExchange\\s*\\)\\s*" +
                    "\\.\\s*with\\s*\\(\\s*(MqConstants\\.\\w+\\.ROUTING_\\w+)"
    );

    @Test
    @DisplayName("每个发布到 direct exchange 的 routing key 都必须有至少一个 @Bean Binding")
    void everyPublishedRoutingKeyHasBinding() throws IOException {
        Set<String> published = scan(PUBLISH_DIRECT);
        Set<String> bound = scan(BIND_DIRECT);

        Set<String> orphans = new TreeSet<>(published);
        orphans.removeAll(bound);

        assertTrue(orphans.isEmpty(),
                "以下 routing key 在 sendDirect/send 中被发布，但代码库中没有任何 @Bean Binding；" +
                        "direct exchange 会静默丢弃这些消息（事故 mission 019dcfa8-…71a 的根因）：\n" +
                        String.join("\n", orphans) +
                        "\n\n已发现的发布点（共 " + published.size() + "）：\n" + published +
                        "\n已发现的绑定（共 " + bound.size() + "）：\n" + bound);
    }

    private Set<String> scan(Pattern pattern) throws IOException {
        Set<String> result = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(BACKEND_ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/"))
                    .forEach(p -> matchInto(p, pattern, result));
        }
        return result;
    }

    private void matchInto(Path file, Pattern pattern, Set<String> sink) {
        try {
            String content = Files.readString(file);
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                sink.add(matcher.group(1));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取源文件失败: " + file, e);
        }
    }
}
