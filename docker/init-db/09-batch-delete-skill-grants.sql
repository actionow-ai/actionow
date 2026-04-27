-- =====================================================
-- 增量 seed: 把 batch_delete_* 工具 ID 追加到对应技能的 grouped_tool_ids
--
-- 背景：03-agent-seed.sql 的 t_agent_skill.grouped_tool_ids 漏挂 batch_delete_*。
-- 技能的 prompt body 提到了工具名 → LLM 会调用，但 SaaAgentFactory 在
-- buildGroupedToolsFromSkills() 里只按 grouped_tool_ids 解析 ToolCallback，
-- 缺挂会导致：LLM 调用时抛 IllegalStateException: No ToolCallback found。
--
-- 用 jsonb 数组追加去重；幂等可重复执行。
-- 配套：08-batch-delete-tool-access.sql（解锁 t_agent_tool_access 行）。
-- =====================================================

-- 工具函数：把 toolId 追加到指定 skill.grouped_tool_ids（去重）
DO $$
DECLARE
    pairs CONSTANT JSONB := '[
      ["skill-episode-expert",    "episode_batchDeleteEpisodes"],
      ["skill-storyboard-expert", "storyboard_batchDeleteStoryboards"],
      ["skill-character-expert",  "character_batchDeleteCharacters"],
      ["skill-scene-expert",      "scene_batchDeleteScenes"],
      ["skill-prop-expert",       "prop_batchDeleteProps"],
      ["skill-style-expert",      "style_batchDeleteStyles"],
      ["skill-multimodal-expert", "multimodal_batchDeleteAssets"],
      ["skill-mission-expert",    "episode_batchDeleteEpisodes"],
      ["skill-mission-expert",    "storyboard_batchDeleteStoryboards"],
      ["skill-mission-expert",    "character_batchDeleteCharacters"],
      ["skill-mission-expert",    "scene_batchDeleteScenes"],
      ["skill-mission-expert",    "prop_batchDeleteProps"],
      ["skill-mission-expert",    "style_batchDeleteStyles"],
      ["skill-mission-expert",    "multimodal_batchDeleteAssets"]
    ]'::jsonb;
    pair JSONB;
    sid TEXT;
    tid TEXT;
BEGIN
    FOR pair IN SELECT * FROM jsonb_array_elements(pairs) LOOP
        sid := pair->>0;
        tid := pair->>1;
        UPDATE t_agent_skill
           SET grouped_tool_ids = (
                   SELECT jsonb_agg(DISTINCT v)
                     FROM jsonb_array_elements_text(
                              COALESCE(grouped_tool_ids, '[]'::jsonb) || to_jsonb(tid)
                          ) AS v
               ),
               updated_at = CURRENT_TIMESTAMP
         WHERE id = sid
           AND NOT (grouped_tool_ids @> to_jsonb(tid));
    END LOOP;
END$$;

-- 验证（执行后应全部为 t）：
-- SELECT id, grouped_tool_ids @> '["character_batchDeleteCharacters"]'::jsonb AS ok
--   FROM t_agent_skill
--  WHERE id IN ('skill-character-expert','skill-mission-expert');
