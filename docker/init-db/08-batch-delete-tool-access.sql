-- =====================================================
-- 增量 seed: 14 行 t_agent_tool_access 解锁 batch_delete_* 工具
--
-- 背景：03-agent-seed.sql 已新增 batch_delete_episodes / storyboards /
-- characters / scenes / props / styles / assets 七个工具，但 init-db 仅在
-- 空库初始化时运行。生产/开发库已存在 → 必须用本文件以 UPSERT 方式补行，
-- 否则 SaaAgentFactory 不会把这些 ToolCallback 加入 staticTools，
-- LLM 调用时会抛 IllegalStateException: No ToolCallback found for tool name。
--
-- 反向应用：DELETE FROM t_agent_tool_access WHERE id IN (...) — 见末尾。
-- =====================================================

INSERT INTO t_agent_tool_access (
    id, agent_type, tool_category, tool_id, tool_name, access_mode, daily_quota, enabled
) VALUES
    ('ua-ep-delete',    'UNIVERSAL',   'PROJECT', 'episode_batchDeleteEpisodes',       'batchDeleteEpisodes',     'FULL', -1, true),
    ('co-ep-delete',    'COORDINATOR', 'PROJECT', 'episode_batchDeleteEpisodes',       'batchDeleteEpisodes',     'FULL', -1, true),
    ('ua-sb-delete',    'UNIVERSAL',   'PROJECT', 'storyboard_batchDeleteStoryboards', 'batchDeleteStoryboards',  'FULL', -1, true),
    ('co-sb-delete',    'COORDINATOR', 'PROJECT', 'storyboard_batchDeleteStoryboards', 'batchDeleteStoryboards',  'FULL', -1, true),
    ('ua-ch-delete',    'UNIVERSAL',   'PROJECT', 'character_batchDeleteCharacters',   'batchDeleteCharacters',   'FULL', -1, true),
    ('co-ch-delete',    'COORDINATOR', 'PROJECT', 'character_batchDeleteCharacters',   'batchDeleteCharacters',   'FULL', -1, true),
    ('ua-sc-delete',    'UNIVERSAL',   'PROJECT', 'scene_batchDeleteScenes',           'batchDeleteScenes',       'FULL', -1, true),
    ('co-sc-delete',    'COORDINATOR', 'PROJECT', 'scene_batchDeleteScenes',           'batchDeleteScenes',       'FULL', -1, true),
    ('ua-pr-delete',    'UNIVERSAL',   'PROJECT', 'prop_batchDeleteProps',             'batchDeleteProps',        'FULL', -1, true),
    ('co-pr-delete',    'COORDINATOR', 'PROJECT', 'prop_batchDeleteProps',             'batchDeleteProps',        'FULL', -1, true),
    ('ua-st-delete',    'UNIVERSAL',   'PROJECT', 'style_batchDeleteStyles',           'batchDeleteStyles',       'FULL', -1, true),
    ('co-st-delete',    'COORDINATOR', 'PROJECT', 'style_batchDeleteStyles',           'batchDeleteStyles',       'FULL', -1, true),
    ('ua-mm-delassets', 'UNIVERSAL',   'PROJECT', 'multimodal_batchDeleteAssets',      'batchDeleteAssets',       'FULL', -1, true),
    ('co-mm-delassets', 'COORDINATOR', 'PROJECT', 'multimodal_batchDeleteAssets',      'batchDeleteAssets',       'FULL', -1, true)
ON CONFLICT (id) DO UPDATE SET
    agent_type    = EXCLUDED.agent_type,
    tool_category = EXCLUDED.tool_category,
    tool_id       = EXCLUDED.tool_id,
    tool_name     = EXCLUDED.tool_name,
    access_mode   = EXCLUDED.access_mode,
    daily_quota   = EXCLUDED.daily_quota,
    enabled       = EXCLUDED.enabled;

-- 回滚（如需）：
-- DELETE FROM t_agent_tool_access WHERE id IN (
--   'ua-ep-delete','co-ep-delete','ua-sb-delete','co-sb-delete',
--   'ua-ch-delete','co-ch-delete','ua-sc-delete','co-sc-delete',
--   'ua-pr-delete','co-pr-delete','ua-st-delete','co-st-delete',
--   'ua-mm-delassets','co-mm-delassets'
-- );
