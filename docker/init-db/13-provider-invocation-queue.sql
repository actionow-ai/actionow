-- =====================================================
-- 13. Provider 调用队列化（per-provider RabbitMQ queue）
-- =====================================================
-- 每个 provider 可独立配置 RabbitMQ 队列；未配置则走全局默认队列。
-- worker 数量、prefetch、积压上限均可单独覆盖，避免长耗时 provider
-- 拖慢其它 provider；积压超限时新请求会被立刻拒绝（背压）。
-- =====================================================

ALTER TABLE t_model_provider
    ADD COLUMN IF NOT EXISTS queue_name           VARCHAR(128),
    ADD COLUMN IF NOT EXISTS queue_concurrency    INTEGER,
    ADD COLUMN IF NOT EXISTS queue_prefetch       INTEGER,
    ADD COLUMN IF NOT EXISTS queue_max_length     INTEGER;

COMMENT ON COLUMN t_model_provider.queue_name IS 'RabbitMQ 队列名；NULL 走 runtime.ai.queue_default_name';
COMMENT ON COLUMN t_model_provider.queue_concurrency IS '消费者并发数；NULL 取 runtime.ai.queue_default_concurrency';
COMMENT ON COLUMN t_model_provider.queue_prefetch IS 'consumer prefetch；NULL 取 runtime.ai.queue_default_prefetch';
COMMENT ON COLUMN t_model_provider.queue_max_length IS '队列最大积压消息数；满后新消息被 broker reject (x-max-length + reject-publish)';

-- =====================================================
-- runtime config 默认值
-- =====================================================
INSERT INTO t_system_config
    (id, config_key, config_value, config_type, scope, description, value_type, enabled, module, group_name, display_name, sort_order, created_by)
VALUES
    ('00000000-0000-0000-0003-000000000040', 'runtime.ai.queue_default_name',         'ai.provider.default', 'LIMIT', 'GLOBAL', '默认 provider 调用队列名',                     'STRING',  TRUE, 'ai', 'queue', '默认队列名',           340, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000041', 'runtime.ai.queue_default_concurrency',  '20',                  'LIMIT', 'GLOBAL', '默认队列消费者并发数',                          'INTEGER', TRUE, 'ai', 'queue', '默认 worker 并发',     341, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000042', 'runtime.ai.queue_default_prefetch',     '20',                  'LIMIT', 'GLOBAL', '默认队列 consumer prefetch',                    'INTEGER', TRUE, 'ai', 'queue', '默认 prefetch',         342, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000043', 'runtime.ai.queue_default_max_length',   '5000',                'LIMIT', 'GLOBAL', '默认队列最大积压消息数',                        'INTEGER', TRUE, 'ai', 'queue', '默认 max length',       343, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000044', 'runtime.ai.queue_result_ttl_seconds',   '3600',                'LIMIT', 'GLOBAL', 'Redis 调用结果保留秒数',                        'INTEGER', TRUE, 'ai', 'queue', '结果 TTL',              344, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000045', 'runtime.ai.queue_submit_timeout_ms',    '120000',              'LIMIT', 'GLOBAL', 'facade.awaitBlocking 默认超时（毫秒）',         'INTEGER', TRUE, 'ai', 'queue', '同步等待超时',         345, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000046', 'runtime.ai.queue_message_ttl_seconds',  '600',                 'LIMIT', 'GLOBAL', '消息在队列里的最大寿命（秒）',                   'INTEGER', TRUE, 'ai', 'queue', '消息 TTL',              346, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000047', 'runtime.ai.bulkhead_max_concurrent',    '40',                  'LIMIT', 'GLOBAL', 'Provider 单实例 bulkhead 并发上限',              'INTEGER', TRUE, 'ai', 'queue', 'Bulkhead 并发',        347, '00000000-0000-0000-0000-000000000000')
ON CONFLICT DO NOTHING;
