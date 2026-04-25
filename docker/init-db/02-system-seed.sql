-- =====================================================
-- Actionow 平台数据库初始化脚本
-- 02-system-seed.sql - 系统初始化数据（系统租户 / 预置数据 / 模板 / 模型提供商）
-- PostgreSQL 16+
-- =====================================================

-- =====================================================
-- 1. 系统租户与管理员初始化
-- =====================================================


-- =====================================================
-- 1. 初始化 tenant_system Schema
-- 这是系统级别的租户，用于存放所有工作空间共享的数据
-- =====================================================

-- 调用 tenant schema 创建函数
SELECT create_tenant_schema('tenant_system');
SELECT create_tenant_indexes('tenant_system');

-- =====================================================
-- 公共资源库：发布相关索引（仅 tenant_system schema 需要）
-- 支持按 scope + published_at 快速浏览已发布资源
-- =====================================================
CREATE INDEX IF NOT EXISTS idx_character_system_pub
    ON tenant_system.t_character(scope, deleted, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_scene_system_pub
    ON tenant_system.t_scene(scope, deleted, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_prop_system_pub
    ON tenant_system.t_prop(scope, deleted, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_style_system_pub
    ON tenant_system.t_style(scope, deleted, published_at DESC);

CREATE INDEX IF NOT EXISTS idx_asset_system_pub
    ON tenant_system.t_asset(scope, deleted, published_at DESC);

-- =====================================================
-- 2. 创建系统用户（用于系统预设数据的 created_by）
-- =====================================================

INSERT INTO t_user (id, username, nickname, email, status, email_verified, created_by, updated_by) VALUES
    ('00000000-0000-0000-0000-000000000000', 'system', '系统', 'system@actionow.io', 'ACTIVE', TRUE, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 3. 创建可登录用户
-- 默认密码: 请首次登录后立即修改 (BCrypt 加密，明文见部署文档)
-- 生产环境请立即修改密码！
-- =====================================================

INSERT INTO t_user (id, username, nickname, email, password, status, email_verified, created_by, updated_by) VALUES
    -- 租户创建者
    ('00000000-0000-0000-0000-000000000001', 'actionow', '管理员', 'admin@actionow.ai',
     '$2b$10$X.S9VqKIkvKoTi7S2KIRGOQ3Jy9I5rfzlB9Xommsedav9kH0JVVy6',
     'ACTIVE', TRUE, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 租户管理员
    ('00000000-0000-0000-0000-000000000002', 'admin', '租户管理员', 'manager@actionow.ai',
     '$2b$10$X.S9VqKIkvKoTi7S2KIRGOQ3Jy9I5rfzlB9Xommsedav9kH0JVVy6',
     'ACTIVE', TRUE, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 普通用户 1
    ('00000000-0000-0000-0000-000000000003', 'user1', '用户一', 'user1@actionow.ai',
     '$2b$10$X.S9VqKIkvKoTi7S2KIRGOQ3Jy9I5rfzlB9Xommsedav9kH0JVVy6',
     'ACTIVE', TRUE, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 普通用户 2
    ('00000000-0000-0000-0000-000000000004', 'user2', '用户二', 'user2@actionow.ai',
     '$2b$10$X.S9VqKIkvKoTi7S2KIRGOQ3Jy9I5rfzlB9Xommsedav9kH0JVVy6',
     'ACTIVE', TRUE, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 4. 创建系统工作空间（用于系统预设数据的 workspace_id）
-- =====================================================

INSERT INTO t_workspace (id, name, slug, description, owner_id, schema_name, status, plan_type, member_count) VALUES
    ('00000000-0000-0000-0000-000000000001', 'Actionow System', 'system', '系统工作空间，存放所有用户共享的系统级数据', '00000000-0000-0000-0000-000000000001', 'tenant_system', 'ACTIVE', 'Enterprise', 4)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 5. 将用户添加为系统工作空间成员
-- =====================================================

INSERT INTO t_workspace_member (id, workspace_id, user_id, role, status, joined_at, created_by, updated_by) VALUES
    -- 租户创建者 (CREATOR)
    ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 'CREATOR', 'ACTIVE', CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 租户管理员 (ADMIN)
    ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002', 'ADMIN', 'ACTIVE', CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 普通用户 1 (MEMBER)
    ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000003', 'MEMBER', 'ACTIVE', CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 普通用户 2 (MEMBER)
    ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000004', 'MEMBER', 'ACTIVE', CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (workspace_id, user_id) DO NOTHING;

-- =====================================================
-- 5.5 系统工作空间钱包（开发环境赠送积分）
-- =====================================================

INSERT INTO t_workspace_wallet (id, workspace_id, balance, total_recharged, status, created_by, updated_by) VALUES
    ('00000000-0000-0000-0000-000000000006', '00000000-0000-0000-0000-000000000001', 10000, 10000, 'ACTIVE', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (workspace_id) DO NOTHING;

-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'Tenant system schema initialized successfully!';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  All accounts password: (default; change immediately after first login)';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '  [CREATOR] actionow  / admin@actionow.ai';
    RAISE NOTICE '  [ADMIN]   admin     / manager@actionow.ai';
    RAISE NOTICE '  [MEMBER]  user1     / user1@actionow.ai';
    RAISE NOTICE '  [MEMBER]  user2     / user2@actionow.ai';
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'WARNING: Change passwords in production!';
END $$;

-- =====================================================
-- 2. 系统预置业务数据
-- =====================================================


-- =====================================================
-- 1. 系统级风格预设（所有租户可使用）
-- =====================================================

INSERT INTO tenant_system.t_style (id, workspace_id, scope, name, description, fixed_desc, style_params, created_by, updated_by) VALUES
    ('00000000-0000-7000-8000-000000000001', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '动漫风格', '日式动漫画风，色彩明亮，线条清晰', 'anime style, vibrant colors, clear lines', '{"base_model": "anime", "cfg_scale": 7}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000002', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '写实风格', '高度写实的照片级画风', 'photorealistic, highly detailed, 8k', '{"base_model": "realistic", "cfg_scale": 5}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000003', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '水彩风格', '水彩画艺术风格，柔和色调', 'watercolor painting, soft tones, artistic', '{"base_model": "artistic", "cfg_scale": 6}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000004', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '油画风格', '古典油画艺术风格，厚重质感', 'oil painting, classical art, rich textures', '{"base_model": "artistic", "cfg_scale": 6}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000005', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '赛博朋克', '未来科幻赛博朋克风格，霓虹灯光', 'cyberpunk, neon lights, futuristic, sci-fi', '{"base_model": "sci-fi", "cfg_scale": 7}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000006', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '像素风格', '复古像素艺术风格', 'pixel art, retro game style, 8-bit', '{"base_model": "pixel", "cfg_scale": 7}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000007', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '3D渲染', '3D建模渲染风格', '3D render, CGI, octane render, realistic lighting', '{"base_model": "3d", "cfg_scale": 6}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000008', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '素描风格', '铅笔素描手绘风格', 'pencil sketch, hand drawn, monochrome', '{"base_model": "sketch", "cfg_scale": 6}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 2. 系统级场景模板（所有租户可使用）
-- =====================================================

INSERT INTO tenant_system.t_scene (id, workspace_id, scope, scene_type, name, description, fixed_desc, appearance_data, created_by, updated_by) VALUES
    ('00000000-0000-7000-8000-000000000101', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'EXTERIOR', '现代都市-白天', '现代城市街道场景，白天', 'modern city street, daytime, urban environment', '{"location": "city", "timeOfDay": "DAY", "mood": "busy", "weather": "sunny"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000102', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'EXTERIOR', '现代都市-夜晚', '现代城市街道场景，夜晚灯光', 'modern city street, nighttime, neon lights, urban night', '{"location": "city", "timeOfDay": "NIGHT", "mood": "vibrant", "weather": "clear"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000103', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'EXTERIOR', '森林', '茂密的森林场景，阳光透过树叶', 'dense forest, sunlight through leaves, nature', '{"location": "forest", "timeOfDay": "DAY", "mood": "peaceful", "weather": "sunny"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000104', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'EXTERIOR', '海滩', '海边沙滩场景，海浪拍岸', 'beach, ocean waves, sandy shore, coastal', '{"location": "beach", "timeOfDay": "DAY", "mood": "relaxing", "weather": "sunny"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000105', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'INTERIOR', '教室', '学校教室内部场景', 'classroom interior, school, desks and chairs', '{"location": "indoor", "timeOfDay": "DAY", "mood": "academic"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000106', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'INTERIOR', '咖啡厅', '温馨的咖啡厅内部场景', 'cozy cafe interior, warm lighting, coffee shop', '{"location": "indoor", "timeOfDay": "DAY", "mood": "cozy"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000107', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'INTERIOR', '办公室', '现代办公室场景', 'modern office interior, workspace, corporate', '{"location": "indoor", "timeOfDay": "DAY", "mood": "professional"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000108', '00000000-0000-0000-0000-000000000001', 'SYSTEM', 'EXTERIOR', '雪山', '白雪覆盖的山脉场景', 'snow covered mountains, winter landscape, alpine', '{"location": "mountain", "timeOfDay": "DAY", "mood": "majestic", "weather": "snow"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 3. 系统级提示词模板（所有租户可使用）
-- =====================================================

INSERT INTO tenant_system.t_prompt_template (id, workspace_id, scope, name, description, category, template_content, parameters, created_by, updated_by) VALUES
    ('00000000-0000-7000-8000-000000000201', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '角色立绘生成', '生成角色全身立绘', 'character', '{{character_description}}, full body, standing pose, {{style}}, high quality, detailed', '{"character_description": "角色描述", "style": "画风"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000202', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '场景背景生成', '生成场景背景图', 'scene', '{{scene_description}}, background, wide shot, {{atmosphere}}, {{style}}, no characters', '{"scene_description": "场景描述", "atmosphere": "氛围", "style": "画风"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000203', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '分镜画面生成', '生成分镜画面', 'storyboard', '{{scene}}, {{characters}}, {{action}}, {{camera_angle}}, {{style}}, cinematic', '{"scene": "场景", "characters": "角色", "action": "动作", "camera_angle": "镜头角度", "style": "画风"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000204', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '角色头像生成', '生成角色头像/胸像', 'character', '{{character_description}}, portrait, headshot, {{expression}}, {{style}}, high quality', '{"character_description": "角色描述", "expression": "表情", "style": "画风"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000205', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '道具设计生成', '生成道具设计图', 'prop', '{{prop_description}}, item design, {{material}}, {{style}}, detailed, isolated on white background', '{"prop_description": "道具描述", "material": "材质", "style": "画风"}'::jsonb, '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 4. 系统级标签（所有租户可使用）
-- =====================================================

INSERT INTO tenant_system.t_tag (id, workspace_id, scope, name, color, description, created_by, updated_by) VALUES
    -- 题材标签
    ('00000000-0000-7000-8000-000000000301', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '爱情', '#FF69B4', '爱情题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000302', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '动作', '#FF4500', '动作题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000303', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '科幻', '#4169E1', '科幻题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000304', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '悬疑', '#2F4F4F', '悬疑题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000305', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '喜剧', '#FFD700', '喜剧题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000306', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '奇幻', '#9932CC', '奇幻题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000307', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '恐怖', '#000000', '恐怖题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000308', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '战争', '#8B4513', '战争题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000309', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '历史', '#DAA520', '历史题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000310', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '校园', '#32CD32', '校园题材', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    -- 状态标签
    ('00000000-0000-7000-8000-000000000311', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '待审核', '#FFA500', '等待审核', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000312', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '精选', '#FF1493', '精选推荐', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-7000-8000-000000000313', '00000000-0000-0000-0000-000000000001', 'SYSTEM', '热门', '#FF0000', '热门内容', '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 5. 数据字典类型
-- =====================================================

INSERT INTO t_dict_type (id, type_code, type_name, description, is_system, enabled) VALUES
    ('00000000-0000-0000-0002-000000000001', 'SCRIPT_STATUS', '剧本状态', '剧本的状态枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000002', 'EPISODE_STATUS', '剧集状态', '剧集的状态枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000003', 'ASSET_TYPE', '素材类型', '素材的类型枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000004', 'ASSET_SOURCE', '素材来源', '素材的来源枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000005', 'CHARACTER_TYPE', '角色类型', '角色的类型枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000006', 'GENDER', '性别', '性别枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000007', 'WORKSPACE_ROLE', '工作空间角色', '工作空间成员角色枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000008', 'TASK_STATUS', '任务状态', '异步任务状态枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000009', 'PROVIDER_TYPE', '模型提供商类型', '模型提供商类型枚举', TRUE, TRUE),
    ('00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', '认证类型', '模型提供商认证类型枚举', TRUE, TRUE)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 6. 数据字典项
-- =====================================================

-- 剧本状态
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000001', '00000000-0000-0000-0002-000000000001', 'SCRIPT_STATUS', 'DRAFT', '草稿', 'DRAFT', 1),
    ('00000000-0000-0000-0003-000000000002', '00000000-0000-0000-0002-000000000001', 'SCRIPT_STATUS', 'IN_PROGRESS', '制作中', 'IN_PROGRESS', 2),
    ('00000000-0000-0000-0003-000000000003', '00000000-0000-0000-0002-000000000001', 'SCRIPT_STATUS', 'COMPLETED', '已完成', 'COMPLETED', 3),
    ('00000000-0000-0000-0003-000000000004', '00000000-0000-0000-0002-000000000001', 'SCRIPT_STATUS', 'ARCHIVED', '已归档', 'ARCHIVED', 4)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 素材类型
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000011', '00000000-0000-0000-0002-000000000003', 'ASSET_TYPE', 'IMAGE', '图片', 'IMAGE', 1),
    ('00000000-0000-0000-0003-000000000012', '00000000-0000-0000-0002-000000000003', 'ASSET_TYPE', 'VIDEO', '视频', 'VIDEO', 2),
    ('00000000-0000-0000-0003-000000000013', '00000000-0000-0000-0002-000000000003', 'ASSET_TYPE', 'AUDIO', '音频', 'AUDIO', 3),
    ('00000000-0000-0000-0003-000000000014', '00000000-0000-0000-0002-000000000003', 'ASSET_TYPE', 'DOCUMENT', '文档', 'DOCUMENT', 4)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 素材来源
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000021', '00000000-0000-0000-0002-000000000004', 'ASSET_SOURCE', 'UPLOAD', '用户上传', 'UPLOAD', 1),
    ('00000000-0000-0000-0003-000000000022', '00000000-0000-0000-0002-000000000004', 'ASSET_SOURCE', 'AI_GENERATED', 'AI生成', 'AI_GENERATED', 2),
    ('00000000-0000-0000-0003-000000000023', '00000000-0000-0000-0002-000000000004', 'ASSET_SOURCE', 'EXTERNAL', '外部引用', 'EXTERNAL', 3)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 角色类型（与 Agent Skills 中使用的枚举一致）
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000031', '00000000-0000-0000-0002-000000000005', 'CHARACTER_TYPE', 'PROTAGONIST', '主角', 'PROTAGONIST', 1),
    ('00000000-0000-0000-0003-000000000032', '00000000-0000-0000-0002-000000000005', 'CHARACTER_TYPE', 'SUPPORTING', '配角', 'SUPPORTING', 2),
    ('00000000-0000-0000-0003-000000000033', '00000000-0000-0000-0002-000000000005', 'CHARACTER_TYPE', 'BACKGROUND', '群演', 'BACKGROUND', 3),
    ('00000000-0000-0000-0003-000000000034', '00000000-0000-0000-0002-000000000005', 'CHARACTER_TYPE', 'ANTAGONIST', '对立角色', 'ANTAGONIST', 4)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 性别
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000041', '00000000-0000-0000-0002-000000000006', 'GENDER', 'MALE', '男', 'MALE', 1),
    ('00000000-0000-0000-0003-000000000042', '00000000-0000-0000-0002-000000000006', 'GENDER', 'FEMALE', '女', 'FEMALE', 2),
    ('00000000-0000-0000-0003-000000000043', '00000000-0000-0000-0002-000000000006', 'GENDER', 'OTHER', '其他', 'OTHER', 3)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 工作空间角色
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000051', '00000000-0000-0000-0002-000000000007', 'WORKSPACE_ROLE', 'CREATOR', '创建者', 'CREATOR', 1),
    ('00000000-0000-0000-0003-000000000052', '00000000-0000-0000-0002-000000000007', 'WORKSPACE_ROLE', 'ADMIN', '管理员', 'ADMIN', 2),
    ('00000000-0000-0000-0003-000000000053', '00000000-0000-0000-0002-000000000007', 'WORKSPACE_ROLE', 'MEMBER', '成员', 'MEMBER', 3),
    ('00000000-0000-0000-0003-000000000054', '00000000-0000-0000-0002-000000000007', 'WORKSPACE_ROLE', 'GUEST', '访客', 'GUEST', 4)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 任务状态
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000061', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'PENDING', '待处理', 'PENDING', 1),
    ('00000000-0000-0000-0003-000000000062', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'QUEUED', '已入队', 'QUEUED', 2),
    ('00000000-0000-0000-0003-000000000063', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'RUNNING', '执行中', 'RUNNING', 3),
    ('00000000-0000-0000-0003-000000000064', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'COMPLETED', '已完成', 'COMPLETED', 4),
    ('00000000-0000-0000-0003-000000000065', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'FAILED', '已失败', 'FAILED', 5),
    ('00000000-0000-0000-0003-000000000066', '00000000-0000-0000-0002-000000000008', 'TASK_STATUS', 'CANCELLED', '已取消', 'CANCELLED', 6)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 模型提供商类型
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000071', '00000000-0000-0000-0002-000000000009', 'PROVIDER_TYPE', 'IMAGE', '图像生成', 'IMAGE', 1),
    ('00000000-0000-0000-0003-000000000072', '00000000-0000-0000-0002-000000000009', 'PROVIDER_TYPE', 'VIDEO', '视频生成', 'VIDEO', 2),
    ('00000000-0000-0000-0003-000000000073', '00000000-0000-0000-0002-000000000009', 'PROVIDER_TYPE', 'AUDIO', '音频生成', 'AUDIO', 3),
    ('00000000-0000-0000-0003-000000000074', '00000000-0000-0000-0002-000000000009', 'PROVIDER_TYPE', 'TEXT', '文本生成', 'TEXT', 4)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- 认证类型
INSERT INTO t_dict_item (id, type_id, type_code, item_code, item_name, item_value, sort_order) VALUES
    ('00000000-0000-0000-0003-000000000081', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'API_KEY', 'API Key', 'API_KEY', 1),
    ('00000000-0000-0000-0003-000000000082', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'BEARER', 'Bearer Token', 'BEARER', 2),
    ('00000000-0000-0000-0003-000000000083', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'AK_SK', 'AK/SK签名', 'AK_SK', 3),
    ('00000000-0000-0000-0003-000000000084', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'OAUTH2', 'OAuth 2.0', 'OAUTH2', 4),
    ('00000000-0000-0000-0003-000000000085', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'CUSTOM', '自定义', 'CUSTOM', 5),
    ('00000000-0000-0000-0003-000000000086', '00000000-0000-0000-0002-000000000010', 'AUTH_TYPE', 'NONE', '无认证', 'NONE', 6)
ON CONFLICT (type_code, item_code) DO NOTHING;

-- =====================================================
-- 7. 邀请码相关系统配置
-- =====================================================

INSERT INTO t_system_config (id, config_key, config_value, config_type, scope, description, value_type, enabled, module, group_name, display_name, sort_order, created_by) VALUES
    ('00000000-0000-0000-0001-000000000001', 'registration.invitation_code.required',        'false', 'FEATURE', 'GLOBAL', '注册是否需要邀请码',            'BOOLEAN', TRUE, 'user', 'registration', '邀请码注册开关',       1, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000002', 'registration.invitation_code.allow_user_code',  'true',  'FEATURE', 'GLOBAL', '是否允许使用用户邀请码注册',     'BOOLEAN', TRUE, 'user', 'registration', '用户邀请码注册',       2, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000003', 'registration.invitation_code.default_max_uses', '1',     'LIMIT',   'GLOBAL', '管理员邀请码默认最大使用次数',   'INTEGER', TRUE, 'user', 'registration', '管理员邀请码使用次数', 3, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000004', 'registration.invitation_code.default_valid_days', '30',  'LIMIT',   'GLOBAL', '管理员邀请码默认有效天数',       'INTEGER', TRUE, 'user', 'registration', '管理员邀请码有效期',   4, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000005', 'user.invitation_code.enabled',                 'true',  'FEATURE', 'GLOBAL', '是否为用户生成专属邀请码',       'BOOLEAN', TRUE, 'user', 'invitation',   '用户邀请码开关',       5, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000006', 'user.invitation_code.max_uses',                '5',     'LIMIT',   'GLOBAL', '用户邀请码最大使用次数',         'INTEGER', TRUE, 'user', 'invitation',   '用户邀请码使用次数',   6, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0001-000000000007', 'user.invitation_code.valid_days',              '90',    'LIMIT',   'GLOBAL', '用户邀请码有效天数',             'INTEGER', TRUE, 'user', 'invitation',   '用户邀请码有效期',     7, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 7.5 OAuth 提供商 & 登录方式动态配置
-- 所有 OAuth 配置均通过 t_system_config 管理，支持热更新
-- =====================================================

INSERT INTO t_system_config (id, config_key, config_value, config_type, scope, description, value_type, sensitive, enabled, module, group_name, display_name, sort_order, created_by) VALUES
    -- 全局登录方式开关
    ('00000000-0000-0000-0005-000000000001', 'auth.login.password_enabled',   'true',  'FEATURE', 'GLOBAL', '是否启用账号密码登录',        'BOOLEAN', FALSE, TRUE, 'user', 'auth', '密码登录开关',     101, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000002', 'auth.login.code_enabled',       'true',  'FEATURE', 'GLOBAL', '是否启用验证码登录',          'BOOLEAN', FALSE, TRUE, 'user', 'auth', '验证码登录开关',   102, '00000000-0000-0000-0000-000000000000'),

    -- GitHub OAuth
    ('00000000-0000-0000-0005-000000000010', 'oauth.github.enabled',          'true',                                        'FEATURE', 'GLOBAL', 'GitHub OAuth 开关',              'BOOLEAN', FALSE, TRUE, 'user', 'oauth', 'GitHub 开关',        201, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000011', 'oauth.github.client_id',        '',                                             'SYSTEM',  'GLOBAL', 'GitHub OAuth Client ID',          'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub Client ID',   202, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000012', 'oauth.github.client_secret',    '',                                             'SYSTEM',  'GLOBAL', 'GitHub OAuth Client Secret',      'STRING',  TRUE,  TRUE, 'user', 'oauth', 'GitHub Secret',      203, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000013', 'oauth.github.authorize_url',    'https://github.com/login/oauth/authorize',     'SYSTEM',  'GLOBAL', 'GitHub 授权地址',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub 授权URL',     204, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000014', 'oauth.github.token_url',        'https://github.com/login/oauth/access_token',  'SYSTEM',  'GLOBAL', 'GitHub Token 地址',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub Token URL',   205, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000015', 'oauth.github.user_info_url',    'https://api.github.com/user',                  'SYSTEM',  'GLOBAL', 'GitHub 用户信息地址',              'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub UserInfo URL', 206, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000016', 'oauth.github.scope',            'read:user,user:email',                         'SYSTEM',  'GLOBAL', 'GitHub 授权范围',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub Scope',       207, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000017', 'oauth.github.display_name',     'GitHub',                                       'SYSTEM',  'GLOBAL', 'GitHub 显示名称',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub 显示名',      208, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000018', 'oauth.github.icon',             'github',                                       'SYSTEM',  'GLOBAL', 'GitHub 图标标识',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'GitHub 图标',        209, '00000000-0000-0000-0000-000000000000'),

    -- Google OAuth
    ('00000000-0000-0000-0005-000000000020', 'oauth.google.enabled',          'true',                                         'FEATURE', 'GLOBAL', 'Google OAuth 开关',              'BOOLEAN', FALSE, TRUE, 'user', 'oauth', 'Google 开关',        211, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000021', 'oauth.google.client_id',        '',                                             'SYSTEM',  'GLOBAL', 'Google OAuth Client ID',          'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google Client ID',   212, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000022', 'oauth.google.client_secret',    '',                                             'SYSTEM',  'GLOBAL', 'Google OAuth Client Secret',      'STRING',  TRUE,  TRUE, 'user', 'oauth', 'Google Secret',      213, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000023', 'oauth.google.authorize_url',    'https://accounts.google.com/o/oauth2/v2/auth',  'SYSTEM',  'GLOBAL', 'Google 授权地址',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google 授权URL',     214, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000024', 'oauth.google.token_url',        'https://oauth2.googleapis.com/token',           'SYSTEM',  'GLOBAL', 'Google Token 地址',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google Token URL',   215, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000025', 'oauth.google.user_info_url',    'https://www.googleapis.com/oauth2/v3/userinfo', 'SYSTEM',  'GLOBAL', 'Google 用户信息地址',              'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google UserInfo URL', 216, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000026', 'oauth.google.scope',            'openid profile email',                          'SYSTEM',  'GLOBAL', 'Google 授权范围',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google Scope',       217, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000027', 'oauth.google.display_name',     'Google',                                        'SYSTEM',  'GLOBAL', 'Google 显示名称',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google 显示名',      218, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000028', 'oauth.google.icon',             'google',                                        'SYSTEM',  'GLOBAL', 'Google 图标标识',                  'STRING',  FALSE, TRUE, 'user', 'oauth', 'Google 图标',        219, '00000000-0000-0000-0000-000000000000'),

    -- WeChat OAuth
    ('00000000-0000-0000-0005-000000000030', 'oauth.wechat.enabled',          'false',                                        'FEATURE', 'GLOBAL', '微信 OAuth 开关',                 'BOOLEAN', FALSE, TRUE, 'user', 'oauth', '微信开关',           221, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000031', 'oauth.wechat.client_id',        '',                                             'SYSTEM',  'GLOBAL', '微信 OAuth App ID',               'STRING',  FALSE, TRUE, 'user', 'oauth', '微信 App ID',        222, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000032', 'oauth.wechat.client_secret',    '',                                             'SYSTEM',  'GLOBAL', '微信 OAuth App Secret',           'STRING',  TRUE,  TRUE, 'user', 'oauth', '微信 Secret',        223, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000033', 'oauth.wechat.authorize_url',    'https://open.weixin.qq.com/connect/qrconnect', 'SYSTEM',  'GLOBAL', '微信授权地址',                     'STRING',  FALSE, TRUE, 'user', 'oauth', '微信授权URL',        224, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000034', 'oauth.wechat.token_url',        'https://api.weixin.qq.com/sns/oauth2/access_token', 'SYSTEM', 'GLOBAL', '微信 Token 地址',              'STRING',  FALSE, TRUE, 'user', 'oauth', '微信 Token URL',     225, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000035', 'oauth.wechat.user_info_url',    'https://api.weixin.qq.com/sns/userinfo',       'SYSTEM',  'GLOBAL', '微信用户信息地址',                 'STRING',  FALSE, TRUE, 'user', 'oauth', '微信 UserInfo URL',  226, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000036', 'oauth.wechat.scope',            'snsapi_login',                                 'SYSTEM',  'GLOBAL', '微信授权范围',                     'STRING',  FALSE, TRUE, 'user', 'oauth', '微信 Scope',         227, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000037', 'oauth.wechat.display_name',     '微信',                                         'SYSTEM',  'GLOBAL', '微信显示名称',                     'STRING',  FALSE, TRUE, 'user', 'oauth', '微信显示名',         228, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000038', 'oauth.wechat.icon',             'wechat',                                       'SYSTEM',  'GLOBAL', '微信图标标识',                     'STRING',  FALSE, TRUE, 'user', 'oauth', '微信图标',           229, '00000000-0000-0000-0000-000000000000'),

    -- Apple OAuth
    ('00000000-0000-0000-0005-000000000040', 'oauth.apple.enabled',           'false',                                        'FEATURE', 'GLOBAL', 'Apple OAuth 开关',               'BOOLEAN', FALSE, TRUE, 'user', 'oauth', 'Apple 开关',         231, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000041', 'oauth.apple.client_id',         '',                                             'SYSTEM',  'GLOBAL', 'Apple OAuth Client ID',           'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple Client ID',    232, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000042', 'oauth.apple.client_secret',     '',                                             'SYSTEM',  'GLOBAL', 'Apple OAuth Client Secret',       'STRING',  TRUE,  TRUE, 'user', 'oauth', 'Apple Secret',       233, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000043', 'oauth.apple.authorize_url',     'https://appleid.apple.com/auth/authorize',     'SYSTEM',  'GLOBAL', 'Apple 授权地址',                   'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple 授权URL',      234, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000044', 'oauth.apple.token_url',         'https://appleid.apple.com/auth/token',         'SYSTEM',  'GLOBAL', 'Apple Token 地址',                 'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple Token URL',    235, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000045', 'oauth.apple.user_info_url',     '',                                             'SYSTEM',  'GLOBAL', 'Apple 用户信息地址',               'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple UserInfo URL',  236, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000046', 'oauth.apple.scope',             'name email',                                   'SYSTEM',  'GLOBAL', 'Apple 授权范围',                   'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple Scope',        237, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000047', 'oauth.apple.display_name',      'Apple',                                        'SYSTEM',  'GLOBAL', 'Apple 显示名称',                   'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple 显示名',       238, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000048', 'oauth.apple.icon',              'apple',                                        'SYSTEM',  'GLOBAL', 'Apple 图标标识',                   'STRING',  FALSE, TRUE, 'user', 'oauth', 'Apple 图标',         239, '00000000-0000-0000-0000-000000000000'),

    -- Linux.do OAuth
    ('00000000-0000-0000-0005-000000000050', 'oauth.linux_do.enabled',        'true',                                         'FEATURE', 'GLOBAL', 'Linux.do OAuth 开关',             'BOOLEAN', FALSE, TRUE, 'user', 'oauth', 'Linux.do 开关',      241, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000051', 'oauth.linux_do.client_id',      '',                                             'SYSTEM',  'GLOBAL', 'Linux.do OAuth Client ID',        'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do Client ID',  242, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000052', 'oauth.linux_do.client_secret',  '',                                             'SYSTEM',  'GLOBAL', 'Linux.do OAuth Client Secret',    'STRING',  TRUE,  TRUE, 'user', 'oauth', 'Linux.do Secret',    243, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000053', 'oauth.linux_do.authorize_url',  'https://connect.linux.do/oauth2/authorize',    'SYSTEM',  'GLOBAL', 'Linux.do 授权地址',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do 授权URL',   244, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000054', 'oauth.linux_do.token_url',      'https://connect.linux.do/oauth2/token',        'SYSTEM',  'GLOBAL', 'Linux.do Token 地址',              'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do Token URL',  245, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000055', 'oauth.linux_do.user_info_url',  'https://connect.linux.do/api/user',            'SYSTEM',  'GLOBAL', 'Linux.do 用户信息地址',            'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do UserInfo',  246, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000056', 'oauth.linux_do.scope',          'read',                                         'SYSTEM',  'GLOBAL', 'Linux.do 授权范围',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do Scope',     247, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000057', 'oauth.linux_do.display_name',   'Linux.do',                                     'SYSTEM',  'GLOBAL', 'Linux.do 显示名称',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do 显示名',    248, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0005-000000000058', 'oauth.linux_do.icon',           'linux_do',                                     'SYSTEM',  'GLOBAL', 'Linux.do 图标标识',                'STRING',  FALSE, TRUE, 'user', 'oauth', 'Linux.do 图标',      249, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 8. 运行时参数动态配置（Runtime Config）
-- 各模块核心参数，支持通过 API 动态调整，所有实例自动同步
-- =====================================================

INSERT INTO t_system_config (id, config_key, config_value, config_type, scope, description, value_type, enabled, module, group_name, display_name, sort_order, created_by) VALUES
    -- Agent 模块 - execution
    ('00000000-0000-0000-0003-000000000001', 'runtime.agent.max_concurrent_executions',    '100',     'LIMIT',   'GLOBAL', 'Agent 最大并发执行数（Semaphore 许可数）',          'INTEGER', TRUE, 'agent', 'execution', '最大并发执行数',       301, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000002', 'runtime.agent.acquire_timeout_ms',           '30000',   'LIMIT',   'GLOBAL', 'Agent 获取执行许可超时时间（毫秒）',                 'INTEGER', TRUE, 'agent', 'execution', '执行许可超时',         302, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000003', 'runtime.agent.execution_stale_threshold_ms', '600000',  'LIMIT',   'GLOBAL', 'Agent 执行超时阈值（毫秒），超过视为卡死',           'INTEGER', TRUE, 'agent', 'execution', '执行超时阈值',         303, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000004', 'runtime.agent.max_iterations',               '20',      'LIMIT',   'GLOBAL', 'Agent ReAct 最大 LLM 调用次数',                    'INTEGER', TRUE, 'agent', 'execution', '最大迭代次数',         304, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000005', 'runtime.agent.default_model',                'llm-google-gemini-2.5-flash','LIMIT',   'GLOBAL', 'Agent 默认模型 ID（需与 t_llm_provider.id 对应）',  'STRING',  TRUE, 'agent', 'execution', '默认模型',             305, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000024', 'runtime.agent.rag_enabled',                  'false',   'FEATURE', 'GLOBAL', 'Agent RAG 向量记忆功能开关',                        'BOOLEAN', TRUE, 'agent', 'execution', 'RAG 功能开关',         311, '00000000-0000-0000-0000-000000000000'),
    -- Agent 模块 - session
    ('00000000-0000-0000-0003-000000000006', 'runtime.agent.session.max_active_per_scope', '5',       'LIMIT',   'GLOBAL', '每个 scope（剧本）最大活跃会话数',                   'INTEGER', TRUE, 'agent', 'session',   '每 scope 活跃会话数', 306, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000007', 'runtime.agent.session.max_active_global',    '3',       'LIMIT',   'GLOBAL', '用户全局最大活跃会话数',                             'INTEGER', TRUE, 'agent', 'session',   '全局活跃会话数',       307, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000035', 'runtime.agent.session.max_total_per_user',   '200',     'LIMIT',   'GLOBAL', '用户最大会话总数',                                   'INTEGER', TRUE, 'agent', 'session',   '用户会话总数上限',     350, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000058', 'runtime.agent.session_cache.max_size',       '10000',   'LIMIT',   'GLOBAL', 'Session 缓存最大容量',                               'INTEGER', TRUE, 'agent', 'session',   '缓存最大容量',         510, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000059', 'runtime.agent.session_cache.expire_minutes', '5',       'LIMIT',   'GLOBAL', 'Session 缓存过期时间（分钟）',                        'INTEGER', TRUE, 'agent', 'session',   '缓存过期时间',         511, '00000000-0000-0000-0000-000000000000'),
    -- Agent 模块 - mission
    ('00000000-0000-0000-0003-000000000008', 'runtime.agent.mission.max_steps',            '50',      'LIMIT',   'GLOBAL', 'Mission 最大步骤数上限',                             'INTEGER', TRUE, 'agent', 'mission',   '最大步骤数',           308, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000009', 'runtime.agent.mission.loop_fail_threshold',  '10',      'LIMIT',   'GLOBAL', 'Mission 循环检测失败阈值（连续无进展步骤数）',         'INTEGER', TRUE, 'agent', 'mission',   '循环检测失败阈值',     309, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000010', 'runtime.agent.mission.max_retries',          '3',       'LIMIT',   'GLOBAL', 'Mission 步骤失败最大重试次数',                        'INTEGER', TRUE, 'agent', 'mission',   '步骤最大重试次数',     310, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000023', 'runtime.agent.mission.max_concurrent_per_workspace', '3', 'LIMIT', 'GLOBAL', '每个工作区最大并发 Mission 数',                       'INTEGER', TRUE, 'agent', 'mission',   '工作区并发 Mission 数',310, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000036', 'runtime.agent.mission.max_context_steps',    '10',      'LIMIT',   'GLOBAL', 'Mission Prompt 最大上下文步骤数',                     'INTEGER', TRUE, 'agent', 'mission',   '上下文最大步骤数',     360, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000037', 'runtime.agent.mission.max_step_summary_chars','500',    'LIMIT',   'GLOBAL', 'Mission 步骤摘要最大字符数',                          'INTEGER', TRUE, 'agent', 'mission',   '步骤摘要最大字符数',   361, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000038', 'runtime.agent.mission.loop_warn_threshold',  '5',       'LIMIT',   'GLOBAL', 'Mission 循环检测警告阈值',                            'INTEGER', TRUE, 'agent', 'mission',   '循环检测警告阈值',     362, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000060', 'runtime.agent.mission.sse_timeout_ms',       '1800000', 'LIMIT',   'GLOBAL', 'Mission SSE 超时时间（毫秒）',                        'INTEGER', TRUE, 'agent', 'mission',   'SSE 超时时间',         512, '00000000-0000-0000-0000-000000000000'),
    -- Agent 模块 - billing
    ('00000000-0000-0000-0003-000000000025', 'runtime.agent.billing.idle_timeout_minutes', '30',      'LIMIT',   'GLOBAL', 'Agent 计费会话空闲超时时间（分钟）',                   'INTEGER', TRUE, 'agent', 'billing',   '空闲超时时间',         312, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000029', 'runtime.agent.billing.batch_size',           '50',      'LIMIT',   'GLOBAL', 'Agent 计费批量处理数量',                              'INTEGER', TRUE, 'agent', 'billing',   '计费批量处理数',       340, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000030', 'runtime.agent.billing.max_retry_count',      '3',       'LIMIT',   'GLOBAL', 'Agent 计费失败最大重试次数',                           'INTEGER', TRUE, 'agent', 'billing',   '计费最大重试次数',     341, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000031', 'runtime.agent.billing.default_freeze_amount','100',     'LIMIT',   'GLOBAL', 'Agent 默认预冻结积分数',                              'INTEGER', TRUE, 'agent', 'billing',   '默认预冻结积分',       342, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000032', 'runtime.agent.billing.freeze_threshold_ratio','0.8',    'LIMIT',   'GLOBAL', 'Agent 追加冻结阈值比例（已消费/已冻结）',               'STRING',  TRUE, 'agent', 'billing',   '追加冻结阈值比例',     343, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000033', 'runtime.agent.billing.default_input_price',  '0.5',     'LIMIT',   'GLOBAL', 'Agent 默认输入价格（积分/1K tokens）',                 'STRING',  TRUE, 'agent', 'billing',   '默认输入价格',         344, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000034', 'runtime.agent.billing.default_output_price', '1.5',     'LIMIT',   'GLOBAL', 'Agent 默认输出价格（积分/1K tokens）',                 'STRING',  TRUE, 'agent', 'billing',   '默认输出价格',         345, '00000000-0000-0000-0000-000000000000'),
    -- Billing 模块 - points
    ('00000000-0000-0000-0003-000000000026', 'runtime.billing.points_per_major_unit',      '10',      'LIMIT',   'GLOBAL', '每主要货币单位对应积分数（如 1 USD = 10 积分）',        'INTEGER', TRUE, 'billing', 'points',  '每主货币单位积分数',   330, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000027', 'runtime.billing.minor_per_major_unit',       '100',     'LIMIT',   'GLOBAL', '1 个主货币单位对应最小货币单位数量（如 USD 的 cent）',   'INTEGER', TRUE, 'billing', 'points',  '最小货币单位数',       331, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000028', 'runtime.billing.subscription_currency',      'USD',     'LIMIT',   'GLOBAL', '订阅默认币种',                                       'STRING',  TRUE, 'billing', 'points',  '默认币种',             332, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000043', 'runtime.billing.enabled_payment_providers',  'STRIPE,WECHATPAY', 'FEATURE', 'GLOBAL', '启用的支付渠道（逗号分隔，可选: STRIPE/WECHATPAY/ALIPAY）', 'STRING',  TRUE, 'billing', 'payment', '启用的支付渠道',       333, '00000000-0000-0000-0000-000000000000'),
    -- AI 模块 - rate_limit
    ('00000000-0000-0000-0003-000000000011', 'runtime.ai.max_active_polls',                '100',     'LIMIT',   'GLOBAL', '最大并发轮询任务数',                                 'INTEGER', TRUE, 'ai', 'rate_limit',  '最大并发轮询数',       311, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000012', 'runtime.ai.default_rate_limit',              '60',      'LIMIT',   'GLOBAL', 'AI Provider 默认每分钟请求限制',                     'INTEGER', TRUE, 'ai', 'rate_limit',  '默认请求限制',         312, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000015', 'runtime.ai.failure_rate_threshold',          '50',      'LIMIT',   'GLOBAL', '熔断器失败率阈值（百分比）',                          'INTEGER', TRUE, 'ai', 'rate_limit',  '熔断失败率阈值',       315, '00000000-0000-0000-0000-000000000000'),
    -- AI 模块 - retry
    ('00000000-0000-0000-0003-000000000013', 'runtime.ai.default_max_retries',             '3',       'LIMIT',   'GLOBAL', 'AI Provider 默认最大重试次数',                       'INTEGER', TRUE, 'ai', 'retry',       '默认最大重试次数',     313, '00000000-0000-0000-0000-000000000000'),
    -- AI 模块 - groovy
    ('00000000-0000-0000-0003-000000000014', 'runtime.ai.groovy_max_execution_time_ms',    '300000',  'LIMIT',   'GLOBAL', 'Groovy 脚本最大执行时间（毫秒）',                    'INTEGER', TRUE, 'ai', 'groovy',      'Groovy 执行超时',      314, '00000000-0000-0000-0000-000000000000'),
    -- AI 模块 - alert
    ('00000000-0000-0000-0003-000000000039', 'runtime.ai.alert_enabled',                   'true',    'FEATURE', 'GLOBAL', 'AI 告警功能开关',                                    'BOOLEAN', TRUE, 'ai', 'alert',       '告警功能开关',         370, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000040', 'runtime.ai.alert_error_rate_threshold',      '0.1',     'LIMIT',   'GLOBAL', 'AI 告警错误率阈值（0-1）',                            'STRING',  TRUE, 'ai', 'alert',       '告警错误率阈值',       371, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000041', 'runtime.ai.alert_response_time_threshold_ms','30000',   'LIMIT',   'GLOBAL', 'AI 告警响应时间阈值（毫秒）',                         'INTEGER', TRUE, 'ai', 'alert',       '告警响应时间阈值',     372, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000042', 'runtime.ai.alert_consecutive_failures_threshold', '5',  'LIMIT',   'GLOBAL', 'AI 告警连续失败阈值',                                 'INTEGER', TRUE, 'ai', 'alert',       '告警连续失败阈值',     373, '00000000-0000-0000-0000-000000000000'),
    -- AI 模块 - http
    ('00000000-0000-0000-0003-000000000061', 'runtime.ai.http_connect_timeout_ms',         '10000',   'LIMIT',   'GLOBAL', '插件 HTTP 连接超时（毫秒）',                          'INTEGER', TRUE, 'ai', 'http',        'HTTP 连接超时',        520, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000062', 'runtime.ai.http_read_timeout_seconds',       '60',      'LIMIT',   'GLOBAL', '插件 HTTP 读取超时（秒）',                            'INTEGER', TRUE, 'ai', 'http',        'HTTP 读取超时',        521, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000063', 'runtime.ai.http_max_connections',             '500',     'LIMIT',   'GLOBAL', '插件 HTTP 最大连接数',                               'INTEGER', TRUE, 'ai', 'http',        'HTTP 最大连接数',      522, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000064', 'runtime.ai.http_max_connections_per_route',   '50',      'LIMIT',   'GLOBAL', '插件 HTTP 每路由最大连接数',                          'INTEGER', TRUE, 'ai', 'http',        'HTTP 每路由最大连接',  523, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - general
    ('00000000-0000-0000-0003-000000000016', 'runtime.task.default_timeout_seconds',       '300',     'LIMIT',   'GLOBAL', '任务默认超时时间（秒）',                              'INTEGER', TRUE, 'task', 'general',    '默认超时时间',         316, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000017', 'runtime.task.default_max_retry',             '3',       'LIMIT',   'GLOBAL', '任务默认最大重试次数',                                'INTEGER', TRUE, 'task', 'general',    '默认最大重试次数',     317, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000020', 'runtime.task.generation_lock_expire_seconds', '600',    'LIMIT',   'GLOBAL', 'AI 生成任务分布式锁超时时间（秒）',                    'INTEGER', TRUE, 'task', 'generation', '生成锁超时时间',       320, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - compensation
    ('00000000-0000-0000-0003-000000000018', 'runtime.task.compensation.max_retry_count',  '5',       'LIMIT',   'GLOBAL', '补偿任务最大重试次数',                                'INTEGER', TRUE, 'task', 'compensation', '补偿最大重试次数',   318, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000019', 'runtime.task.compensation.scan_interval_ms', '30000',   'LIMIT',   'GLOBAL', '补偿任务扫描间隔（毫秒）',                            'INTEGER', TRUE, 'task', 'compensation', '补偿扫描间隔',       319, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - feign
    ('00000000-0000-0000-0003-000000000022', 'runtime.task.ai_feign_read_timeout_ms',      '240000',  'LIMIT',   'GLOBAL', 'Task 调用 AI 服务 Feign 读取超时时间（毫秒）',         'INTEGER', TRUE, 'task', 'feign',      'AI Feign 读取超时',    322, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000067', 'runtime.task.feign_connect_timeout_ms',      '10000',   'LIMIT',   'GLOBAL', 'Feign 连接超时（毫秒）',                              'INTEGER', TRUE, 'task', 'feign',      'Feign 连接超时',       532, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000068', 'runtime.task.ai_service_url',                'http://actionow-ai:8086', 'SYSTEM', 'GLOBAL', 'AI 服务 URL',                           'STRING',  TRUE, 'task', 'feign',      'AI 服务地址',          533, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - concurrency
    ('00000000-0000-0000-0003-000000000065', 'runtime.task.scope_max_items',               '500',     'LIMIT',   'GLOBAL', 'Scope 展开后最大 item 数量',                          'INTEGER', TRUE, 'task', 'concurrency', 'Scope 最大 item 数', 530, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000066', 'runtime.task.workspace_batch_limit',         '10',      'LIMIT',   'GLOBAL', '每个工作空间最大批量并发数',                           'INTEGER', TRUE, 'task', 'concurrency', '工作空间批量并发数', 531, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - polling
    ('00000000-0000-0000-0003-000000000069', 'runtime.task.polling.scan_interval_ms',      '15000',   'LIMIT',   'GLOBAL', 'POLLING 模式轮询扫描间隔（毫秒）',                    'INTEGER', TRUE, 'task', 'polling',    '轮询扫描间隔',         534, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000070', 'runtime.task.polling.batch_size',             '50',      'LIMIT',   'GLOBAL', 'POLLING 模式每次扫描任务数',                          'INTEGER', TRUE, 'task', 'polling',    '轮询批量大小',         535, '00000000-0000-0000-0000-000000000000'),
    -- Task 模块 - batch
    ('00000000-0000-0000-0003-000000000071', 'runtime.task.batch.stale_timeout_ms',        '600000',  'LIMIT',   'GLOBAL', 'Batch 作业无进展超时（毫秒）',                         'INTEGER', TRUE, 'task', 'batch',      'Batch 超时时间',       536, '00000000-0000-0000-0000-000000000000'),
    -- Gateway 模块 - rate_limit
    ('00000000-0000-0000-0003-000000000043', 'runtime.gateway.rate_limit.enabled',         'true',    'FEATURE', 'GLOBAL', 'Gateway 限流功能开关',                                'BOOLEAN', TRUE, 'gateway', 'rate_limit', '限流功能开关',     380, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000044', 'runtime.gateway.rate_limit.global_limit',    '1000',    'LIMIT',   'GLOBAL', 'Gateway 全局限流（每秒请求数）',                       'INTEGER', TRUE, 'gateway', 'rate_limit', '全局限流',         381, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000045', 'runtime.gateway.rate_limit.ip_limit',        '200',     'LIMIT',   'GLOBAL', 'Gateway IP 限流（每窗口请求数）',                      'INTEGER', TRUE, 'gateway', 'rate_limit', 'IP 限流',          382, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000046', 'runtime.gateway.rate_limit.ip_window',       '60',      'LIMIT',   'GLOBAL', 'Gateway IP 限流时间窗口（秒）',                        'INTEGER', TRUE, 'gateway', 'rate_limit', 'IP 限流窗口',      383, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000047', 'runtime.gateway.rate_limit.user_limit',      '300',     'LIMIT',   'GLOBAL', 'Gateway 用户限流（每窗口请求数）',                      'INTEGER', TRUE, 'gateway', 'rate_limit', '用户限流',         384, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000048', 'runtime.gateway.rate_limit.user_window',     '60',      'LIMIT',   'GLOBAL', 'Gateway 用户限流时间窗口（秒）',                        'INTEGER', TRUE, 'gateway', 'rate_limit', '用户限流窗口',     385, '00000000-0000-0000-0000-000000000000'),
    -- Gateway 模块 - cors
    ('00000000-0000-0000-0003-000000000049', 'runtime.gateway.cors.enabled',               'true',    'FEATURE', 'GLOBAL', 'Gateway CORS 功能开关',                               'BOOLEAN', TRUE, 'gateway', 'cors',       'CORS 开关',        386, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000050', 'runtime.gateway.cors.allowed_origins',       '*',       'LIMIT',   'GLOBAL', 'Gateway CORS 允许的源（逗号分隔）',                     'STRING',  TRUE, 'gateway', 'cors',       'CORS 允许源',      387, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000051', 'runtime.gateway.cors.allow_credentials',     'true',    'FEATURE', 'GLOBAL', 'Gateway CORS 是否允许携带凭证',                        'BOOLEAN', TRUE, 'gateway', 'cors',       'CORS 允许凭证',    388, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000052', 'runtime.gateway.cors.max_age',               '3600',    'LIMIT',   'GLOBAL', 'Gateway CORS 预检请求缓存时间（秒）',                   'INTEGER', TRUE, 'gateway', 'cors',       'CORS 缓存时间',    389, '00000000-0000-0000-0000-000000000000'),
    -- Gateway 模块 - api_limits
    ('00000000-0000-0000-0003-000000000074', 'runtime.gateway.rate_limit.api_limits',      '{"/api/ai/model-providers":10,"/api/ai/groovy-templates":10,"/api/tasks/ai/":10,"/api/files/upload":5,"/api/user/auth/login":10,"/api/user/auth/register":5}', 'LIMIT', 'GLOBAL', 'Gateway API 特定限流配置（JSON：路径前缀→每秒限制数）', 'JSON', TRUE, 'gateway', 'rate_limit', 'API 特定限流', 390, '00000000-0000-0000-0000-000000000000'),
    -- Gateway 模块 - log
    ('00000000-0000-0000-0003-000000000075', 'runtime.gateway.log.enabled',               'true',    'FEATURE', 'GLOBAL', 'Gateway 请求日志开关',                                'BOOLEAN', TRUE, 'gateway', 'log',        '请求日志开关',     391, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000076', 'runtime.gateway.log.log_headers',           'true',    'FEATURE', 'GLOBAL', 'Gateway 请求头日志开关',                               'BOOLEAN', TRUE, 'gateway', 'log',        '请求头日志',       392, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000077', 'runtime.gateway.log.log_body',              'true',    'FEATURE', 'GLOBAL', 'Gateway 请求体日志开关',                               'BOOLEAN', TRUE, 'gateway', 'log',        '请求体日志',       393, '00000000-0000-0000-0000-000000000000'),
    -- Project 模块 - trash
    ('00000000-0000-0000-0003-000000000053', 'runtime.project.trash_retention_days',       '30',      'LIMIT',   'GLOBAL', '回收站保留天数',                                      'INTEGER', TRUE, 'project', 'trash',     '回收站保留天数',     500, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000054', 'runtime.project.trash_auto_cleanup_enabled', 'true',    'FEATURE', 'GLOBAL', '回收站自动清理开关',                                   'BOOLEAN', TRUE, 'project', 'trash',     '自动清理开关',       501, '00000000-0000-0000-0000-000000000000'),
    -- Project 模块 - version
    ('00000000-0000-0000-0003-000000000055', 'runtime.project.version_max_keep_count',     '50',      'LIMIT',   'GLOBAL', '每个实体保留最大版本数',                                'INTEGER', TRUE, 'project', 'version',   '最大保留版本数',     502, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000056', 'runtime.project.version_cleanup_threshold',  '60',      'LIMIT',   'GLOBAL', '触发版本清理的版本数阈值',                              'INTEGER', TRUE, 'project', 'version',   '清理触发阈值',       503, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000057', 'runtime.project.version_cleanup_batch_size', '100',     'LIMIT',   'GLOBAL', '版本清理每批处理实体数',                                'INTEGER', TRUE, 'project', 'version',   '清理批量大小',       504, '00000000-0000-0000-0000-000000000000'),
    -- MQ 模块 - consumer
    ('00000000-0000-0000-0003-000000000021', 'runtime.mq.prefetch_count',                  '10',      'LIMIT',   'GLOBAL', 'RabbitMQ Consumer prefetch 数量',                    'INTEGER', TRUE, 'mq', 'consumer',       'Prefetch 数量',       321, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000072', 'runtime.mq.task_concurrency',                '5',       'LIMIT',   'GLOBAL', 'AI 任务队列并发 consumer 数',                         'INTEGER', TRUE, 'mq', 'consumer',       '任务队列并发数',       537, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0003-000000000073', 'runtime.mq.task_max_concurrency',            '10',      'LIMIT',   'GLOBAL', 'AI 任务队列并发 consumer 数上限',                     'INTEGER', TRUE, 'mq', 'consumer',       '任务队列并发上限',     538, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 9. 积分充值汇率配置（Billing Topup Rates）
-- =====================================================

INSERT INTO t_system_config (id, config_key, config_value, config_type, scope, description, default_value, value_type, enabled, module, group_name, display_name, sort_order, created_by) VALUES
    ('00000000-0000-0000-0004-000000000001', 'billing.topup.rate.CNY', '{"pointsPerMajorUnit": 10, "minorPerMajorUnit": 100}', 'SYSTEM', 'GLOBAL', 'CNY 积分汇率: 1元=10积分, 最小单位=分',          '{"pointsPerMajorUnit": 10, "minorPerMajorUnit": 100}', 'JSON', TRUE, 'billing', 'topup_rate', 'CNY 积分汇率', 401, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0004-000000000002', 'billing.topup.rate.USD', '{"pointsPerMajorUnit": 10, "minorPerMajorUnit": 100}', 'SYSTEM', 'GLOBAL', 'USD 积分汇率: 1USD=10积分, 最小单位=cent',       '{"pointsPerMajorUnit": 10, "minorPerMajorUnit": 100}', 'JSON', TRUE, 'billing', 'topup_rate', 'USD 积分汇率', 402, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'System initial data inserted successfully!';
END $$;

-- =====================================================
-- 3. Groovy 模板库
-- =====================================================


-- =====================================================
-- 1. 认证模板 (Authentication Templates)
-- =====================================================

-- -----------------------------------------------------
-- 1.1 API Key Header 认证
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000001',
    'Auth: API Key Header',
    'API Key 请求头认证，支持自定义 Header 名称',
    'REQUEST_BUILDER',
    'ALL',
    '// API Key Header Authentication
// 可用变量: inputs, config, headers

// 获取配置
def apiKey = config.apiKey ?: config.api_key ?: config.authConfig?.apiKey
def headerName = config.headerName ?: config.header_name ?: "X-API-Key"

if (!apiKey) {
    throw new IllegalArgumentException("缺少 API Key 配置")
}

// 设置认证头
headers[headerName] = apiKey

// 返回请求体（透传输入）
return inputs',
    '1.0.0',
    true,
    true,
    '{"config": {"apiKey": "sk-xxx", "headerName": "X-API-Key"}, "inputs": {"prompt": "hello"}}'::jsonb,
    '{"prompt": "hello"}'::jsonb,
    '## API Key Header 认证

### 配置参数
- `apiKey` / `api_key`: API 密钥（必需）
- `headerName` / `header_name`: 请求头名称，默认 "X-API-Key"

### 常见 Header 名称
- `X-API-Key`: 通用格式
- `api-key`: Azure OpenAI
- `Authorization`: 需配合 "Api-Key {key}" 格式'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 1.2 Bearer Token 认证
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000002',
    'Auth: Bearer Token',
    'Bearer Token (JWT/OAuth) 认证',
    'REQUEST_BUILDER',
    'ALL',
    '// Bearer Token Authentication
// 可用变量: inputs, config, headers

def token = config.token ?: config.accessToken ?: config.access_token ?: config.authConfig?.token
def prefix = config.tokenPrefix ?: "Bearer"

if (!token) {
    throw new IllegalArgumentException("缺少 Token 配置")
}

// 设置 Authorization 头
headers["Authorization"] = "${prefix} ${token}"

// 返回请求体
return inputs',
    '1.0.0',
    true,
    true,
    '{"config": {"token": "eyJhbG..."}, "inputs": {"prompt": "hello"}}'::jsonb,
    '{"prompt": "hello"}'::jsonb,
    '## Bearer Token 认证

### 配置参数
- `token` / `accessToken`: 访问令牌（必需）
- `tokenPrefix`: 前缀，默认 "Bearer"

### 适用场景
- OAuth 2.0 Access Token
- JWT Token
- 自定义 Token 认证'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 1.3 Basic Auth 认证
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000003',
    'Auth: Basic Authentication',
    'HTTP Basic 认证（用户名:密码）',
    'REQUEST_BUILDER',
    'ALL',
    '// Basic Authentication
// 可用变量: inputs, config, headers, crypto

def username = config.username ?: config.authConfig?.username
def password = config.password ?: config.authConfig?.password

if (!username || !password) {
    throw new IllegalArgumentException("缺少用户名或密码配置")
}

// Base64 编码
def credentials = "${username}:${password}"
def encoded = crypto.base64Encode(credentials)

// 设置 Authorization 头
headers["Authorization"] = "Basic ${encoded}"

return inputs',
    '1.0.0',
    true,
    true,
    '{"config": {"username": "user", "password": "pass"}, "inputs": {}}'::jsonb,
    '{}'::jsonb,
    '## Basic Authentication

### 配置参数
- `username`: 用户名（必需）
- `password`: 密码（必需）

### 工作原理
将 `username:password` 进行 Base64 编码后放入 Authorization 头'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 1.4 HMAC 签名认证
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000004',
    'Auth: HMAC Signature',
    'HMAC 签名认证，支持多种算法',
    'REQUEST_BUILDER',
    'ALL',
    '// HMAC Signature Authentication
// 可用变量: inputs, config, headers, crypto, json

def accessKey = config.accessKey ?: config.access_key ?: config.authConfig?.accessKey
def secretKey = config.secretKey ?: config.secret_key ?: config.authConfig?.secretKey
def algorithm = config.algorithm ?: "HmacSHA256"

if (!accessKey || !secretKey) {
    throw new IllegalArgumentException("缺少 AccessKey 或 SecretKey")
}

// 生成时间戳和随机数
def timestamp = System.currentTimeMillis().toString()
def nonce = UUID.randomUUID().toString().replace("-", "")

// 构建签名字符串
def body = json.toJson(inputs)
def signString = "${timestamp}\n${nonce}\n${body}"

// 计算签名
def signature = crypto.hmacSha256(signString, secretKey)

// 设置请求头
headers["X-Access-Key"] = accessKey
headers["X-Timestamp"] = timestamp
headers["X-Nonce"] = nonce
headers["X-Signature"] = signature

return inputs',
    '1.0.0',
    true,
    true,
    '{"config": {"accessKey": "ak-xxx", "secretKey": "sk-xxx"}, "inputs": {"data": "test"}}'::jsonb,
    '{"data": "test"}'::jsonb,
    '## HMAC 签名认证

### 配置参数
- `accessKey`: 访问密钥 ID（必需）
- `secretKey`: 访问密钥 Secret（必需）
- `algorithm`: 签名算法，默认 HmacSHA256

### 签名格式
```
signString = timestamp + "\n" + nonce + "\n" + body
signature = HMAC-SHA256(signString, secretKey)
```

### 请求头
- `X-Access-Key`: 访问密钥 ID
- `X-Timestamp`: 毫秒时间戳
- `X-Nonce`: 随机字符串
- `X-Signature`: HMAC 签名'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 1.5 OAuth 2.0 Client Credentials
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000005',
    'Auth: OAuth 2.0 Client Credentials',
    'OAuth 2.0 客户端凭证模式，自动获取和刷新 Token',
    'REQUEST_BUILDER',
    'ALL',
    '// OAuth 2.0 Client Credentials Flow
// 可用变量: inputs, config, headers, http, json

def clientId = config.clientId ?: config.client_id
def clientSecret = config.clientSecret ?: config.client_secret
def tokenUrl = config.tokenUrl ?: config.token_url
def scope = config.scope ?: ""

if (!clientId || !clientSecret || !tokenUrl) {
    throw new IllegalArgumentException("缺少 OAuth 配置: clientId, clientSecret, tokenUrl")
}

// 获取 Token（实际使用时建议缓存）
def tokenResponse = http.post(tokenUrl, [
    grant_type: "client_credentials",
    client_id: clientId,
    client_secret: clientSecret,
    scope: scope
], [
    "Content-Type": "application/x-www-form-urlencoded"
])

def tokenData = json.parseJson(tokenResponse)
def accessToken = tokenData.access_token

if (!accessToken) {
    throw new RuntimeException("获取 OAuth Token 失败: " + tokenResponse)
}

// 设置 Bearer Token
headers["Authorization"] = "Bearer ${accessToken}"

return inputs',
    '1.0.0',
    true,
    true,
    '{"config": {"clientId": "xxx", "clientSecret": "xxx", "tokenUrl": "https://auth.example.com/oauth/token"}}'::jsonb,
    '{}'::jsonb,
    '## OAuth 2.0 Client Credentials

### 配置参数
- `clientId`: 客户端 ID（必需）
- `clientSecret`: 客户端密钥（必需）
- `tokenUrl`: Token 端点 URL（必需）
- `scope`: 权限范围（可选）

### 注意事项
- 每次请求都会获取新 Token，生产环境建议缓存
- 支持标准 OAuth 2.0 Client Credentials Grant'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 2. HTTP 请求构建模板
-- =====================================================

-- -----------------------------------------------------
-- 2.1 通用 JSON POST 请求
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000101',
    'HTTP: JSON POST Request',
    '通用 JSON POST 请求构建器，支持字段映射和默认值',
    'REQUEST_BUILDER',
    'ALL',
    '// Generic JSON POST Request Builder
// 可用变量: inputs, config, headers

// 设置 Content-Type
headers["Content-Type"] = "application/json"

// 获取字段映射配置
def fieldMapping = config.fieldMapping ?: [:]
def defaults = config.defaults ?: [:]

// 构建请求体
def body = [:]

// 应用默认值
defaults.each { k, v -> body[k] = v }

// 映射输入字段
inputs.each { key, value ->
    if (value != null) {
        def targetKey = fieldMapping[key] ?: key
        body[targetKey] = value
    }
}

// 添加模型配置（如果有）
if (config.model) {
    body.model = config.model
}

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"model": "gpt-4", "fieldMapping": {"text": "prompt"}, "defaults": {"temperature": 0.7}}, "inputs": {"text": "hello"}}'::jsonb,
    '{"model": "gpt-4", "temperature": 0.7, "prompt": "hello"}'::jsonb,
    '## 通用 JSON POST 请求

### 配置参数
- `model`: 模型名称（可选）
- `fieldMapping`: 字段名映射 {"源字段": "目标字段"}
- `defaults`: 默认值 {"字段": 值}

### 功能
1. 自动设置 Content-Type
2. 支持字段名重映射
3. 支持默认值注入
4. 自动过滤 null 值'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 2.2 带图片 Base64 的请求
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000102',
    'HTTP: Request with Image Base64',
    '支持图片 URL 自动转 Base64 的请求构建器',
    'REQUEST_BUILDER',
    'IMAGE',
    '// Request Builder with Image Base64 Conversion
// 可用变量: inputs, config, headers, http, asset

headers["Content-Type"] = "application/json"

def body = [:]

// 复制基础字段
["prompt", "negative_prompt", "size", "n", "guidance_scale", "seed"].each { field ->
    if (inputs[field] != null) {
        body[field] = inputs[field]
    }
}

// 处理图片字段
def imageField = config.imageField ?: "image"
def imageUrl = inputs.image_url ?: inputs.imageUrl ?: inputs.image

if (imageUrl) {
    // 下载并转换为 Base64
    def imageBytes = asset.downloadImage(imageUrl)
    def base64 = asset.toBase64(imageBytes)
    def mimeType = asset.detectMimeType(imageBytes)

    // 根据配置选择格式
    if (config.imageFormat == "data_uri") {
        body[imageField] = "data:${mimeType};base64,${base64}"
    } else {
        body[imageField] = base64
    }
}

// 添加模型
if (config.model) {
    body.model = config.model
}

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"model": "sd-v1.5", "imageField": "init_image"}, "inputs": {"prompt": "a cat", "image_url": "https://example.com/img.jpg"}}'::jsonb,
    '{"model": "sd-v1.5", "prompt": "a cat", "init_image": "base64..."}'::jsonb,
    '## 图片 Base64 请求构建器

### 配置参数
- `imageField`: 图片字段名，默认 "image"
- `imageFormat`: 格式，"data_uri" 或 "raw"（默认）
- `model`: 模型名称

### 图片输入
- `image_url` / `imageUrl` / `image`: 图片 URL

### 功能
1. 自动下载远程图片
2. 转换为 Base64 编码
3. 自动检测 MIME 类型'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 2.3 带图片预处理的请求
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000103',
    'HTTP: Request with Image Preprocessing',
    '支持图片压缩、裁剪、调整大小的请求构建器',
    'REQUEST_BUILDER',
    'IMAGE',
    '// Request Builder with Image Preprocessing
// 可用变量: inputs, config, headers, asset

headers["Content-Type"] = "application/json"

def body = [:]

// 复制文本字段
["prompt", "negative_prompt", "style", "seed"].each { field ->
    if (inputs[field] != null) {
        body[field] = inputs[field]
    }
}

// 获取预处理配置
def imageConfig = config.imageProcessing ?: [:]
def maxWidth = imageConfig.maxWidth ?: 1024
def maxHeight = imageConfig.maxHeight ?: 1024
def quality = imageConfig.quality ?: 0.85
def format = imageConfig.format ?: "jpg"

// 处理图片
def imageUrl = inputs.image_url ?: inputs.imageUrl ?: inputs.image
if (imageUrl) {
    // 下载图片
    def imageBytes = asset.downloadImage(imageUrl)

    // 获取原始信息
    def info = asset.getImageInfo(imageBytes)
    log.info("原始图片: {}x{}, {}KB", info.width, info.height, info.sizeBytes / 1024)

    // 按需调整大小
    if (info.width > maxWidth || info.height > maxHeight) {
        imageBytes = asset.resizeImageKeepRatio(imageBytes, maxWidth, maxHeight, format)
        log.info("调整后大小: {}KB", imageBytes.length / 1024)
    }

    // 压缩
    if (quality < 1.0) {
        imageBytes = asset.compressImage(imageBytes, quality as float, format)
        log.info("压缩后大小: {}KB", imageBytes.length / 1024)
    }

    // 转 Base64
    body.image = asset.toBase64(imageBytes)
}

// 添加模型
if (config.model) {
    body.model = config.model
}

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"model": "sd-v1.5", "imageProcessing": {"maxWidth": 512, "maxHeight": 512, "quality": 0.8, "format": "jpg"}}}'::jsonb,
    '{"model": "sd-v1.5", "prompt": "...", "image": "base64..."}'::jsonb,
    '## 图片预处理请求构建器

### 配置参数
- `imageProcessing.maxWidth`: 最大宽度，默认 1024
- `imageProcessing.maxHeight`: 最大高度，默认 1024
- `imageProcessing.quality`: 压缩质量 0-1，默认 0.85
- `imageProcessing.format`: 输出格式，默认 "jpg"

### 功能
1. 自动下载远程图片
2. 按比例调整大小（不超过最大尺寸）
3. 压缩优化（减少传输大小）
4. 日志记录处理过程'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 3. 响应处理模板
-- =====================================================

-- -----------------------------------------------------
-- 3.1 标准 JSON 响应映射器
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000201',
    'Response: Standard JSON Mapper',
    '标准 JSON 响应映射器，支持自定义路径提取，自动上传远程文件到OSS',
    'RESPONSE_MAPPER',
    'ALL',
    '// Standard JSON Response Mapper
// 可用变量: response, config, json, extras, oss, log

// 检查空响应
if (!response) {
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [code: "EMPTY_RESPONSE", message: "API 返回空响应", retryable: true]
    ]
}

// 获取路径配置
def paths = config.responsePaths ?: [:]
def dataPath = paths.data ?: ''$.data''
def urlPath = paths.url ?: ''$.data.url''
def errorPath = paths.error ?: ''$.error''
def statusPath = paths.status ?: ''$.status''

// 检查错误
def error = json.path(response, errorPath)
if (error) {
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [
            code: error.code ?: "API_ERROR",
            message: error.message ?: error.toString(),
            retryable: error.code in ["rate_limit", "timeout"]
        ],
        metadata: [raw: response]
    ]
}

// 提取数据
def fileUrl = json.path(response, urlPath)
def data = json.path(response, dataPath)

// 处理数组响应
def allUrls = []
if (data instanceof List) {
    allUrls = data.collect { item ->
        item instanceof Map ? (item.url ?: item.file_url) : item
    }.findAll { it != null }
}

// 构建响应
def result = [
    status: "SUCCEEDED",
    metadata: [raw: response]
]

if (fileUrl || !allUrls.isEmpty()) {
    def urls = allUrls.isEmpty() ? [fileUrl] : allUrls
    result.outputType = urls.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE"

    // 处理媒体项：下载远程文件并上传到OSS
    def items = urls.collect { url ->
        def item = [mimeType: "image/jpeg", sourceUrl: url]
        if (url && oss != null) {
            try {
                def extension = "jpg"
                if (url.contains(".png")) extension = "png"
                else if (url.contains(".webp")) extension = "webp"
                def ossPath = oss.generatePath("ai-outputs", extension)
                def uploadResult = oss.uploadFromUrlWithKey(url, ossPath, null)
                log.info("文件已上传到OSS: {} -> {}, fileKey={}", url, uploadResult.url, uploadResult.fileKey)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
            } catch (Exception e) {
                log.warn("上传到OSS失败，使用原始URL: {}", e.message)
                item.fileUrl = url
            }
        } else {
            item.fileUrl = url
        }
        return item
    }

    result.media = [
        mediaType: "IMAGE",
        items: items
    ]
} else {
    result.outputType = "MEDIA_SINGLE"
    result.metadata.outputs = data
}

return result',
    '1.0.0',
    true,
    true,
    '{"config": {"responsePaths": {"url": "$.result.image_url"}}, "response": {"result": {"image_url": "https://..."}}}'::jsonb,
    '{"outputType": "MEDIA_SINGLE", "status": "SUCCEEDED", "media": {"mediaType": "IMAGE", "items": [{"fileUrl": "https://oss.example.com/...", "sourceUrl": "https://..."}]}}'::jsonb,
    '## 标准 JSON 响应映射器

### 配置参数
- `responsePaths.data`: 数据路径，默认 $.data
- `responsePaths.url`: URL 路径，默认 $.data.url
- `responsePaths.error`: 错误路径，默认 $.error
- `responsePaths.status`: 状态路径，默认 $.status

### 功能
1. 使用 JSONPath 提取数据
2. 自动处理数组响应
3. 标准化错误格式
4. 支持自定义路径配置
5. **自动下载远程文件并上传到自有OSS**
6. 保留原始URL到 sourceUrl 字段'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 3.2 轮询状态检查器
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000202',
    'Response: Polling Status Checker',
    '通用轮询状态检查响应映射器，自动上传远程文件到OSS',
    'RESPONSE_MAPPER',
    'ALL',
    '// Polling Status Checker
// 可用变量: response, config, json, extras, oss, log

if (!response) {
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]
    ]
}

// 获取配置
def pollingConfig = config.pollingConfig ?: [:]
def statusPath = pollingConfig.statusPath ?: ''$.status''
def successStatus = pollingConfig.successStatus ?: [''succeeded'', ''success'', ''completed'', ''done'']
def failedStatus = pollingConfig.failedStatus ?: [''failed'', ''error'', ''cancelled'']
def pendingStatus = pollingConfig.pendingStatus ?: [''pending'', ''queued'', ''waiting'']
def runningStatus = pollingConfig.runningStatus ?: [''running'', ''processing'', ''in_progress'']

// 提取状态
def status = json.path(response, statusPath)?.toString()?.toLowerCase()

// 确定执行状态
def executionStatus
if (status in successStatus) {
    executionStatus = "SUCCEEDED"
} else if (status in failedStatus) {
    executionStatus = "FAILED"
} else if (status in runningStatus) {
    executionStatus = "RUNNING"
} else {
    executionStatus = "PENDING"
}

// 提取任务ID
def taskId = json.path(response, ''$.task_id'') ?: json.path(response, ''$.id'') ?: extras?.externalTaskId
def runId = json.path(response, ''$.run_id'') ?: extras?.externalRunId

// 非终态直接返回
if (executionStatus in ["PENDING", "RUNNING"]) {
    return [
        outputType: "MEDIA_SINGLE",
        status: executionStatus,
        metadata: [
            externalTaskId: taskId?.toString(),
            externalRunId: runId?.toString(),
            raw: response
        ]
    ]
}

// 失败状态
if (executionStatus == "FAILED") {
    def errorMsg = json.path(response, ''$.error'') ?: json.path(response, ''$.message'') ?: "任务执行失败"
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [code: "TASK_FAILED", message: errorMsg.toString(), retryable: false],
        metadata: [externalTaskId: taskId?.toString(), raw: response]
    ]
}

// 成功状态 - 提取结果
def resultPath = pollingConfig.resultPath ?: ''$.data''
def urlPath = pollingConfig.urlPath ?: ''$.data.url''

def fileUrl = json.path(response, urlPath)
def data = json.path(response, resultPath)

// 处理多文件
def allUrls = []
if (data instanceof List) {
    allUrls = data.collect { it.url ?: it.file_url ?: it }.findAll { it != null && it instanceof String }
}

def result = [
    status: "SUCCEEDED",
    metadata: [externalTaskId: taskId?.toString(), raw: response]
]

if (fileUrl || !allUrls.isEmpty()) {
    def urls = allUrls.isEmpty() ? [fileUrl] : allUrls
    result.outputType = urls.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE"

    // 处理媒体项：下载远程文件并上传到OSS
    def items = urls.collect { url ->
        def item = [mimeType: "image/jpeg", sourceUrl: url]
        if (url && oss != null) {
            try {
                def extension = "jpg"
                if (url.contains(".png")) extension = "png"
                else if (url.contains(".webp")) extension = "webp"
                else if (url.contains(".mp4")) extension = "mp4"
                def ossPath = oss.generatePath("ai-outputs", extension)
                def uploadResult = oss.uploadFromUrlWithKey(url, ossPath, null)
                log.info("文件已上传到OSS: {} -> {}, fileKey={}", url, uploadResult.url, uploadResult.fileKey)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
                if (extension == "mp4") {
                    item.mimeType = "video/mp4"
                }
            } catch (Exception e) {
                log.warn("上传到OSS失败，使用原始URL: {}", e.message)
                item.fileUrl = url
            }
        } else {
            item.fileUrl = url
        }
        return item
    }

    result.media = [
        mediaType: "IMAGE",
        items: items
    ]
} else {
    result.outputType = "MEDIA_SINGLE"
    result.metadata.outputs = data
}

return result',
    '1.0.0',
    true,
    true,
    '{"config": {"pollingConfig": {"statusPath": "$.state", "successStatus": ["done"]}}, "response": {"state": "done", "data": {"url": "https://..."}}}'::jsonb,
    '{"outputType": "MEDIA_SINGLE", "status": "SUCCEEDED", "media": {"mediaType": "IMAGE", "items": [{"fileUrl": "https://oss.example.com/...", "sourceUrl": "https://..."}]}}'::jsonb,
    '## 轮询状态检查器

### 配置参数
- `pollingConfig.statusPath`: 状态字段路径，默认 $.status
- `pollingConfig.successStatus`: 成功状态值列表
- `pollingConfig.failedStatus`: 失败状态值列表
- `pollingConfig.pendingStatus`: 等待状态值列表
- `pollingConfig.runningStatus`: 运行中状态值列表
- `pollingConfig.resultPath`: 结果数据路径
- `pollingConfig.urlPath`: 文件 URL 路径

### 返回状态
- `SUCCEEDED`: 任务成功完成
- `FAILED`: 任务失败
- `RUNNING`: 任务运行中
- `PENDING`: 任务等待中

### 新增功能
- **自动下载远程文件并上传到自有OSS**
- 保留原始URL到 sourceUrl 字段'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 3.3 错误恢复处理器
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000203',
    'Response: Error Handler',
    '增强错误处理，支持自定义错误码映射和重试策略',
    'RESPONSE_MAPPER',
    'ALL',
    '// Enhanced Error Handler
// 可用变量: response, config, json, extras

// 错误码映射配置
def errorMapping = config.errorMapping ?: [
    "rate_limit": [retryable: true, delay: 60000],
    "timeout": [retryable: true, delay: 5000],
    "server_error": [retryable: true, delay: 10000],
    "invalid_request": [retryable: false],
    "authentication_error": [retryable: false],
    "insufficient_quota": [retryable: false]
]

// 检查 HTTP 状态码
def httpStatus = extras?.httpStatus ?: 200
if (httpStatus >= 400) {
    def errorInfo = errorMapping["server_error"] ?: [retryable: httpStatus >= 500]
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [
            code: "HTTP_${httpStatus}",
            message: "HTTP 错误: ${httpStatus}",
            retryable: errorInfo.retryable,
            retryAfterMs: errorInfo.delay
        ],
        metadata: [httpStatus: httpStatus, raw: response]
    ]
}

// 检查业务错误
if (!response) {
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [code: "EMPTY_RESPONSE", message: "响应为空", retryable: true, retryAfterMs: 5000]
    ]
}

// 提取错误信息
def error = response.error ?: (response.code && response.code != 0 ? response : null)
if (error) {
    def errorCode = error.code?.toString()?.toLowerCase() ?: "unknown_error"
    def errorInfo = errorMapping[errorCode] ?: [retryable: false]

    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [
            code: errorCode.toUpperCase(),
            message: error.message ?: error.msg ?: "未知错误",
            retryable: errorInfo.retryable,
            retryAfterMs: errorInfo.delay
        ],
        metadata: [raw: response]
    ]
}

// 无错误，透传响应
return [
    outputType: "MEDIA_SINGLE",
    status: "SUCCEEDED",
    metadata: [raw: response, outputs: response.data ?: response]
]',
    '1.0.0',
    true,
    true,
    '{"config": {"errorMapping": {"rate_limit": {"retryable": true, "delay": 60000}}}}'::jsonb,
    '{"status": "FAILED", "error": {"code": "RATE_LIMIT", "retryable": true, "retryAfterMs": 60000}}'::jsonb,
    '## 增强错误处理器

### 配置参数
- `errorMapping`: 错误码到处理策略的映射
  - `retryable`: 是否可重试
  - `delay`: 重试延迟（毫秒）

### 默认错误处理
- `rate_limit`: 可重试，延迟 60s
- `timeout`: 可重试，延迟 5s
- `server_error`: 可重试，延迟 10s
- `invalid_request`: 不可重试
- `authentication_error`: 不可重试
- `insufficient_quota`: 不可重试

### 功能
1. HTTP 状态码处理（4xx/5xx）
2. 业务错误码映射
3. 重试策略配置
4. 标准化错误输出'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 4. 数据库操作模板
-- =====================================================

-- -----------------------------------------------------
-- 4.1 查询并处理结果
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000301',
    'DB: Query and Process',
    '查询数据库并处理结果，用于动态构建请求',
    'REQUEST_BUILDER',
    'ALL',
    '// Database Query and Process
// 可用变量: inputs, config, db, json

// 获取查询配置
def entityType = config.entityType ?: "character"  // character, scene, style, script, etc.
def entityId = inputs.entityId ?: inputs.entity_id

if (!entityId) {
    throw new IllegalArgumentException("缺少实体 ID")
}

// 查询实体
def entity
switch (entityType) {
    case "character":
        entity = db.getCharacter(entityId)
        break
    case "scene":
        entity = db.getScene(entityId)
        break
    case "style":
        entity = db.getStyle(entityId)
        break
    case "script":
        entity = db.getScript(entityId)
        break
    case "storyboard":
        entity = db.getStoryboard(entityId)
        break
    default:
        throw new IllegalArgumentException("不支持的实体类型: ${entityType}")
}

if (!entity) {
    throw new RuntimeException("实体不存在: ${entityType}/${entityId}")
}

// 构建请求体
def body = [:]

// 合并实体描述
if (entity.description) {
    body.prompt = (inputs.prompt ?: "") + ", " + entity.description
}
if (entity.fixedDesc || entity.fixed_desc) {
    body.prompt = body.prompt + ", " + (entity.fixedDesc ?: entity.fixed_desc)
}

// 复制其他字段
["size", "n", "seed", "style"].each { field ->
    if (inputs[field] != null) {
        body[field] = inputs[field]
    }
}

// 合并实体参数
if (entity.styleParams || entity.style_params) {
    def params = entity.styleParams ?: entity.style_params
    if (params instanceof String) {
        params = json.parseJson(params)
    }
    params.each { k, v -> body[k] = v }
}

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"entityType": "character"}, "inputs": {"entityId": "xxx", "prompt": "portrait"}}'::jsonb,
    '{"prompt": "portrait, 角色描述, fixed_desc", "style_params": {"cfg_scale": 7}}'::jsonb,
    '## 数据库查询处理模板

### 配置参数
- `entityType`: 实体类型 (character/scene/style/script/storyboard)

### 输入参数
- `entityId`: 实体 ID（必需）
- `prompt`: 基础提示词
- 其他生成参数

### 功能
1. 查询指定类型实体
2. 合并实体描述到提示词
3. 合并实体参数到请求
4. 支持多种实体类型'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 4.2 保存生成结果
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000302',
    'DB: Save Generation Result',
    '将生成结果保存到数据库（作为资产或更新实体）',
    'RESPONSE_MAPPER',
    'ALL',
    '// Save Generation Result to Database
// 可用变量: response, config, db, oss, json, extras

// 先解析响应
def data = response.data ?: response
def fileUrl = data.url ?: data.file_url ?: data.image_url

if (!fileUrl) {
    return [
        outputType: "MEDIA_SINGLE",
        status: "FAILED",
        error: [code: "NO_OUTPUT", message: "响应中没有文件 URL"]
    ]
}

// 获取保存配置
def saveConfig = config.saveConfig ?: [:]
def saveAsAsset = saveConfig.saveAsAsset != false
def updateEntity = saveConfig.updateEntity ?: false
def entityType = saveConfig.entityType
def entityId = extras?.entityId ?: saveConfig.entityId

// 保存为资产
def assetId = null
if (saveAsAsset) {
    try {
        // 下载并重新上传到自己的 OSS
        def fileName = oss.generatePath("ai-outputs", "png")
        def ossUrl = oss.uploadFromUrl(fileUrl, fileName)

        // 创建资产记录
        assetId = db.createAsset([
            name: extras?.assetName ?: "AI 生成图片",
            type: "IMAGE",
            url: ossUrl,
            sourceUrl: fileUrl,
            generationId: extras?.executionId
        ])

        log.info("资产已保存: {}", assetId)
    } catch (Exception e) {
        log.error("保存资产失败: {}", e.message)
    }
}

// 更新实体
if (updateEntity && entityType && entityId) {
    try {
        def updateData = [:]
        if (entityType == "character") {
            updateData.avatarUrl = fileUrl
        } else if (entityType == "storyboard") {
            updateData.imageUrl = fileUrl
        }

        db.updateEntity(entityType, entityId, updateData)
        log.info("实体已更新: {}/{}", entityType, entityId)
    } catch (Exception e) {
        log.error("更新实体失败: {}", e.message)
    }
}

// 返回结果
return [
    outputType: "MEDIA_SINGLE",
    status: "SUCCEEDED",
    media: [
        mediaType: "IMAGE",
        items: [[fileUrl: fileUrl, mimeType: "image/png"]]
    ],
    metadata: [
        assetId: assetId,
        entityUpdated: updateEntity,
        raw: response
    ]
]',
    '1.0.0',
    true,
    true,
    '{"config": {"saveConfig": {"saveAsAsset": true, "updateEntity": true, "entityType": "character"}}}'::jsonb,
    '{"status": "SUCCEEDED", "metadata": {"assetId": "xxx", "entityUpdated": true}}'::jsonb,
    '## 保存生成结果模板

### 配置参数
- `saveConfig.saveAsAsset`: 是否保存为资产，默认 true
- `saveConfig.updateEntity`: 是否更新关联实体，默认 false
- `saveConfig.entityType`: 要更新的实体类型
- `saveConfig.entityId`: 要更新的实体 ID

### 功能
1. 下载远程文件并上传到自有 OSS
2. 创建资产记录
3. 更新关联实体的图片字段
4. 返回标准响应格式'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 5. 资产处理模板
-- =====================================================

-- -----------------------------------------------------
-- 5.1 图片预处理通用模板
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000401',
    'Asset: Image Preprocessing',
    '通用图片预处理模板，支持多种处理操作',
    'CUSTOM_LOGIC',
    'IMAGE',
    '// Image Preprocessing Template
// 可用变量: inputs, config, asset, log

def imageUrl = inputs.image_url ?: inputs.imageUrl ?: inputs.image
if (!imageUrl) {
    throw new IllegalArgumentException("缺少图片 URL")
}

// 获取处理配置
def processConfig = config.imageProcessing ?: [:]
def operations = processConfig.operations ?: ["resize"]  // resize, compress, crop, square, convert

// 下载图片
def imageBytes = asset.downloadImage(imageUrl)
def originalInfo = asset.getImageInfo(imageBytes)
log.info("原始图片: {}x{}, {}KB", originalInfo.width, originalInfo.height, originalInfo.sizeBytes / 1024)

// 执行处理操作
operations.each { op ->
    switch (op) {
        case "resize":
            def maxW = processConfig.maxWidth ?: 1024
            def maxH = processConfig.maxHeight ?: 1024
            if (originalInfo.width > maxW || originalInfo.height > maxH) {
                imageBytes = asset.resizeImageKeepRatio(imageBytes, maxW, maxH, "png")
                log.info("调整大小完成")
            }
            break

        case "compress":
            def quality = processConfig.quality ?: 0.85
            imageBytes = asset.compressImage(imageBytes, quality as float, "jpg")
            log.info("压缩完成: {}KB", imageBytes.length / 1024)
            break

        case "crop":
            def x = processConfig.cropX ?: 0
            def y = processConfig.cropY ?: 0
            def w = processConfig.cropWidth ?: originalInfo.width
            def h = processConfig.cropHeight ?: originalInfo.height
            imageBytes = asset.cropImage(imageBytes, x, y, w, h, "png")
            log.info("裁剪完成")
            break

        case "square":
            def size = processConfig.squareSize ?: 512
            imageBytes = asset.cropSquare(imageBytes, size, "png")
            log.info("正方形裁剪完成: {}x{}", size, size)
            break

        case "convert":
            def format = processConfig.format ?: "jpg"
            imageBytes = asset.convertFormat(imageBytes, format)
            log.info("格式转换完成: {}", format)
            break
    }
}

// 返回处理结果
return [
    processedImage: asset.toBase64(imageBytes),
    mimeType: asset.detectMimeType(imageBytes),
    sizeBytes: imageBytes.length,
    operations: operations
]',
    '1.0.0',
    true,
    true,
    '{"config": {"imageProcessing": {"operations": ["resize", "compress"], "maxWidth": 512, "quality": 0.8}}}'::jsonb,
    '{"processedImage": "base64...", "mimeType": "image/jpeg", "sizeBytes": 12345}'::jsonb,
    '## 图片预处理模板

### 配置参数
- `imageProcessing.operations`: 处理操作列表
  - `resize`: 调整大小（保持比例）
  - `compress`: 压缩
  - `crop`: 裁剪
  - `square`: 居中裁剪为正方形
  - `convert`: 格式转换

### 调整大小参数
- `maxWidth`: 最大宽度
- `maxHeight`: 最大高度

### 压缩参数
- `quality`: 压缩质量 (0-1)

### 裁剪参数
- `cropX/Y`: 起始坐标
- `cropWidth/Height`: 裁剪尺寸

### 正方形参数
- `squareSize`: 正方形边长

### 格式转换
- `format`: 目标格式 (jpg/png/webp)'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 5.2 文件上传处理模板
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000402',
    'Asset: File Upload Handler',
    '处理文件上传，支持从 URL 或 Base64 上传',
    'CUSTOM_LOGIC',
    'ALL',
    '// File Upload Handler
// 可用变量: inputs, config, oss, asset, log

def sourceType = inputs.sourceType ?: "url"  // url, base64, bytes
def source = inputs.source ?: inputs.url ?: inputs.base64
def fileName = inputs.fileName ?: oss.generateFileName("bin")

if (!source) {
    throw new IllegalArgumentException("缺少文件来源")
}

def fileBytes
def mimeType

// 获取文件数据
switch (sourceType) {
    case "url":
        fileBytes = asset.downloadBytes(source)
        mimeType = asset.detectMimeType(fileBytes)
        break

    case "base64":
        fileBytes = asset.fromBase64(source)
        mimeType = asset.detectMimeType(fileBytes)
        break

    case "bytes":
        fileBytes = source
        mimeType = asset.detectMimeType(fileBytes)
        break

    default:
        throw new IllegalArgumentException("不支持的来源类型: ${sourceType}")
}

log.info("文件大小: {}KB, 类型: {}", fileBytes.length / 1024, mimeType)

// 获取目录配置
def directory = config.uploadDirectory ?: "uploads"
def fullPath = "${directory}/${fileName}"

// 上传到 OSS
def ossUrl = oss.uploadBytes(fileBytes, fullPath, mimeType)

log.info("文件已上传: {}", ossUrl)

return [
    url: ossUrl,
    fileName: fileName,
    mimeType: mimeType,
    sizeBytes: fileBytes.length
]',
    '1.0.0',
    true,
    true,
    '{"inputs": {"sourceType": "url", "source": "https://example.com/file.png"}, "config": {"uploadDirectory": "images"}}'::jsonb,
    '{"url": "https://oss.example.com/images/xxx.png", "fileName": "xxx.png", "mimeType": "image/png", "sizeBytes": 12345}'::jsonb,
    '## 文件上传处理模板

### 输入参数
- `sourceType`: 来源类型 (url/base64/bytes)
- `source` / `url` / `base64`: 文件来源
- `fileName`: 文件名（可选，自动生成）

### 配置参数
- `uploadDirectory`: 上传目录，默认 "uploads"

### 功能
1. 支持从 URL 下载上传
2. 支持 Base64 解码上传
3. 自动检测 MIME 类型
4. 返回 OSS URL 和元信息'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 5.3 多媒体格式检测模板
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000403',
    'Asset: Media Format Detector',
    '检测多媒体文件格式和基本信息',
    'CUSTOM_LOGIC',
    'ALL',
    '// Media Format Detector
// 可用变量: inputs, config, asset, log

def url = inputs.url ?: inputs.file_url
if (!url) {
    throw new IllegalArgumentException("缺少文件 URL")
}

// 下载文件
def fileBytes = asset.downloadBytes(url)
def mimeType = asset.detectMimeType(fileBytes)

// 基本信息
def info = [
    url: url,
    mimeType: mimeType,
    sizeBytes: fileBytes.length,
    sizeMB: String.format("%.2f", fileBytes.length / (1024.0 * 1024.0))
]

// 根据类型获取详细信息
if (mimeType.startsWith("image/")) {
    def imageInfo = asset.getImageInfo(fileBytes)
    info.mediaType = "IMAGE"
    info.width = imageInfo.width
    info.height = imageInfo.height
    info.aspectRatio = String.format("%.2f", imageInfo.width / (imageInfo.height as double))
    info.hasAlpha = imageInfo.hasAlpha
} else if (mimeType.startsWith("video/")) {
    def videoInfo = asset.getVideoInfo(fileBytes)
    info.mediaType = "VIDEO"
    info.format = videoInfo.format
} else if (mimeType.startsWith("audio/")) {
    info.mediaType = "AUDIO"
} else if (mimeType == "application/pdf") {
    info.mediaType = "DOCUMENT"
    info.format = "PDF"
} else {
    info.mediaType = "OTHER"
}

log.info("媒体信息: {} - {}x{} - {}MB",
    info.mediaType, info.width ?: "N/A", info.height ?: "N/A", info.sizeMB)

return info',
    '1.0.0',
    true,
    true,
    '{"inputs": {"url": "https://example.com/image.png"}}'::jsonb,
    '{"mediaType": "IMAGE", "mimeType": "image/png", "width": 1024, "height": 768, "sizeBytes": 123456}'::jsonb,
    '## 多媒体格式检测模板

### 输入参数
- `url` / `file_url`: 文件 URL

### 返回信息
- `mediaType`: 媒体类型 (IMAGE/VIDEO/AUDIO/DOCUMENT/OTHER)
- `mimeType`: MIME 类型
- `sizeBytes`: 文件大小（字节）
- `sizeMB`: 文件大小（MB）
- `width/height`: 图片尺寸（仅图片）
- `aspectRatio`: 宽高比（仅图片）
- `format`: 格式（视频/文档）'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 6. 组合模板（完整流程示例）
-- =====================================================

-- -----------------------------------------------------
-- 6.1 完整图生图流程
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000501',
    'Flow: Complete Image-to-Image',
    '完整的图生图流程：预处理 → API 调用 → 结果保存',
    'REQUEST_BUILDER',
    'IMAGE',
    '// Complete Image-to-Image Flow
// 可用变量: inputs, config, headers, asset, oss, log

headers["Content-Type"] = "application/json"

// ========== 1. 图片预处理 ==========
def imageUrl = inputs.image_url ?: inputs.imageUrl ?: inputs.image
if (!imageUrl) {
    throw new IllegalArgumentException("缺少输入图片")
}

log.info("开始处理图片: {}", imageUrl)

// 下载并预处理
def imageBytes = asset.downloadImage(imageUrl)
def originalInfo = asset.getImageInfo(imageBytes)
log.info("原始尺寸: {}x{}", originalInfo.width, originalInfo.height)

// 获取处理配置
def maxSize = config.maxImageSize ?: 1024
def quality = config.imageQuality ?: 0.9

// 调整大小
if (originalInfo.width > maxSize || originalInfo.height > maxSize) {
    imageBytes = asset.resizeImageKeepRatio(imageBytes, maxSize, maxSize, "jpg")
    log.info("调整后大小: {}KB", imageBytes.length / 1024)
}

// 压缩
imageBytes = asset.compressImage(imageBytes, quality as float, "jpg")
log.info("压缩后大小: {}KB", imageBytes.length / 1024)

// ========== 2. 上传到临时存储（可选）==========
def tempUrl = null
if (config.uploadBeforeCall) {
    def tempPath = oss.generatePath("temp", "jpg")
    tempUrl = oss.uploadBytes(imageBytes, tempPath, "image/jpeg")
    log.info("临时文件: {}", tempUrl)
}

// ========== 3. 构建请求体 ==========
def body = [
    prompt: inputs.prompt,
    image: tempUrl ?: asset.toBase64(imageBytes)
]

// 可选参数
["negative_prompt", "strength", "seed", "guidance_scale", "steps"].each { field ->
    if (inputs[field] != null) {
        body[field] = inputs[field]
    }
}

// 模型配置
if (config.model) {
    body.model = config.model
}

// 默认值
body.strength = body.strength ?: config.defaultStrength ?: 0.75

log.info("请求构建完成, model={}, prompt={}", body.model, body.prompt?.take(50))

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"model": "sd-v1.5", "maxImageSize": 512, "imageQuality": 0.85}, "inputs": {"image_url": "https://...", "prompt": "oil painting style"}}'::jsonb,
    '{"model": "sd-v1.5", "prompt": "oil painting style", "image": "base64...", "strength": 0.75}'::jsonb,
    '## 完整图生图流程

### 配置参数
- `model`: 模型名称
- `maxImageSize`: 最大图片尺寸，默认 1024
- `imageQuality`: 压缩质量，默认 0.9
- `uploadBeforeCall`: 是否先上传到 OSS
- `defaultStrength`: 默认变换强度，默认 0.75

### 输入参数
- `image_url`: 输入图片 URL（必需）
- `prompt`: 提示词（必需）
- `negative_prompt`: 负向提示词
- `strength`: 变换强度 (0-1)
- `seed`: 随机种子
- `guidance_scale`: 引导系数
- `steps`: 采样步数

### 处理流程
1. 下载输入图片
2. 调整大小（保持比例）
3. 压缩优化
4. 可选上传到 OSS
5. 构建 API 请求体'
)
ON CONFLICT (id) DO NOTHING;

-- -----------------------------------------------------
-- 6.2 完整视频生成流程（提交 + 轮询）
-- -----------------------------------------------------
INSERT INTO t_groovy_template (
    id, name, description, template_type, generation_type,
    script_content, script_version, is_system, enabled,
    example_input, example_output, documentation
) VALUES (
    '00000000-0000-0000-0002-000000000502',
    'Flow: Video Generation Submit',
    '视频生成任务提交，包含图片预处理',
    'REQUEST_BUILDER',
    'VIDEO',
    '// Video Generation Submit Flow
// 可用变量: inputs, config, headers, asset, log

headers["Content-Type"] = "application/json"

// ========== 图片预处理（如果有）==========
def firstFrame = null
def imageUrl = inputs.image_url ?: inputs.imageUrl ?: inputs.first_frame

if (imageUrl) {
    log.info("处理首帧图片: {}", imageUrl)

    def imageBytes = asset.downloadImage(imageUrl)
    def info = asset.getImageInfo(imageBytes)

    // 视频通常需要特定比例
    def targetWidth = config.videoWidth ?: 1280
    def targetHeight = config.videoHeight ?: 720

    // 调整大小
    imageBytes = asset.resizeImageKeepRatio(imageBytes, targetWidth, targetHeight, "jpg")

    // 压缩
    imageBytes = asset.compressImage(imageBytes, 0.9f, "jpg")

    firstFrame = asset.toBase64(imageBytes)
    log.info("首帧处理完成: {}KB", imageBytes.length / 1024)
}

// ========== 构建请求 ==========
def body = [
    prompt: inputs.prompt
]

if (firstFrame) {
    body.first_frame = firstFrame
}

// 视频参数
["duration", "fps", "resolution", "style", "seed"].each { field ->
    if (inputs[field] != null) {
        body[field] = inputs[field]
    }
}

// 模型和默认值
body.model = config.model ?: "video-gen-v1"
body.duration = body.duration ?: config.defaultDuration ?: 4
body.fps = body.fps ?: config.defaultFps ?: 24

log.info("视频生成请求: model={}, duration={}s, fps={}", body.model, body.duration, body.fps)

return body',
    '1.0.0',
    true,
    true,
    '{"config": {"model": "video-gen-v1", "videoWidth": 1920, "videoHeight": 1080}, "inputs": {"prompt": "a cat walking", "image_url": "https://..."}}'::jsonb,
    '{"model": "video-gen-v1", "prompt": "a cat walking", "first_frame": "base64...", "duration": 4, "fps": 24}'::jsonb,
    '## 视频生成提交流程

### 配置参数
- `model`: 模型名称
- `videoWidth/Height`: 视频分辨率
- `defaultDuration`: 默认时长（秒）
- `defaultFps`: 默认帧率

### 输入参数
- `prompt`: 提示词（必需）
- `image_url`: 首帧图片 URL
- `duration`: 视频时长
- `fps`: 帧率
- `resolution`: 分辨率
- `style`: 风格
- `seed`: 随机种子

### 处理流程
1. 预处理首帧图片（如果有）
2. 调整尺寸适配视频比例
3. 构建视频生成请求'
)
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE 'Groovy templates inserted successfully!';
    RAISE NOTICE '';
    RAISE NOTICE '*** OSS Auto-Upload Feature ***';
    RAISE NOTICE 'Response mapper templates (Standard JSON Mapper, Polling Status Checker)';
    RAISE NOTICE 'now automatically download remote URLs and upload to self-hosted OSS.';
    RAISE NOTICE 'Original URLs are preserved in sourceUrl field for reference.';
    RAISE NOTICE 'This ensures data sovereignty and avoids URL expiration issues.';
END $$;

-- =====================================================
-- 4. 模型提供商初始化
-- =====================================================

-- =====================================================

-- =====================================================
-- Model Provider 初始化数据
-- 包含图片生成和视频生成模型配置
-- =====================================================

INSERT INTO t_model_provider (
    id, name, description, plugin_id, plugin_type, provider_type,
    base_url, endpoint, http_method, auth_type, auth_config,
    api_key_ref, base_url_ref,
    supported_modes, callback_config, polling_config,
    credit_cost, rate_limit, timeout, max_retries,
    icon_url, priority, enabled, custom_headers,
    created_by, updated_by, deleted, version
) VALUES
-- =====================================================
-- 1. Seedream 4.5 - 最新图片生成模型
-- =====================================================
('00000000-0000-0000-0003-000000000001', 'Seedream 4.5', 'Seedream 4.5 图片生成模型，支持文生图、单图生图', 'seedream-4-5', 'GROOVY', 'IMAGE',
 'https://ark.cn-beijing.volces.com', '/api/v3/images/generations', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["BLOCKING"]'::jsonb, '{}', '{}',
 3, 60, 120000, 3,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 100, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 2. Seedream 4.0 - 图片生成模型
-- =====================================================
('019c383d-bc4b-fa0a-8217-6ee9713f03e3', 'Seedream 4.0', 'Seedream 4.0 图片生成模型，支持文生图、单图生图', 'seedream-4-0', 'GROOVY', 'IMAGE',
 'https://ark.cn-beijing.volces.com', '/api/v3/images/generations', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["BLOCKING"]'::jsonb, '{}', '{}',
 2, 60, 120000, 3,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 90, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 3. Nano Banana Pro - Gemini 图片生成
-- =====================================================
('00000000-0000-0000-0003-000000000012', 'Nano Banana Pro', 'Nano Banana Pro，专业级图片生成编辑，支持复杂指令和高保真文字渲染', 'nano-banana-pro', 'GROOVY', 'IMAGE',
 'https://jp.duckcoding.com', '/v1beta/models/gemini-3-pro-image-preview:generateContent', 'POST', 'API_KEY', '{"apiKey": ""}',
 NULL, NULL,
 '["BLOCKING"]'::jsonb, '{}', '{}',
 3, 30, 180000, 3,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/gemini.svg', 110, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 4. Seedance 1.5 Pro - 视频生成模型
-- =====================================================
('019c399a-cf8a-7106-bece-9ff2291cddc3', 'Seedance 1.5 Pro', 'Seedance 1.5 Pro 视频生成模型，支持文生视频、图生视频（首帧/首尾帧），支持音频生成', 'seedance-1-5-pro', 'GROOVY', 'VIDEO',
 'https://ark.cn-beijing.volces.com', '/api/v3/contents/generations/tasks', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["POLLING"]'::jsonb, '{}', '{"urlPath": "$.content.video_url", "resultPath": "$.content", "statusPath": "$.status", "taskIdPath": "$.id", "failedStatus": ["failed", "cancelled", "expired"], "pendingStatus": ["queued"], "pollingMethod": "GET", "runningStatus": ["running"], "successStatus": ["succeeded"], "maxPollingTime": 600000, "pollingEndpoint": "/api/v3/contents/generations/tasks/{taskId}", "pollingInterval": 5000}',
 20, 15, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 85, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 5. Seedance 1.0 Pro - 视频生成模型
-- =====================================================
('00000000-0000-0000-0003-000000000022', 'Seedance 1.0 Pro', 'Seedance 1.0 Pro 视频生成模型，支持文生视频、图生视频（首帧/首尾帧）', 'seedance-1-0-pro', 'GROOVY', 'VIDEO',
 'https://ark.cn-beijing.volces.com', '/api/v3/contents/generations/tasks', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["POLLING"]'::jsonb, '{}', '{"urlPath": "$.content.video_url", "resultPath": "$.content", "statusPath": "$.status", "taskIdPath": "$.id", "failedStatus": ["failed", "cancelled", "expired"], "pendingStatus": ["queued"], "pollingMethod": "GET", "runningStatus": ["running"], "successStatus": ["succeeded"], "maxPollingTime": 600000, "pollingEndpoint": "/api/v3/contents/generations/tasks/{taskId}", "pollingInterval": 5000}',
 20, 15, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 85, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6. Seedance 1.0 Pro Fast - 快速视频生成
-- =====================================================
('019c399f-72f2-f80e-9e49-a3763928fd1e', 'Seedance 1.0 Pro Fast', 'Seedance 1.0 Pro Fast 视频生成模型，快速版', 'seedance-1-0-pro-fast', 'GROOVY', 'VIDEO',
 'https://ark.cn-beijing.volces.com', '/api/v3/contents/generations/tasks', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["POLLING"]'::jsonb, '{}', '{"urlPath": "$.content.video_url", "resultPath": "$.content", "statusPath": "$.status", "taskIdPath": "$.id", "failedStatus": ["failed", "cancelled", "expired"], "pendingStatus": ["queued"], "pollingMethod": "GET", "runningStatus": ["running"], "successStatus": ["succeeded"], "maxPollingTime": 600000, "pollingEndpoint": "/api/v3/contents/generations/tasks/{taskId}", "pollingInterval": 5000}',
 10, 15, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 80, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6.5 Seedance 2.0 - 多模态视频生成（支持参考图/视频/音频）
-- =====================================================
('00000000-0000-0000-0003-000000000040', 'Seedance 2.0', 'Seedance 2.0 视频生成模型，支持多模态参考生视频（图片+视频+音频）、首帧/首尾帧、文生视频、有声视频', 'seedance-2-0', 'GROOVY', 'VIDEO',
 'https://ark.cn-beijing.volces.com', '/api/v3/contents/generations/tasks', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["POLLING"]'::jsonb, '{}', '{"urlPath": "$.content.video_url", "resultPath": "$.content", "statusPath": "$.status", "taskIdPath": "$.id", "failedStatus": ["failed", "cancelled", "expired"], "pendingStatus": ["queued"], "pollingMethod": "GET", "runningStatus": ["running"], "successStatus": ["succeeded"], "maxPollingTime": 600000, "pollingEndpoint": "/api/v3/contents/generations/tasks/{taskId}", "pollingInterval": 5000}',
 25, 15, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 95, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6.6 Seedance 2.0 Fast - 多模态视频生成（快速版）
-- =====================================================
('00000000-0000-0000-0003-000000000041', 'Seedance 2.0 Fast', 'Seedance 2.0 Fast 快速视频生成模型，支持多模态参考生视频、首帧/首尾帧、文生视频、有声视频', 'seedance-2-0-fast', 'GROOVY', 'VIDEO',
 'https://ark.cn-beijing.volces.com', '/api/v3/contents/generations/tasks', 'POST', 'API_KEY', '{"headerName": "Authorization"}',
 'ai.provider.volcengine.api_key', 'ai.provider.volcengine.base_url',
 '["POLLING"]'::jsonb, '{}', '{"urlPath": "$.content.video_url", "resultPath": "$.content", "statusPath": "$.status", "taskIdPath": "$.id", "failedStatus": ["failed", "cancelled", "expired"], "pendingStatus": ["queued"], "pollingMethod": "GET", "runningStatus": ["running"], "successStatus": ["succeeded"], "maxPollingTime": 600000, "pollingEndpoint": "/api/v3/contents/generations/tasks/{taskId}", "pollingInterval": 5000}',
 15, 15, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/doubao.png', 90, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6.7 MiniMax T2A - 文本转语音
-- =====================================================
('00000000-0000-0000-0003-000000000050', 'MiniMax T2A', 'MiniMax 文本转语音（speech-2.8-hd），支持多语言、情感控制、语音变声', 'minimax-t2a', 'GROOVY', 'AUDIO',
 'https://api.minimax.io', '/v1/t2a_v2', 'POST', 'API_KEY', '{"headerName": "Authorization", "apiKeyPrefix": "Bearer"}',
 'ai.provider.minimax.api_key', 'ai.provider.minimax.base_url',
 '["BLOCKING"]'::jsonb, '{}', '{}',
 2, 30, 60000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/minimax.png', 85, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6.8 MiniMax Voice Clone - 语音克隆
-- =====================================================
('00000000-0000-0000-0003-000000000051', 'MiniMax Voice Clone', 'MiniMax 快速语音克隆，上传音频克隆声音并生成预览', 'minimax-voice-clone', 'GROOVY', 'AUDIO',
 'https://api.minimax.io', '/v1/voice_clone', 'POST', 'API_KEY', '{"headerName": "Authorization", "apiKeyPrefix": "Bearer"}',
 'ai.provider.minimax.api_key', 'ai.provider.minimax.base_url',
 '["BLOCKING"]'::jsonb, '{}', '{}',
 5, 15, 120000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/minimax.png', 80, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 6.9 MiniMax Music Generation - 音乐生成
-- =====================================================
('00000000-0000-0000-0003-000000000052', 'MiniMax Music Gen', 'MiniMax 音乐生成（music-2.6），支持文本+歌词生成完整歌曲，支持翻唱、纯音乐', 'minimax-music', 'GROOVY', 'AUDIO',
 'https://api.minimax.io', '/v1/music_generation', 'POST', 'API_KEY', '{"headerName": "Authorization", "apiKeyPrefix": "Bearer"}',
 'ai.provider.minimax.api_key', 'ai.provider.minimax.base_url',
 '["BLOCKING"]'::jsonb, '{}', '{}',
 10, 10, 120000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/minimax.png', 80, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 7. Midjourney V7 - RunningHub 图片生成
-- =====================================================
('00000000-0000-0000-0003-000000000031', 'Midjourney V7', 'Midjourney V7 图片生成模型（RunningHub API），支持文生图、图生图、风格参考、对象参考', 'midjourney-v7', 'GROOVY', 'IMAGE',
 'https://www.runninghub.ai', '/openapi/v2/youchuan/text-to-image-v7', 'POST', 'API_KEY', '{"apiKey": "YOUR_RUNNINGHUB_API_KEY", "apiKeyHeader": "Authorization", "apiKeyPrefix": "Bearer"}',
 NULL, NULL,
 '["POLLING"]'::jsonb, '{}', '{"endpoint": "/openapi/v2/query", "httpMethod": "POST", "requestBodyTemplate": {"taskId": "{taskId}"}, "intervalMs": 5000, "maxAttempts": 120, "statusPath": "$.status", "resultPath": "$.results", "successStatuses": ["SUCCESS"], "failedStatuses": ["FAILED"]}',
 15, 10, 300000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/midjourney.svg', 90, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 8. Midjourney Niji 7 - RunningHub 动漫风格图片生成
-- =====================================================
('00000000-0000-0000-0003-000000000032', 'Midjourney Niji 7', 'Midjourney Niji 7 动漫风格图片生成模型（RunningHub API），专为动漫、插画、二次元风格优化', 'midjourney-niji7', 'GROOVY', 'IMAGE',
 'https://www.runninghub.ai', '/openapi/v2/youchuan/text-to-image-niji7', 'POST', 'API_KEY', '{"apiKey": "YOUR_RUNNINGHUB_API_KEY", "apiKeyHeader": "Authorization", "apiKeyPrefix": "Bearer"}',
 NULL, NULL,
 '["POLLING"]'::jsonb, '{}', '{"endpoint": "/openapi/v2/query", "httpMethod": "POST", "requestBodyTemplate": {"taskId": "{taskId}"}, "intervalMs": 5000, "maxAttempts": 120, "statusPath": "$.status", "resultPath": "$.results", "successStatuses": ["SUCCESS"], "failedStatuses": ["FAILED"]}',
 15, 10, 300000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/midjourney.svg', 90, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- 9. Vidu Q2 Pro - RunningHub 参考生视频模型
-- =====================================================
('00000000-0000-0000-0003-000000000033', 'Vidu Q2 Pro', 'Vidu Q2 Pro 参考生视频模型（RunningHub API），支持参考图片/视频 + 文本提示词生成视频', 'vidu-q2-pro', 'GROOVY', 'VIDEO',
 'https://www.runninghub.ai', '/openapi/v2/vidu/reference-to-video-q2-pro', 'POST', 'API_KEY', '{"apiKey": "YOUR_RUNNINGHUB_API_KEY", "apiKeyHeader": "Authorization", "apiKeyPrefix": "Bearer"}',
 NULL, NULL,
 '["POLLING"]'::jsonb, '{}', '{"endpoint": "/openapi/v2/query", "httpMethod": "POST", "requestBodyTemplate": {"taskId": "{taskId}"}, "intervalMs": 5000, "maxAttempts": 120, "statusPath": "$.status", "resultPath": "$.results", "successStatuses": ["SUCCESS"], "failedStatuses": ["FAILED"]}',
 20, 10, 600000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/vidu.png', 85, TRUE, '{"Content-Type": "application/json"}',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    plugin_id = EXCLUDED.plugin_id,
    plugin_type = EXCLUDED.plugin_type,
    provider_type = EXCLUDED.provider_type,
    base_url = EXCLUDED.base_url,
    endpoint = EXCLUDED.endpoint,
    http_method = EXCLUDED.http_method,
    auth_type = EXCLUDED.auth_type,
    auth_config = EXCLUDED.auth_config,
    api_key_ref = EXCLUDED.api_key_ref,
    base_url_ref = EXCLUDED.base_url_ref,
    supported_modes = EXCLUDED.supported_modes,
    callback_config = EXCLUDED.callback_config,
    polling_config = EXCLUDED.polling_config,
    credit_cost = EXCLUDED.credit_cost,
    rate_limit = EXCLUDED.rate_limit,
    timeout = EXCLUDED.timeout,
    max_retries = EXCLUDED.max_retries,
    icon_url = EXCLUDED.icon_url,
    priority = EXCLUDED.priority,
    enabled = EXCLUDED.enabled,
    custom_headers = EXCLUDED.custom_headers,
    updated_at = NOW();

INSERT INTO t_model_provider_script (
    id, provider_id,
    request_builder_script, response_mapper_script, custom_logic_script,
    pricing_rules, pricing_script,
    created_by, updated_by, deleted, version
) VALUES

('00000000-0000-0000-1003-000000000001', '00000000-0000-0000-0003-000000000001',
$REQUEST$
// 火山引擎 Seedream 4.5 请求构建（Schema 驱动 + 特殊处理 size 拼接）
def body = req.buildBody([model: "doubao-seedream-4-5-251128", watermark: false])

// Seedream 需要将 width/height 合并为 "WxH" 格式的 size 字段
if (inputs?.width != null && inputs?.height != null) {
    def w = inputs.width.toString().trim()
    def h = inputs.height.toString().trim()
    if (w.isNumber() && h.isNumber() && Double.parseDouble(w) > 0 && Double.parseDouble(h) > 0) {
        body.size = "${w}x${h}" as String
    }
    body.remove("width"); body.remove("height")
}
return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedream 响应映射（使用 resp 辅助工具）
def error = resp.checkError(response)
if (error) return error

def images = response.data ?: []
if (images.isEmpty()) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_OUTPUT", message: "未生成任何图片"], metadata: [raw: response]]
}

def items = images.collect { img ->
    def item = [mimeType: "image/jpeg"]
    if (img.url) {
        def ossResult = resp.uploadToOss(img.url, "IMAGE")
        item.fileUrl = ossResult.url; item.fileKey = ossResult.fileKey
    } else if (img.b64_json && oss != null) {
        try {
            def ossPath = oss.generatePath("images", "png")
            def uploadResult = oss.uploadBase64WithKey(img.b64_json, ossPath, "image/png")
            item.fileUrl = uploadResult.url; item.fileKey = uploadResult.fileKey; item.mimeType = "image/png"
        } catch (Exception e) { item.base64Data = img.b64_json }
    } else if (img.b64_json) { item.base64Data = img.b64_json }
    if (img.size) item.size = img.size
    return item
}.findAll { it.fileUrl || it.base64Data }

return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "IMAGE", items: items], metadata: [model: response.model, created: response.created, usage: response.usage, raw: response]]
$RESPONSE$,
'',
 '{"baseCredits": 3, "minCredits": 1, "maxCredits": 50, "roundMode": "CEILING", "rules": [{"param": "size", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"2K": 1.0, "4K": 2.0, "2048x2048": 1.0, "2304x1728": 1.0, "1728x2304": 1.0, "2560x1440": 1.0, "1440x2560": 1.0}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c383d-bc4b-fa0a-1217-6ee9713f03e3', '019c383d-bc4b-fa0a-8217-6ee9713f03e3',
$REQUEST$
// 火山引擎 Seedream 4.0 请求构建（Schema 驱动 + 特殊处理 size 拼接）
def body = req.buildBody([model: "doubao-seedream-4-0-250828", watermark: false])

// Seedream 需要将 width/height 合并为 "WxH" 格式的 size 字段
if (inputs?.width != null && inputs?.height != null) {
    def w = inputs.width.toString().trim()
    def h = inputs.height.toString().trim()
    if (w.isNumber() && h.isNumber() && Double.parseDouble(w) > 0 && Double.parseDouble(h) > 0) {
        body.size = "${w}x${h}" as String
    }
    body.remove("width"); body.remove("height")
}
return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedream 响应映射（使用 resp 辅助工具）
def error = resp.checkError(response)
if (error) return error

def images = response.data ?: []
if (images.isEmpty()) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_OUTPUT", message: "未生成任何图片"], metadata: [raw: response]]
}

def items = images.collect { img ->
    def item = [mimeType: "image/jpeg"]
    if (img.url) {
        def ossResult = resp.uploadToOss(img.url, "IMAGE")
        item.fileUrl = ossResult.url; item.fileKey = ossResult.fileKey
    } else if (img.b64_json && oss != null) {
        try {
            def ossPath = oss.generatePath("images", "png")
            def uploadResult = oss.uploadBase64WithKey(img.b64_json, ossPath, "image/png")
            item.fileUrl = uploadResult.url; item.fileKey = uploadResult.fileKey; item.mimeType = "image/png"
        } catch (Exception e) { item.base64Data = img.b64_json }
    } else if (img.b64_json) { item.base64Data = img.b64_json }
    if (img.size) item.size = img.size
    return item
}.findAll { it.fileUrl || it.base64Data }

return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "IMAGE", items: items], metadata: [model: response.model, created: response.created, usage: response.usage, raw: response]]
$RESPONSE$,
'',
 '{"baseCredits": 2, "minCredits": 1, "maxCredits": 50, "roundMode": "CEILING", "rules": [{"param": "size", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"2K": 1.0, "4K": 2.0, "2048x2048": 1.0, "2304x1728": 1.0, "1728x2304": 1.0, "2560x1440": 1.0, "1440x2560": 1.0}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-1003-000000000012', '00000000-0000-0000-0003-000000000012',
$REQUEST$
// Gemini 3 Pro / Imagen 3 请求构建
headers["Content-Type"] = "application/json"

if (!inputs.prompt) {
    throw new IllegalArgumentException("缺少提示词 (prompt)")
}

def parts = []
parts << [text: inputs.prompt]

// 处理参考图片 - inputs.image 已经被 resolveAssetInputs 转换为 base64 data URI
if (inputs.image) {
    def imageList = inputs.image instanceof List ? inputs.image : [inputs.image]

    imageList.each { imageData ->
        if (imageData && imageData.startsWith("data:")) {
            // 已经是 data URI 格式: data:image/png;base64,xxxx
            def matcher = (imageData =~ /^data:([^;]+);base64,(.+)$/)
            if (matcher.find()) {
                def mimeType = matcher.group(1)
                def base64Data = matcher.group(2)
                parts << [inline_data: [mime_type: mimeType, data: base64Data]]
            }
        }
    }
}

def body = [
    contents: [[parts: parts]],
    generationConfig: [responseModalities: ["IMAGE"]]
]

def imageConfig = [:]
if (inputs.aspect_ratio) imageConfig.aspectRatio = inputs.aspect_ratio
if (inputs.image_size) imageConfig.imageSize = inputs.image_size
if (imageConfig) body.generationConfig.imageConfig = imageConfig

return body
$REQUEST$,
$RESPONSE$
// Gemini Image 响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["RESOURCE_EXHAUSTED", "UNAVAILABLE"]], metadata: [raw: response]]
}

def candidates = response.candidates ?: []
if (candidates.isEmpty()) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_CANDIDATES", message: "未生成任何内容"], metadata: [raw: response]]
}

def parts = candidates[0]?.content?.parts ?: []
def items = []
def textResponse = null

parts.each { part ->
    if (part.text) {
        textResponse = part.text
    } else if (part.inlineData || part.inline_data) {
        def data = part.inlineData ?: part.inline_data
        def mimeType = data.mimeType ?: data.mime_type ?: "image/png"
        def item = [mimeType: mimeType]

        if (data.data && oss != null) {
            try {
                def extension = mimeType.contains("png") ? "png" : (mimeType.contains("webp") ? "webp" : "jpg")
                def ossPath = oss.generatePath("images", extension)
                def uploadResult = oss.uploadBase64WithKey(data.data, ossPath, mimeType)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
            } catch (Exception e) {
                item.base64Data = data.data
            }
        } else {
            item.base64Data = data.data
        }
        items << item
    }
}

if (items.isEmpty()) {
    if (textResponse) {
        return [outputType: "TEXT_CONTENT", status: "SUCCEEDED", textContent: textResponse, metadata: [raw: response]]
    }
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_OUTPUT", message: "未生成任何图片或文本"], metadata: [raw: response]]
}

return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "IMAGE", items: items], metadata: [text: textResponse, raw: response]]
$RESPONSE$,
'',
 '{"baseCredits": 3, "minCredits": 1, "maxCredits": 50, "roundMode": "CEILING", "rules": [{"param": "image_size", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"1K": 0.7, "2K": 1.0, "4K": 1.5}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c399a-cf8a-7106-1ece-9ff2291cddc3', '019c399a-cf8a-7106-bece-9ff2291cddc3',
$REQUEST$
// 火山引擎 Seedance 1.5 Pro 视频生成请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: "doubao-seedance-1-5-pro-251215",
    watermark: false
]

def content = []

if (inputs.prompt) {
    content << [type: "text", text: inputs.prompt]
}

if (inputs.first_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.first_frame)], role: "first_frame"]
}

if (inputs.last_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.last_frame)], role: "last_frame"]
}

if (content.isEmpty()) {
    throw new IllegalArgumentException("至少需要提供提示词或首帧图片")
}

body.content = content

if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.ratio) body.ratio = inputs.ratio
if (inputs.duration != null) body.duration = inputs.duration as Integer
if (inputs.seed != null) body.seed = inputs.seed as Integer
if (inputs.camera_fixed != null) body.camera_fixed = inputs.camera_fixed as Boolean
if (inputs.generate_audio != null) body.generate_audio = inputs.generate_audio as Boolean

return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedance 视频生成响应映射 (任务提交)
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["rate_limit_exceeded", "server_error"]], metadata: [raw: response]]
}

def taskId = response.id
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, model: response.model, raw: response]]
$RESPONSE$,
$POLLING$
// 火山引擎 Seedance 视频生成轮询响应处理
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toLowerCase()
def taskId = response.id

if (status == "queued") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "running") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status in ["failed", "cancelled", "expired"]) {
    def errorMsg = response.error?.message ?: "任务${status}"
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error?.code ?: status.toUpperCase(), message: errorMsg, retryable: status == "expired"], metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "succeeded") {
    def content = response.content ?: [:]
    def videoUrl = content.video_url

    if (!videoUrl) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_VIDEO_URL", message: "未返回视频URL"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def finalVideoUrl = videoUrl
    def videoFileKey = null
    def sourceUrl = videoUrl
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("videos", "mp4")
            def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
            finalVideoUrl = uploadResult.url
            videoFileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }

    def result = [
        outputType: "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: [[fileUrl: finalVideoUrl, fileKey: videoFileKey, sourceUrl: sourceUrl, mimeType: "video/mp4"]]],
        metadata: [externalTaskId: taskId, model: response.model, resolution: response.resolution, ratio: response.ratio, duration: response.duration, seed: response.seed, usage: response.usage, raw: response]
    ]

    if (content.last_frame_url) {
        def lastFrameUrl = content.last_frame_url
        if (oss != null) {
            try {
                def ossPath = oss.generatePath("images", "jpg")
                def uploadResult = oss.uploadFromUrlWithKey(content.last_frame_url, ossPath, null)
                lastFrameUrl = uploadResult.url
            } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
        }
        result.metadata.lastFrameUrl = lastFrameUrl
        result.metadata.lastFrameSourceUrl = content.last_frame_url
    }

    return result
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 4, "minCredits": 5, "maxCredits": 200, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 4, "max": 12}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"720p": 0.8, "1080p": 1.0}, "default": 1.0}, {"param": "generate_audio", "type": "MULTIPLIER", "mode": "ADD", "mapping": {"true": 5, "false": 0}, "default": 0}]}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- Seedance 2.0 脚本
-- =====================================================
('00000000-0000-0000-1003-000000000040', '00000000-0000-0000-0003-000000000040',
$REQUEST$
// 火山引擎 Seedance 2.0 视频生成请求构建
// 支持：文生视频、首帧/首尾帧生视频、多模态参考生视频（图片+视频+音频）
headers["Content-Type"] = "application/json"

def body = [
    model: "doubao-seedance-2-0-260128",
    watermark: false
]

def content = []

// 文本提示词
if (inputs.prompt) {
    content << [type: "text", text: inputs.prompt]
}

// 首帧/首尾帧模式
if (inputs.first_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.first_frame)], role: "first_frame"]
}
if (inputs.last_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.last_frame)], role: "last_frame"]
}

// 多模态参考图（1~9张）
if (inputs.reference_images) {
    def refImages = inputs.reference_images instanceof List ? inputs.reference_images : [inputs.reference_images]
    refImages.each { img ->
        content << [type: "image_url", image_url: [url: asset.getAssetUrl(img)], role: "reference_image"]
    }
}

// 参考视频（1~3个）
if (inputs.reference_videos) {
    def refVideos = inputs.reference_videos instanceof List ? inputs.reference_videos : [inputs.reference_videos]
    refVideos.each { vid ->
        content << [type: "video_url", video_url: [url: asset.getAssetUrl(vid)], role: "reference_video"]
    }
}

// 参考音频（1~3段）
if (inputs.reference_audios) {
    def refAudios = inputs.reference_audios instanceof List ? inputs.reference_audios : [inputs.reference_audios]
    refAudios.each { aud ->
        content << [type: "audio_url", audio_url: [url: asset.getAssetUrl(aud)], role: "reference_audio"]
    }
}

if (content.isEmpty()) {
    throw new IllegalArgumentException("至少需要提供提示词、首帧图片或参考素材")
}

body.content = content

// 视频参数
if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.ratio) body.ratio = inputs.ratio
if (inputs.duration != null) body.duration = inputs.duration as Integer
if (inputs.seed != null) body.seed = inputs.seed as Integer
if (inputs.generate_audio != null) body.generate_audio = inputs.generate_audio as Boolean
if (inputs.return_last_frame != null) body.return_last_frame = inputs.return_last_frame as Boolean

return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedance 视频生成响应映射 (任务提交)
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["rate_limit_exceeded", "server_error"]], metadata: [raw: response]]
}

def taskId = response.id
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, model: response.model, raw: response]]
$RESPONSE$,
$POLLING$
// 火山引擎 Seedance 视频生成轮询响应处理
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toLowerCase()
def taskId = response.id

if (status == "queued") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "running") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status in ["failed", "cancelled", "expired"]) {
    def errorMsg = response.error?.message ?: "任务${status}"
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error?.code ?: status.toUpperCase(), message: errorMsg, retryable: status == "expired"], metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "succeeded") {
    def content = response.content ?: [:]
    def videoUrl = content.video_url

    if (!videoUrl) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_VIDEO_URL", message: "未返回视频URL"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def finalVideoUrl = videoUrl
    def videoFileKey = null
    def sourceUrl = videoUrl
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("videos", "mp4")
            def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
            finalVideoUrl = uploadResult.url
            videoFileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }

    def result = [
        outputType: "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: [[fileUrl: finalVideoUrl, fileKey: videoFileKey, sourceUrl: sourceUrl, mimeType: "video/mp4"]]],
        metadata: [externalTaskId: taskId, model: response.model, resolution: response.resolution, ratio: response.ratio, duration: response.duration, seed: response.seed, usage: response.usage, raw: response]
    ]

    if (content.last_frame_url) {
        def lastFrameUrl = content.last_frame_url
        if (oss != null) {
            try {
                def ossPath = oss.generatePath("images", "jpg")
                def uploadResult = oss.uploadFromUrlWithKey(content.last_frame_url, ossPath, null)
                lastFrameUrl = uploadResult.url
            } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
        }
        result.metadata.lastFrameUrl = lastFrameUrl
        result.metadata.lastFrameSourceUrl = content.last_frame_url
    }

    return result
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 5, "minCredits": 5, "maxCredits": 300, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 4, "max": 15}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"480p": 0.6, "720p": 1.0}, "default": 1.0}, {"param": "generate_audio", "type": "MULTIPLIER", "mode": "ADD", "mapping": {"true": 5, "false": 0}, "default": 0}]}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- Seedance 2.0 Fast 脚本（复用 2.0 的脚本逻辑，仅 model ID 不同）
-- =====================================================
('00000000-0000-0000-1003-000000000041', '00000000-0000-0000-0003-000000000041',
$REQUEST$
// 火山引擎 Seedance 2.0 Fast 视频生成请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: "doubao-seedance-2-0-fast-260128",
    watermark: false
]

def content = []

if (inputs.prompt) {
    content << [type: "text", text: inputs.prompt]
}

if (inputs.first_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.first_frame)], role: "first_frame"]
}
if (inputs.last_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.last_frame)], role: "last_frame"]
}

if (inputs.reference_images) {
    def refImages = inputs.reference_images instanceof List ? inputs.reference_images : [inputs.reference_images]
    refImages.each { img ->
        content << [type: "image_url", image_url: [url: asset.getAssetUrl(img)], role: "reference_image"]
    }
}

if (inputs.reference_videos) {
    def refVideos = inputs.reference_videos instanceof List ? inputs.reference_videos : [inputs.reference_videos]
    refVideos.each { vid ->
        content << [type: "video_url", video_url: [url: asset.getAssetUrl(vid)], role: "reference_video"]
    }
}

if (inputs.reference_audios) {
    def refAudios = inputs.reference_audios instanceof List ? inputs.reference_audios : [inputs.reference_audios]
    refAudios.each { aud ->
        content << [type: "audio_url", audio_url: [url: asset.getAssetUrl(aud)], role: "reference_audio"]
    }
}

if (content.isEmpty()) {
    throw new IllegalArgumentException("至少需要提供提示词、首帧图片或参考素材")
}

body.content = content

if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.ratio) body.ratio = inputs.ratio
if (inputs.duration != null) body.duration = inputs.duration as Integer
if (inputs.seed != null) body.seed = inputs.seed as Integer
if (inputs.generate_audio != null) body.generate_audio = inputs.generate_audio as Boolean
if (inputs.return_last_frame != null) body.return_last_frame = inputs.return_last_frame as Boolean

return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedance 视频生成响应映射 (任务提交)
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["rate_limit_exceeded", "server_error"]], metadata: [raw: response]]
}

def taskId = response.id
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, model: response.model, raw: response]]
$RESPONSE$,
$POLLING$
// 火山引擎 Seedance 视频生成轮询响应处理
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toLowerCase()
def taskId = response.id

if (status == "queued") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "running") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status in ["failed", "cancelled", "expired"]) {
    def errorMsg = response.error?.message ?: "任务${status}"
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error?.code ?: status.toUpperCase(), message: errorMsg, retryable: status == "expired"], metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "succeeded") {
    def content = response.content ?: [:]
    def videoUrl = content.video_url

    if (!videoUrl) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_VIDEO_URL", message: "未返回视频URL"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def finalVideoUrl = videoUrl
    def videoFileKey = null
    def sourceUrl = videoUrl
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("videos", "mp4")
            def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
            finalVideoUrl = uploadResult.url
            videoFileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }

    def result = [
        outputType: "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: [[fileUrl: finalVideoUrl, fileKey: videoFileKey, sourceUrl: sourceUrl, mimeType: "video/mp4"]]],
        metadata: [externalTaskId: taskId, model: response.model, resolution: response.resolution, ratio: response.ratio, duration: response.duration, seed: response.seed, usage: response.usage, raw: response]
    ]

    if (content.last_frame_url) {
        def lastFrameUrl = content.last_frame_url
        if (oss != null) {
            try {
                def ossPath = oss.generatePath("images", "jpg")
                def uploadResult = oss.uploadFromUrlWithKey(content.last_frame_url, ossPath, null)
                lastFrameUrl = uploadResult.url
            } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
        }
        result.metadata.lastFrameUrl = lastFrameUrl
        result.metadata.lastFrameSourceUrl = content.last_frame_url
    }

    return result
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 3, "minCredits": 3, "maxCredits": 200, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 4, "max": 15}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"480p": 0.6, "720p": 1.0}, "default": 1.0}, {"param": "generate_audio", "type": "MULTIPLIER", "mode": "ADD", "mapping": {"true": 5, "false": 0}, "default": 0}]}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- MiniMax T2A 脚本
-- =====================================================
('00000000-0000-0000-1003-000000000050', '00000000-0000-0000-0003-000000000050',
$REQUEST$
// MiniMax T2A (speech-2.8-hd) 请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: inputs.model ?: "speech-2.8-hd",
    text: inputs.text ?: inputs.prompt,
    stream: false,
    output_format: inputs.output_format ?: "url",
    voice_setting: [
        voice_id: inputs.voice_id ?: "English_expressive_narrator",
        speed: inputs.speed != null ? inputs.speed as Float : 1.0,
        vol: inputs.vol != null ? inputs.vol as Float : 1.0,
        pitch: inputs.pitch != null ? inputs.pitch as Integer : 0
    ],
    audio_setting: [
        sample_rate: inputs.sample_rate != null ? inputs.sample_rate as Integer : 32000,
        bitrate: inputs.bitrate != null ? inputs.bitrate as Integer : 128000,
        format: inputs.audio_format ?: "mp3",
        channel: 1
    ]
]

if (inputs.emotion) body.voice_setting.emotion = inputs.emotion
if (inputs.language_boost) body.language_boost = inputs.language_boost

return body
$REQUEST$,
$RESPONSE$
// MiniMax T2A 响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

def baseResp = response.base_resp
if (baseResp?.status_code != 0) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "MINIMAX_${baseResp?.status_code}", message: baseResp?.status_msg ?: "未知错误", retryable: baseResp?.status_code in [1001, 1002, 1039]], metadata: [raw: response]]
}

def data = response.data
if (!data?.audio) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_AUDIO", message: "未返回音频数据"], metadata: [raw: response]]
}

def audioUrl = data.audio
def fileKey = null
def mimeType = "audio/" + (response.extra_info?.audio_format ?: "mp3")

// 如果返回的是 hex 编码，解码并上传到 OSS
if (!audioUrl.startsWith("http")) {
    if (oss != null) {
        try {
            def audioBytes = javax.xml.bind.DatatypeConverter.parseHexBinary(audioUrl)
            def ossPath = oss.generatePath("audio", response.extra_info?.audio_format ?: "mp3")
            def uploadResult = oss.uploadBytes(audioBytes, ossPath, mimeType)
            audioUrl = uploadResult.url
            fileKey = uploadResult.fileKey
        } catch (Exception e) {
            log.warn("OSS upload failed: " + e.getMessage())
            return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "OSS_UPLOAD_FAILED", message: "音频上传失败: " + e.getMessage()]]
        }
    }
} else {
    // URL 模式，下载到 OSS
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("audio", response.extra_info?.audio_format ?: "mp3")
            def uploadResult = oss.uploadFromUrlWithKey(audioUrl, ossPath, mimeType)
            audioUrl = uploadResult.url
            fileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }
}

return [
    outputType: "MEDIA_SINGLE",
    status: "SUCCEEDED",
    media: [mediaType: "AUDIO", items: [[fileUrl: audioUrl, fileKey: fileKey, mimeType: mimeType]]],
    metadata: [audioLength: response.extra_info?.audio_length, usageCharacters: response.extra_info?.usage_characters, raw: response]
]
$RESPONSE$,
'',
 '{"baseCredits": 2, "minCredits": 1, "maxCredits": 50, "roundMode": "CEILING", "rules": []}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- MiniMax Voice Clone 脚本
-- =====================================================
('00000000-0000-0000-1003-000000000051', '00000000-0000-0000-0003-000000000051',
$REQUEST$
// MiniMax Voice Clone 请求构建
// 流程：先上传音频到 MiniMax File API，再调用克隆 API
headers["Content-Type"] = "application/json"

def body = [
    voice_id: inputs.voice_id ?: ("clone_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12)),
    text: inputs.preview_text ?: "这是一段语音克隆的预览测试。The quick brown fox jumps over the lazy dog.",
    model: inputs.model ?: "speech-2.8-hd"
]

// 上传源音频到 MiniMax 获取 file_id
if (inputs.source_audio) {
    def audioUrl = asset.getAssetUrl(inputs.source_audio)
    def uploadHeaders = ["Authorization": headers["Authorization"]]
    def uploadResult = http.postMultipart(
        config.baseUrl + "/v1/files/upload",
        [purpose: "voice_clone"],
        [file: [url: audioUrl, filename: "clone_input.mp3"]],
        uploadHeaders
    )
    body.file_id = uploadResult?.file?.file_id
}

// 上传示例音频（可选）
if (inputs.prompt_audio) {
    def promptUrl = asset.getAssetUrl(inputs.prompt_audio)
    def uploadHeaders = ["Authorization": headers["Authorization"]]
    def promptResult = http.postMultipart(
        config.baseUrl + "/v1/files/upload",
        [purpose: "prompt_audio"],
        [file: [url: promptUrl, filename: "clone_prompt.mp3"]],
        uploadHeaders
    )
    body.clone_prompt = [
        prompt_audio: promptResult?.file?.file_id,
        prompt_text: inputs.prompt_text ?: ""
    ]
}

return body
$REQUEST$,
$RESPONSE$
// MiniMax Voice Clone 响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

def baseResp = response.base_resp
if (baseResp?.status_code != 0) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "MINIMAX_${baseResp?.status_code}", message: baseResp?.status_msg ?: "未知错误"], metadata: [raw: response]]
}

def audioData = response.data?.audio
def voiceId = response.data?.voice_id ?: response.voice_id

def audioUrl = null
def fileKey = null
if (audioData && oss != null) {
    try {
        def audioBytes = javax.xml.bind.DatatypeConverter.parseHexBinary(audioData)
        def ossPath = oss.generatePath("audio", "mp3")
        def uploadResult = oss.uploadBytes(audioBytes, ossPath, "audio/mp3")
        audioUrl = uploadResult.url
        fileKey = uploadResult.fileKey
    } catch (Exception e) { log.warn("Preview audio upload failed: " + e.getMessage()) }
}

return [
    outputType: "MEDIA_SINGLE",
    status: "SUCCEEDED",
    media: audioUrl ? [mediaType: "AUDIO", items: [[fileUrl: audioUrl, fileKey: fileKey, mimeType: "audio/mp3"]]] : null,
    metadata: [voiceId: voiceId, raw: response]
]
$RESPONSE$,
'',
 '{"baseCredits": 5, "minCredits": 3, "maxCredits": 20, "roundMode": "CEILING", "rules": []}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- =====================================================
-- MiniMax Music Generation 脚本
-- =====================================================
('00000000-0000-0000-1003-000000000052', '00000000-0000-0000-0003-000000000052',
$REQUEST$
// MiniMax Music Generation (music-2.6) 请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: inputs.model ?: "music-2.6",
    prompt: inputs.prompt,
    output_format: "url",
    audio_setting: [
        sample_rate: 44100,
        bitrate: 256000,
        format: "mp3"
    ]
]

if (inputs.lyrics) body.lyrics = inputs.lyrics
if (inputs.is_instrumental != null) body.is_instrumental = inputs.is_instrumental as Boolean
if (inputs.lyrics_optimizer != null) body.lyrics_optimizer = inputs.lyrics_optimizer as Boolean

return body
$REQUEST$,
$RESPONSE$
// MiniMax Music Generation 响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

def baseResp = response.base_resp
if (baseResp?.status_code != 0) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "MINIMAX_${baseResp?.status_code}", message: baseResp?.status_msg ?: "未知错误", retryable: baseResp?.status_code in [1001, 1002]], metadata: [raw: response]]
}

def audioUrl = response.data?.audio
if (!audioUrl) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_AUDIO", message: "未返回音频数据"], metadata: [raw: response]]
}

def finalUrl = audioUrl
def fileKey = null
if (audioUrl.startsWith("http") && oss != null) {
    try {
        def ossPath = oss.generatePath("audio/music", "mp3")
        def uploadResult = oss.uploadFromUrlWithKey(audioUrl, ossPath, "audio/mp3")
        finalUrl = uploadResult.url
        fileKey = uploadResult.fileKey
    } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
}

return [
    outputType: "MEDIA_SINGLE",
    status: "SUCCEEDED",
    media: [mediaType: "AUDIO", items: [[fileUrl: finalUrl, fileKey: fileKey, sourceUrl: audioUrl, mimeType: "audio/mp3"]]],
    metadata: [audioLength: response.extra_info?.audio_length, musicDuration: response.extra_info?.music_duration, raw: response]
]
$RESPONSE$,
'',
 '{"baseCredits": 10, "minCredits": 5, "maxCredits": 100, "roundMode": "CEILING", "rules": []}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-1003-000000000022', '00000000-0000-0000-0003-000000000022',
$REQUEST$
// 火山引擎 Seedance 1.0 Pro 视频生成请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: "doubao-seedance-1-0-pro-250528",
    watermark: false
]

def content = []

if (inputs.prompt) {
    content << [type: "text", text: inputs.prompt]
}

if (inputs.first_frame) {
    content << [type: "image_url", image_url: [url: asset.resolveToUrl(inputs.first_frame)], role: "first_frame"]
}

if (inputs.last_frame) {
    content << [type: "image_url", image_url: [url: asset.resolveToUrl(inputs.last_frame)], role: "last_frame"]
}

if (content.isEmpty()) {
    throw new IllegalArgumentException("至少需要提供提示词或首帧图片")
}

body.content = content

if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.ratio) body.ratio = inputs.ratio
if (inputs.duration != null) body.duration = inputs.duration as Integer
if (inputs.seed != null) body.seed = inputs.seed as Integer
if (inputs.camera_fixed != null) body.camera_fixed = inputs.camera_fixed as Boolean

return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedance 视频生成响应映射 (任务提交)
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["rate_limit_exceeded", "server_error"]], metadata: [raw: response]]
}

def taskId = response.id
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, model: response.model, raw: response]]
$RESPONSE$,
$POLLING$
// 火山引擎 Seedance 视频生成轮询响应处理
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toLowerCase()
def taskId = response.id

if (status == "queued") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "running") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status in ["failed", "cancelled", "expired"]) {
    def errorMsg = response.error?.message ?: "任务${status}"
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error?.code ?: status.toUpperCase(), message: errorMsg, retryable: status == "expired"], metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "succeeded") {
    def content = response.content ?: [:]
    def videoUrl = content.video_url

    if (!videoUrl) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_VIDEO_URL", message: "未返回视频URL"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def finalVideoUrl = videoUrl
    def videoFileKey = null
    def sourceUrl = videoUrl
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("videos", "mp4")
            def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
            finalVideoUrl = uploadResult.url
            videoFileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }

    def result = [
        outputType: "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: [[fileUrl: finalVideoUrl, fileKey: videoFileKey, sourceUrl: sourceUrl, mimeType: "video/mp4"]]],
        metadata: [externalTaskId: taskId, model: response.model, resolution: response.resolution, ratio: response.ratio, duration: response.duration, seed: response.seed, usage: response.usage, raw: response]
    ]

    if (content.last_frame_url) {
        def lastFrameUrl = content.last_frame_url
        if (oss != null) {
            try {
                def ossPath = oss.generatePath("images", "jpg")
                def uploadResult = oss.uploadFromUrlWithKey(content.last_frame_url, ossPath, null)
                lastFrameUrl = uploadResult.url
            } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
        }
        result.metadata.lastFrameUrl = lastFrameUrl
        result.metadata.lastFrameSourceUrl = content.last_frame_url
    }

    return result
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 4, "minCredits": 5, "maxCredits": 200, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 2, "max": 12}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"720p": 0.8, "1080p": 1.0}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c399f-72f2-f80e-1e49-a3763928fd1e', '019c399f-72f2-f80e-9e49-a3763928fd1e',
$REQUEST$
// 火山引擎 Seedance 1.0 Pro Fast 视频生成请求构建
headers["Content-Type"] = "application/json"

def body = [
    model: "doubao-seedance-1-0-pro-fast-251015",
    watermark: false
]

def content = []

if (inputs.prompt) {
    content << [type: "text", text: inputs.prompt]
}

if (inputs.first_frame) {
    content << [type: "image_url", image_url: [url: asset.getAssetUrl(inputs.first_frame)], role: "first_frame"]
}

if (content.isEmpty()) {
    throw new IllegalArgumentException("至少需要提供提示词或首帧图片")
}

body.content = content

if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.ratio) body.ratio = inputs.ratio
if (inputs.duration != null) body.duration = inputs.duration as Integer
if (inputs.seed != null) body.seed = inputs.seed as Integer
if (inputs.camera_fixed != null) body.camera_fixed = inputs.camera_fixed as Boolean

return body
$REQUEST$,
$RESPONSE$
// 火山引擎 Seedance 视频生成响应映射 (任务提交)
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.error) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error.code ?: "API_ERROR", message: response.error.message ?: "未知错误", retryable: response.error.code in ["rate_limit_exceeded", "server_error"]], metadata: [raw: response]]
}

def taskId = response.id
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, model: response.model, raw: response]]
$RESPONSE$,
$POLLING$
// 火山引擎 Seedance 视频生成轮询响应处理
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toLowerCase()
def taskId = response.id

if (status == "queued") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "running") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status in ["failed", "cancelled", "expired"]) {
    def errorMsg = response.error?.message ?: "任务${status}"
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.error?.code ?: status.toUpperCase(), message: errorMsg, retryable: status == "expired"], metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "succeeded") {
    def content = response.content ?: [:]
    def videoUrl = content.video_url

    if (!videoUrl) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_VIDEO_URL", message: "未返回视频URL"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def finalVideoUrl = videoUrl
    def videoFileKey = null
    def sourceUrl = videoUrl
    if (oss != null) {
        try {
            def ossPath = oss.generatePath("videos", "mp4")
            def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
            finalVideoUrl = uploadResult.url
            videoFileKey = uploadResult.fileKey
        } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
    }

    def result = [
        outputType: "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: [[fileUrl: finalVideoUrl, fileKey: videoFileKey, sourceUrl: sourceUrl, mimeType: "video/mp4"]]],
        metadata: [externalTaskId: taskId, model: response.model, resolution: response.resolution, ratio: response.ratio, duration: response.duration, seed: response.seed, usage: response.usage, raw: response]
    ]

    if (content.last_frame_url) {
        def lastFrameUrl = content.last_frame_url
        if (oss != null) {
            try {
                def ossPath = oss.generatePath("images", "jpg")
                def uploadResult = oss.uploadFromUrlWithKey(content.last_frame_url, ossPath, null)
                lastFrameUrl = uploadResult.url
            } catch (Exception e) { log.warn("OSS upload failed, using source URL: " + e.getMessage()) }
        }
        result.metadata.lastFrameUrl = lastFrameUrl
        result.metadata.lastFrameSourceUrl = content.last_frame_url
    }

    return result
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 2, "minCredits": 2, "maxCredits": 100, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 2, "max": 12}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"720p": 0.8, "1080p": 1.0}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-1003-000000000031', '00000000-0000-0000-0003-000000000031',
$REQUEST$
// RunningHub Midjourney V7 请求构建
headers["Content-Type"] = "application/json"

if (!inputs.prompt) {
    throw new IllegalArgumentException("缺少提示词 (prompt)")
}

def body = [prompt: inputs.prompt]

// 基础参数
if (inputs.negativePrompt) body.negativePrompt = inputs.negativePrompt
if (inputs.aspectRatio) body.aspectRatio = inputs.aspectRatio
if (inputs.quality != null) body.quality = inputs.quality as String

// 风格参数
if (inputs.stylize != null) body.stylize = inputs.stylize as Integer
if (inputs.chaos != null) body.chaos = inputs.chaos as Integer
if (inputs.weird != null) body.weird = inputs.weird as Integer
if (inputs.raw != null) body.raw = inputs.raw as Boolean

// 图片参考
if (inputs.imageUrl) {
    body.imageUrl = inputs.imageUrl instanceof List ? inputs.imageUrl : [inputs.imageUrl]
}
if (inputs.iw != null) body.iw = inputs.iw as Integer

// 风格参考 (Style Reference)
if (inputs.sref) {
    body.sref = inputs.sref instanceof List ? inputs.sref : [inputs.sref]
}
if (inputs.sw != null) body.sw = inputs.sw as Integer
if (inputs.sv != null) body.sv = inputs.sv as Integer

// 对象参考 (Object Reference) - V7 独有
if (inputs.oref) {
    body.oref = inputs.oref instanceof List ? inputs.oref : [inputs.oref]
}
if (inputs.ow != null) body.ow = inputs.ow as Integer

// 高级参数
if (inputs.tile != null) body.tile = inputs.tile as Boolean

return body
$REQUEST$,
$RESPONSE$
// RunningHub Midjourney 任务提交响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.errorCode && response.errorCode.toString().trim() != "") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode, message: response.errorMessage ?: "未知错误", retryable: false], metadata: [raw: response]]
}

def taskId = response.taskId
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

def status = response.status?.toUpperCase()
if (status == "SUCCESS") {
    // 同步返回结果（极少情况）
    def results = response.results ?: []
    if (!results.isEmpty()) {
        def items = results.collect { r ->
            def item = [mimeType: "image/${r.outputType ?: 'png'}"]
            item.fileUrl = r.url
            return item
        }
        return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "IMAGE", items: items], metadata: [externalTaskId: taskId, raw: response]]
    }
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, clientId: response.clientId, raw: response]]
$RESPONSE$,
$POLLING$
// RunningHub Midjourney POST 轮询响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toUpperCase()
def taskId = response.taskId

if (status == "QUEUED") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "RUNNING") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "FAILED") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode ?: "TASK_FAILED", message: response.errorMessage ?: "任务失败", retryable: false], metadata: [externalTaskId: taskId, failedReason: response.failedReason, raw: response]]
}

if (status == "SUCCESS") {
    def results = response.results ?: []
    if (results.isEmpty()) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_RESULTS", message: "未返回生成结果"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def items = results.collect { r ->
        def item = [mimeType: "image/${r.outputType ?: 'png'}"]
        def imageUrl = r.url
        if (imageUrl && oss != null) {
            try {
                def extension = r.outputType ?: "png"
                def ossPath = oss.generatePath("images", extension)
                def uploadResult = oss.uploadFromUrlWithKey(imageUrl, ossPath, null)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
                item.sourceUrl = imageUrl
            } catch (Exception e) {
                item.fileUrl = imageUrl
            }
        } else {
            item.fileUrl = imageUrl
        }
        return item
    }

    return [
        outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "IMAGE", items: items],
        metadata: [externalTaskId: taskId, usage: response.usage, raw: response]
    ]
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 15, "minCredits": 5, "maxCredits": 100, "roundMode": "CEILING", "rules": [{"param": "quality", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"1": 1.0, "2": 1.5, "4": 2.0}, "default": 1.0}]}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-1003-000000000032', '00000000-0000-0000-0003-000000000032',
$REQUEST$
// RunningHub Midjourney Niji 7 请求构建
headers["Content-Type"] = "application/json"

if (!inputs.prompt) {
    throw new IllegalArgumentException("缺少提示词 (prompt)")
}

def body = [prompt: inputs.prompt]

// 基础参数
if (inputs.aspectRatio) body.aspectRatio = inputs.aspectRatio

// 风格参数
if (inputs.stylize != null) body.stylize = inputs.stylize as Integer
if (inputs.chaos != null) body.chaos = inputs.chaos as Integer
if (inputs.weird != null) body.weird = inputs.weird as Integer
if (inputs.raw != null) body.raw = inputs.raw as Boolean

// 图片参考
if (inputs.imageUrl) {
    body.imageUrl = inputs.imageUrl instanceof List ? inputs.imageUrl : [inputs.imageUrl]
}
if (inputs.iw != null) body.iw = inputs.iw as Integer

// 风格参考 (Style Reference)
if (inputs.sref) {
    body.sref = inputs.sref instanceof List ? inputs.sref : [inputs.sref]
}
if (inputs.sw != null) body.sw = inputs.sw as Integer
if (inputs.sv != null) body.sv = inputs.sv as Integer

return body
$REQUEST$,
$RESPONSE$
// RunningHub Midjourney 任务提交响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.errorCode && response.errorCode.toString().trim() != "") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode, message: response.errorMessage ?: "未知错误", retryable: false], metadata: [raw: response]]
}

def taskId = response.taskId
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

def status = response.status?.toUpperCase()
if (status == "SUCCESS") {
    def results = response.results ?: []
    if (!results.isEmpty()) {
        def items = results.collect { r ->
            def item = [mimeType: "image/${r.outputType ?: 'png'}"]
            item.fileUrl = r.url
            return item
        }
        return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "IMAGE", items: items], metadata: [externalTaskId: taskId, raw: response]]
    }
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, clientId: response.clientId, raw: response]]
$RESPONSE$,
$POLLING$
// RunningHub Midjourney POST 轮询响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toUpperCase()
def taskId = response.taskId

if (status == "QUEUED") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "RUNNING") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "FAILED") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode ?: "TASK_FAILED", message: response.errorMessage ?: "任务失败", retryable: false], metadata: [externalTaskId: taskId, failedReason: response.failedReason, raw: response]]
}

if (status == "SUCCESS") {
    def results = response.results ?: []
    if (results.isEmpty()) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_RESULTS", message: "未返回生成结果"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def items = results.collect { r ->
        def item = [mimeType: "image/${r.outputType ?: 'png'}"]
        def imageUrl = r.url
        if (imageUrl && oss != null) {
            try {
                def extension = r.outputType ?: "png"
                def ossPath = oss.generatePath("images", extension)
                def uploadResult = oss.uploadFromUrlWithKey(imageUrl, ossPath, null)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
                item.sourceUrl = imageUrl
            } catch (Exception e) {
                item.fileUrl = imageUrl
            }
        } else {
            item.fileUrl = imageUrl
        }
        return item
    }

    return [
        outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "IMAGE", items: items],
        metadata: [externalTaskId: taskId, usage: response.usage, raw: response]
    ]
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 NULL, NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-1003-000000000033', '00000000-0000-0000-0003-000000000033',
$REQUEST$
// RunningHub Vidu Q2 Pro 参考生视频请求构建
headers["Content-Type"] = "application/json"

if (!inputs.prompt) {
    throw new IllegalArgumentException("缺少提示词 (prompt)")
}
if (!inputs.imageUrls || inputs.imageUrls.isEmpty()) {
    throw new IllegalArgumentException("缺少参考图片 (imageUrls)，至少需要1张")
}

def body = [prompt: inputs.prompt]

// 参考图片（必填，最多7张）
body.imageUrls = inputs.imageUrls instanceof List ? inputs.imageUrls : [inputs.imageUrls]
if (body.imageUrls.size() > 7) {
    body.imageUrls = body.imageUrls.take(7)
}

// 参考视频（可选，最多2个）
if (inputs.videos) {
    body.videos = inputs.videos instanceof List ? inputs.videos : [inputs.videos]
    if (body.videos.size() > 2) {
        body.videos = body.videos.take(2)
    }
}

// 视频时长（必填）
if (inputs.duration) body.duration = inputs.duration.toString()

// 可选参数
if (inputs.aspectRatio) body.aspectRatio = inputs.aspectRatio
if (inputs.resolution) body.resolution = inputs.resolution
if (inputs.movementAmplitude) body.movementAmplitude = inputs.movementAmplitude

return body
$REQUEST$,
$RESPONSE$
// RunningHub Vidu Q2 Pro 任务提交响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "API返回空响应", retryable: true]]
}

if (response.errorCode && response.errorCode.toString().trim() != "") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode, message: response.errorMessage ?: "未知错误", retryable: false], metadata: [raw: response]]
}

def taskId = response.taskId
if (!taskId) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_TASK_ID", message: "未返回任务ID"], metadata: [raw: response]]
}

def status = response.status?.toUpperCase()
if (status == "SUCCESS") {
    def results = response.results ?: []
    if (!results.isEmpty()) {
        def items = results.collect { r ->
            def item = [mimeType: "video/mp4"]
            item.fileUrl = r.url
            return item
        }
        return [outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE", status: "SUCCEEDED", media: [mediaType: "VIDEO", items: items], metadata: [externalTaskId: taskId, raw: response]]
    }
}

return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, clientId: response.clientId, raw: response]]
$RESPONSE$,
$POLLING$
// RunningHub Vidu Q2 Pro POST 轮询响应映射
if (!response) {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "EMPTY_RESPONSE", message: "轮询响应为空", retryable: true]]
}

def status = response.status?.toUpperCase()
def taskId = response.taskId

if (status == "QUEUED") {
    return [outputType: "MEDIA_SINGLE", status: "PENDING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "RUNNING") {
    return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, raw: response]]
}

if (status == "FAILED") {
    return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: response.errorCode ?: "TASK_FAILED", message: response.errorMessage ?: "任务失败", retryable: false], metadata: [externalTaskId: taskId, failedReason: response.failedReason, raw: response]]
}

if (status == "SUCCESS") {
    def results = response.results ?: []
    if (results.isEmpty()) {
        return [outputType: "MEDIA_SINGLE", status: "FAILED", error: [code: "NO_RESULTS", message: "未返回生成结果"], metadata: [externalTaskId: taskId, raw: response]]
    }

    def items = results.collect { r ->
        def item = [mimeType: "video/mp4"]
        def videoUrl = r.url
        if (videoUrl && oss != null) {
            try {
                def ossPath = oss.generatePath("videos", "mp4")
                def uploadResult = oss.uploadFromUrlWithKey(videoUrl, ossPath, null)
                item.fileUrl = uploadResult.url
                item.fileKey = uploadResult.fileKey
                item.sourceUrl = videoUrl
            } catch (Exception e) {
                item.fileUrl = videoUrl
            }
        } else {
            item.fileUrl = videoUrl
        }
        return item
    }

    return [
        outputType: items.size() > 1 ? "MEDIA_BATCH" : "MEDIA_SINGLE",
        status: "SUCCEEDED",
        media: [mediaType: "VIDEO", items: items],
        metadata: [externalTaskId: taskId, usage: response.usage, raw: response]
    ]
}

return [outputType: "MEDIA_SINGLE", status: "RUNNING", metadata: [externalTaskId: taskId, originalStatus: status, raw: response]]
$POLLING$,
 '{"baseCredits": 5, "minCredits": 5, "maxCredits": 200, "roundMode": "CEILING", "rules": [{"param": "duration", "type": "LINEAR", "mode": "MULTIPLY", "perUnit": 1.0, "min": 1, "max": 10}, {"param": "resolution", "type": "MULTIPLIER", "mode": "MULTIPLY", "mapping": {"720p": 1.0, "1080p": 1.5}, "default": 1.0}, {"param": "imageUrls.size", "type": "TIERED", "mode": "ADD", "tiers": [{"from": 1, "to": 3, "value": 0}, {"from": 4, "to": 7, "value": 5}], "default": 0}]}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0)
ON CONFLICT (provider_id) WHERE deleted = 0 DO UPDATE SET
    request_builder_script = EXCLUDED.request_builder_script,
    response_mapper_script = EXCLUDED.response_mapper_script,
    custom_logic_script = EXCLUDED.custom_logic_script,
    pricing_rules = EXCLUDED.pricing_rules,
    pricing_script = EXCLUDED.pricing_script,
    updated_at = NOW();

INSERT INTO t_model_provider_schema (
    id, provider_id,
    input_schema, input_groups, exclusive_groups, output_schema,
    created_by, updated_by, deleted, version
) VALUES

('00000000-0000-0000-2003-000000000001', '00000000-0000-0000-0003-000000000001',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "order": 1, "labelEn": "Prompt", "required": true, "description": "生成图片的描述文本，支持中英文，建议不超过300字", "placeholder": "描述你想要生成的图片..."}, {"name": "image", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "order": 2, "labelEn": "Reference Image", "required": false, "fileConfig": {"accept": "image/png,image/jpeg,image/webp", "maxSize": 10485760, "uploadTip": "支持 PNG、JPEG、WebP 格式", "inputFormat": "URL", "maxSizeLabel": "10MB"}, "description": "参考图片，支持最多14张"}, {"enum": ["2560x1440", "1440x2560", "2304x1728", "1728x2304", "2496x1664", "1664x2496", "3024x1296"], "name": "size", "type": "SELECT", "group": "basic", "label": "尺寸", "order": 3, "default": "2560x1440", "labelEn": "Size", "options": [{"label": "2K", "value": "2K"}, {"label": "4K", "value": "4K"}, {"label": "2048x2048 (1:1)", "value": "2048x2048"}, {"label": "2304x1728 (4:3)", "value": "2304x1728"}, {"label": "1728x2304 (3:4)", "value": "1728x2304"}, {"label": "2560x1440 (16:9)", "value": "2560x1440"}, {"label": "1440x2560 (9:16)", "value": "1440x2560"}], "required": false, "description": "图片尺寸", "defaultValue": "2560x1440"}, {"max": 4096, "min": 2560, "name": "width", "type": "NUMBER", "group": "advanced", "label": "图片宽度", "order": 3, "component": "input", "validation": {"max": 4096, "min": 2560}, "description": "图片宽度-优先级高于图片尺寸"}, {"max": 4096, "min": 1440, "name": "height", "type": "NUMBER", "group": "advanced", "label": "图片高度", "order": 4, "component": "input", "validation": {"max": 4096, "min": 1440}, "description": "图片高度-优先级高于图片尺寸"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced"}]',
 '[]',
 '[{"name": "url", "type": "STRING", "label": "图片URL"}, {"name": "b64_json", "type": "STRING", "label": "Base64数据"}, {"name": "size", "type": "STRING", "label": "实际尺寸"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c383d-bc4b-fa0a-2217-6ee9713f03e3', '019c383d-bc4b-fa0a-8217-6ee9713f03e3',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "order": 1, "labelEn": "Prompt", "required": true, "description": "生成图片的描述文本，支持中英文，建议不超过300字", "placeholder": "描述你想要生成的图片..."}, {"name": "image", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "order": 2, "labelEn": "Reference Image", "required": false, "fileConfig": {"accept": "image/png,image/jpeg,image/webp", "maxSize": 10485760, "uploadTip": "支持 PNG、JPEG、WebP 格式", "inputFormat": "URL", "maxSizeLabel": "10MB"}, "description": "参考图片，支持最多14张"}, {"enum": ["2560x1440", "1440x2560", "2304x1728", "1728x2304", "2496x1664", "1664x2496", "3024x1296"], "name": "size", "type": "SELECT", "group": "basic", "label": "尺寸", "order": 3, "default": "2560x1440", "labelEn": "Size", "options": [{"label": "2K", "value": "2K"}, {"label": "4K", "value": "4K"}, {"label": "2048x2048 (1:1)", "value": "2048x2048"}, {"label": "2304x1728 (4:3)", "value": "2304x1728"}, {"label": "1728x2304 (3:4)", "value": "1728x2304"}, {"label": "2560x1440 (16:9)", "value": "2560x1440"}, {"label": "1440x2560 (9:16)", "value": "1440x2560"}], "required": false, "description": "图片尺寸", "defaultValue": "2560x1440"}, {"max": 4096, "min": 1280, "name": "width", "type": "NUMBER", "group": "advanced", "label": "图片宽度", "order": 3, "component": "input", "validation": {"max": 4096, "min": 1280}, "description": "图片宽度-优先级高于图片尺寸"}, {"max": 4096, "min": 720, "name": "height", "type": "NUMBER", "group": "advanced", "label": "图片高度", "order": 4, "component": "input", "validation": {"max": 4096, "min": 720}, "description": "图片高度-优先级高于图片尺寸"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced"}]',
 '[]',
 '[{"name": "url", "type": "STRING", "label": "图片URL"}, {"name": "b64_json", "type": "STRING", "label": "Base64数据"}, {"name": "size", "type": "STRING", "label": "实际尺寸"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-2003-000000000012', '00000000-0000-0000-0003-000000000012',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "required": true, "description": "生成图片的详细描述"}, {"name": "image", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "required": false, "fileConfig": {"maxCount": 14, "inputFormat": "BASE64", "includeDataUriPrefix": true}, "description": "参考图片列表，最多支持14张"}, {"enum": ["1:1", "2:3", "3:2", "3:4", "4:3", "4:5", "5:4", "9:16", "16:9", "21:9"], "name": "aspect_ratio", "type": "SELECT", "group": "basic", "label": "宽高比", "default": "16:9", "required": false, "description": "图片宽高比", "defaultValue": "16:9"}, {"enum": ["1K", "2K", "4K"], "name": "image_size", "type": "SELECT", "group": "basic", "label": "分辨率", "default": "2K", "required": false, "description": "图片分辨率", "defaultValue": "2K"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}]',
 '[]',
 '[{"name": "text", "type": "STRING", "label": "文本响应"}, {"name": "image_data", "type": "STRING", "label": "图片Base64数据"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

-- Seedance 2.0 schema
('00000000-0000-0000-2003-000000000040', '00000000-0000-0000-0003-000000000040',
 '[{"name": "prompt", "type": "STRING", "group": "basic", "label": "提示词", "required": false, "description": "视频描述文本，中文不超过500字，英文不超过1000词，支持中英日印尼西葡语"}, {"name": "first_frame", "type": "IMAGE", "group": "frame", "label": "首帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "首帧图片（与多模态参考互斥）"}, {"name": "last_frame", "type": "IMAGE", "group": "frame", "label": "尾帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "尾帧图片（需配合首帧使用）"}, {"name": "reference_images", "type": "IMAGE_LIST", "group": "reference", "label": "参考图片", "required": false, "fileConfig": {"maxCount": 9, "maxSize": 31457280}, "description": "多模态参考图片（1~9张，与首帧/尾帧互斥）"}, {"name": "reference_videos", "type": "VIDEO_LIST", "group": "reference", "label": "参考视频", "required": false, "fileConfig": {"maxCount": 3, "maxSize": 52428800}, "description": "参考视频（1~3个，单个≤50MB，总时长≤15s）"}, {"name": "reference_audios", "type": "AUDIO_LIST", "group": "reference", "label": "参考音频", "required": false, "fileConfig": {"maxCount": 3, "maxSize": 15728640}, "description": "参考音频（1~3段，单个≤15MB，总时长≤15s，不可单独输入）"}, {"enum": ["480p", "720p"], "name": "resolution", "type": "STRING", "group": "basic", "label": "分辨率", "default": "720p", "required": false, "description": "视频分辨率（2.0不支持1080p）", "defaultValue": "720p"}, {"enum": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"], "name": "ratio", "type": "STRING", "group": "basic", "label": "宽高比", "default": "adaptive", "required": false, "description": "视频尺寸，adaptive自动适配"}, {"max": 15, "min": -1, "name": "duration", "type": "INTEGER", "group": "basic", "label": "时长(秒)", "default": 5, "required": false, "validation": {"min": -1, "max": 15}, "description": "视频时长4~15秒，-1为智能选择"}, {"max": 4294967294, "min": -1, "name": "seed", "type": "NUMBER", "group": "advanced", "label": "随机种子", "validation": {"max": 4294967294, "min": -1}, "description": "控制生成随机性，相同seed生成类似结果"}, {"name": "generate_audio", "type": "BOOLEAN", "group": "advanced", "label": "有声视频", "default": true, "description": "生成视频是否包含同步音频（人声/音效/BGM）", "defaultValue": true}, {"name": "return_last_frame", "type": "BOOLEAN", "group": "advanced", "label": "返回尾帧", "default": false, "description": "返回生成视频的尾帧图片，可用于连续生成", "defaultValue": false}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "frame", "label": "首尾帧", "order": 2, "labelEn": "Frame"}, {"name": "reference", "label": "多模态参考", "order": 3, "labelEn": "Reference"}, {"name": "advanced", "label": "高级参数", "order": 4, "labelEn": "Advanced", "collapsed": true}]',
 '[{"groups": ["frame", "reference"], "message": "首尾帧模式与多模态参考模式不可同时使用"}]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "last_frame_url", "type": "STRING", "label": "尾帧图片URL"}, {"name": "duration", "type": "INTEGER", "label": "实际时长"}, {"name": "resolution", "type": "STRING", "label": "实际分辨率"}, {"name": "ratio", "type": "STRING", "label": "实际宽高比"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- Seedance 2.0 Fast schema（与 2.0 相同）
('00000000-0000-0000-2003-000000000041', '00000000-0000-0000-0003-000000000041',
 '[{"name": "prompt", "type": "STRING", "group": "basic", "label": "提示词", "required": false, "description": "视频描述文本，中文不超过500字，英文不超过1000词，支持中英日印尼西葡语"}, {"name": "first_frame", "type": "IMAGE", "group": "frame", "label": "首帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "首帧图片（与多模态参考互斥）"}, {"name": "last_frame", "type": "IMAGE", "group": "frame", "label": "尾帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "尾帧图片（需配合首帧使用）"}, {"name": "reference_images", "type": "IMAGE_LIST", "group": "reference", "label": "参考图片", "required": false, "fileConfig": {"maxCount": 9, "maxSize": 31457280}, "description": "多模态参考图片（1~9张，与首帧/尾帧互斥）"}, {"name": "reference_videos", "type": "VIDEO_LIST", "group": "reference", "label": "参考视频", "required": false, "fileConfig": {"maxCount": 3, "maxSize": 52428800}, "description": "参考视频（1~3个，单个≤50MB，总时长≤15s）"}, {"name": "reference_audios", "type": "AUDIO_LIST", "group": "reference", "label": "参考音频", "required": false, "fileConfig": {"maxCount": 3, "maxSize": 15728640}, "description": "参考音频（1~3段，单个≤15MB，总时长≤15s，不可单独输入）"}, {"enum": ["480p", "720p"], "name": "resolution", "type": "STRING", "group": "basic", "label": "分辨率", "default": "720p", "required": false, "description": "视频分辨率（2.0 Fast不支持1080p）", "defaultValue": "720p"}, {"enum": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"], "name": "ratio", "type": "STRING", "group": "basic", "label": "宽高比", "default": "adaptive", "required": false, "description": "视频尺寸，adaptive自动适配"}, {"max": 15, "min": -1, "name": "duration", "type": "INTEGER", "group": "basic", "label": "时长(秒)", "default": 5, "required": false, "validation": {"min": -1, "max": 15}, "description": "视频时长4~15秒，-1为智能选择"}, {"max": 4294967294, "min": -1, "name": "seed", "type": "NUMBER", "group": "advanced", "label": "随机种子", "validation": {"max": 4294967294, "min": -1}, "description": "控制生成随机性，相同seed生成类似结果"}, {"name": "generate_audio", "type": "BOOLEAN", "group": "advanced", "label": "有声视频", "default": true, "description": "生成视频是否包含同步音频（人声/音效/BGM）", "defaultValue": true}, {"name": "return_last_frame", "type": "BOOLEAN", "group": "advanced", "label": "返回尾帧", "default": false, "description": "返回生成视频的尾帧图片，可用于连续生成", "defaultValue": false}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "frame", "label": "首尾帧", "order": 2, "labelEn": "Frame"}, {"name": "reference", "label": "多模态参考", "order": 3, "labelEn": "Reference"}, {"name": "advanced", "label": "高级参数", "order": 4, "labelEn": "Advanced", "collapsed": true}]',
 '[{"groups": ["frame", "reference"], "message": "首尾帧模式与多模态参考模式不可同时使用"}]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "last_frame_url", "type": "STRING", "label": "尾帧图片URL"}, {"name": "duration", "type": "INTEGER", "label": "实际时长"}, {"name": "resolution", "type": "STRING", "label": "实际分辨率"}, {"name": "ratio", "type": "STRING", "label": "实际宽高比"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c399a-cf8a-7106-2ece-9ff2291cddc3', '019c399a-cf8a-7106-bece-9ff2291cddc3',
 '[{"name": "prompt", "type": "STRING", "group": "basic", "label": "提示词", "required": true, "description": "视频描述文本，建议不超过500字"}, {"name": "first_frame", "type": "IMAGE", "group": "image", "label": "首帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "首帧图片"}, {"name": "last_frame", "type": "IMAGE", "group": "image", "label": "尾帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "尾帧图片（需配合首帧使用）"}, {"enum": ["720p", "1080p"], "name": "resolution", "type": "STRING", "group": "basic", "label": "分辨率", "default": "1080p", "required": false, "description": "视频分辨率", "defaultValue": "1080p"}, {"enum": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"], "name": "ratio", "type": "STRING", "group": "basic", "label": "宽高比", "default": "adaptive", "required": false, "description": "视频尺寸"}, {"max": 12, "min": 4, "name": "duration", "type": "INTEGER", "group": "basic", "label": "时长(秒)", "default": 5, "required": false, "validation": {"min": 4}, "description": "视频时长"}, {"name": "camera_fixed", "type": "BOOLEAN", "group": "advanced", "label": "固定镜头", "default": false, "required": false, "description": "固定镜头"}, {"max": 4294967294, "min": -1, "name": "seed", "type": "NUMBER", "group": "advanced", "label": "随机种子", "order": 8, "component": "input", "validation": {"max": 4294967294, "min": -1}, "description": "相同的请求下，模型收到相同的seed值，会生成类似的结果，但不保证完全一致"}, {"name": "generate_audio", "type": "BOOLEAN", "group": "advanced", "label": "音频输出", "order": 8, "default": false, "component": "input", "description": "控制生成的视频是否包含与画面同步的声音", "defaultValue": false}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced"}]',
 '[]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "last_frame_url", "type": "STRING", "label": "尾帧图片URL"}, {"name": "duration", "type": "INTEGER", "label": "实际时长"}, {"name": "frames", "type": "INTEGER", "label": "实际帧数"}, {"name": "resolution", "type": "STRING", "label": "实际分辨率"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- MiniMax T2A schema
('00000000-0000-0000-2003-000000000050', '00000000-0000-0000-0003-000000000050',
 '[{"name": "text", "type": "TEXTAREA", "group": "basic", "label": "文本内容", "required": true, "description": "要转换为语音的文本，不超过10000字符。支持停顿标记 <#x#> 和拟声词标签"}, {"name": "voice_id", "type": "STRING", "group": "basic", "label": "音色ID", "required": false, "default": "English_expressive_narrator", "description": "目标音色ID，支持系统音色、克隆音色"}, {"name": "speed", "type": "NUMBER", "group": "basic", "label": "语速", "default": 1.0, "required": false, "validation": {"min": 0.5, "max": 2.0}, "description": "语速 0.5~2.0"}, {"enum": ["speech-2.8-hd", "speech-2.8-turbo", "speech-2.6-hd", "speech-2.6-turbo"], "name": "model", "type": "STRING", "group": "advanced", "label": "模型", "default": "speech-2.8-hd", "required": false, "description": "语音合成模型"}, {"enum": ["happy", "sad", "angry", "fearful", "disgusted", "surprised", "calm"], "name": "emotion", "type": "STRING", "group": "advanced", "label": "情感", "required": false, "description": "语音情感控制"}, {"enum": ["Chinese", "English", "Japanese", "Korean", "auto"], "name": "language_boost", "type": "STRING", "group": "advanced", "label": "语言增强", "required": false, "description": "增强特定语言识别"}, {"enum": ["url", "hex"], "name": "output_format", "type": "STRING", "group": "advanced", "label": "输出格式", "default": "url", "required": false, "description": "url返回下载链接，hex返回编码数据"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "advanced", "label": "高级参数", "order": 2, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "audio_url", "type": "STRING", "label": "音频URL"}, {"name": "audio_length", "type": "INTEGER", "label": "音频时长(ms)"}, {"name": "usage_characters", "type": "INTEGER", "label": "计费字符数"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- MiniMax Voice Clone schema
('00000000-0000-0000-2003-000000000051', '00000000-0000-0000-0003-000000000051',
 '[{"name": "source_audio", "type": "AUDIO", "group": "basic", "label": "源音频", "required": true, "fileConfig": {"maxSize": 20971520, "accept": ".mp3,.m4a,.wav"}, "description": "要克隆的音频（10秒~5分钟，≤20MB）"}, {"name": "voice_id", "type": "STRING", "group": "basic", "label": "自定义音色ID", "required": false, "description": "自定义音色标识，留空自动生成"}, {"name": "preview_text", "type": "TEXTAREA", "group": "basic", "label": "预览文本", "required": false, "description": "用于生成预览音频的文本"}, {"name": "prompt_audio", "type": "AUDIO", "group": "advanced", "label": "示例音频（可选）", "required": false, "fileConfig": {"maxSize": 20971520, "accept": ".mp3,.m4a,.wav"}, "description": "增强克隆质量的示例音频（<8秒）"}, {"name": "prompt_text", "type": "STRING", "group": "advanced", "label": "示例文本", "required": false, "description": "示例音频对应的文本"}, {"enum": ["speech-2.8-hd", "speech-2.8-turbo"], "name": "model", "type": "STRING", "group": "advanced", "label": "模型", "default": "speech-2.8-hd", "required": false}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "advanced", "label": "高级参数", "order": 2, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "voice_id", "type": "STRING", "label": "克隆音色ID"}, {"name": "preview_audio_url", "type": "STRING", "label": "预览音频URL"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

-- MiniMax Music Generation schema
('00000000-0000-0000-2003-000000000052', '00000000-0000-0000-0003-000000000052',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "音乐描述", "required": true, "description": "描述音乐的风格、情绪和场景，如 Soulful Blues, Rainy Night, Male Vocals"}, {"name": "lyrics", "type": "TEXTAREA", "group": "basic", "label": "歌词", "required": false, "description": "歌词内容，支持 [Verse] [Chorus] [Bridge] 等结构标记"}, {"name": "is_instrumental", "type": "BOOLEAN", "group": "basic", "label": "纯音乐", "default": false, "required": false, "description": "是否生成纯音乐（无人声）"}, {"name": "lyrics_optimizer", "type": "BOOLEAN", "group": "advanced", "label": "歌词优化", "default": false, "required": false, "description": "无歌词时自动生成歌词"}, {"enum": ["music-2.6", "music-2.5"], "name": "model", "type": "STRING", "group": "advanced", "label": "模型", "default": "music-2.6", "required": false, "description": "音乐生成模型版本"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "advanced", "label": "高级参数", "order": 2, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "audio_url", "type": "STRING", "label": "音乐URL"}, {"name": "music_duration", "type": "INTEGER", "label": "音乐时长(ms)"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-2003-000000000022', '00000000-0000-0000-0003-000000000022',
 '[{"name": "prompt", "type": "STRING", "group": "basic", "label": "提示词", "required": true, "description": "视频描述文本，建议不超过500字"}, {"name": "first_frame", "type": "IMAGE", "group": "image", "label": "首帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "首帧图片"}, {"name": "last_frame", "type": "IMAGE", "group": "image", "label": "尾帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "尾帧图片（需配合首帧使用）"}, {"enum": ["720p", "1080p"], "name": "resolution", "type": "STRING", "group": "basic", "label": "分辨率", "default": "1080p", "required": false, "description": "视频分辨率", "defaultValue": "1080p"}, {"enum": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"], "name": "ratio", "type": "STRING", "group": "basic", "label": "宽高比", "default": "adaptive", "required": false, "description": "视频尺寸"}, {"max": 12, "min": 2, "name": "duration", "type": "INTEGER", "group": "basic", "label": "时长(秒)", "default": 5, "required": false, "validation": {"min": 2}, "description": "视频时长"}, {"name": "camera_fixed", "type": "BOOLEAN", "group": "advanced", "label": "固定镜头", "default": false, "required": false, "description": "固定镜头"}, {"max": 4294967294, "min": -1, "name": "seed", "type": "NUMBER", "group": "advanced", "label": "随机种子", "order": 8, "component": "input", "validation": {"max": 4294967294, "min": -1}, "description": "相同的请求下，模型收到相同的seed值，会生成类似的结果，但不保证完全一致"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced"}]',
 '[]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "last_frame_url", "type": "STRING", "label": "尾帧图片URL"}, {"name": "duration", "type": "INTEGER", "label": "实际时长"}, {"name": "frames", "type": "INTEGER", "label": "实际帧数"}, {"name": "resolution", "type": "STRING", "label": "实际分辨率"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('019c399f-72f2-f80e-2e49-a3763928fd1e', '019c399f-72f2-f80e-9e49-a3763928fd1e',
 '[{"name": "prompt", "type": "STRING", "group": "basic", "label": "提示词", "required": true, "description": "视频描述文本，建议不超过500字"}, {"name": "first_frame", "type": "IMAGE", "group": "image", "label": "首帧图片", "required": false, "fileConfig": {"maxSize": 31457280}, "description": "首帧图片"}, {"enum": ["720p", "1080p"], "name": "resolution", "type": "STRING", "group": "basic", "label": "分辨率", "default": "1080p", "required": false, "description": "视频分辨率", "defaultValue": "1080p"}, {"enum": ["16:9", "4:3", "1:1", "3:4", "9:16", "21:9", "adaptive"], "name": "ratio", "type": "STRING", "group": "basic", "label": "宽高比", "default": "adaptive", "required": false, "description": "视频尺寸"}, {"max": 12, "min": 2, "name": "duration", "type": "INTEGER", "group": "basic", "label": "时长(秒)", "default": 5, "required": false, "validation": {"min": 2}, "description": "视频时长"}, {"name": "camera_fixed", "type": "BOOLEAN", "group": "advanced", "label": "固定镜头", "default": false, "required": false, "description": "固定镜头"}, {"max": 4294967294, "min": -1, "name": "seed", "type": "NUMBER", "group": "advanced", "label": "随机种子", "order": 8, "component": "input", "validation": {"max": 4294967294, "min": -1}, "description": "随机种子"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced"}]',
 '[]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "last_frame_url", "type": "STRING", "label": "尾帧图片URL"}, {"name": "duration", "type": "INTEGER", "label": "实际时长"}, {"name": "frames", "type": "INTEGER", "label": "实际帧数"}, {"name": "resolution", "type": "STRING", "label": "实际分辨率"}]',
 '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-2003-000000000031', '00000000-0000-0000-0003-000000000031',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "order": 1, "required": true, "placeholder": "描述你想要生成的图片...", "description": "生成图片的详细描述，长度限制 1-8192 字符"}, {"name": "negativePrompt", "type": "TEXTAREA", "group": "basic", "label": "反向提示词", "order": 2, "required": false, "placeholder": "不希望出现的元素...", "description": "排除不想要的内容"}, {"name": "aspectRatio", "type": "SELECT", "group": "basic", "label": "宽高比", "order": 3, "default": "1:1", "options": [{"label": "1:1", "value": "1:1"}, {"label": "4:3", "value": "4:3"}, {"label": "3:2", "value": "3:2"}, {"label": "16:9", "value": "16:9"}, {"label": "3:4", "value": "3:4"}, {"label": "2:3", "value": "2:3"}, {"label": "9:16", "value": "9:16"}], "required": false, "defaultValue": "1:1"}, {"name": "quality", "type": "SELECT", "group": "basic", "label": "质量", "order": 4, "options": [{"label": "标准", "value": "1"}, {"label": "高", "value": "2"}, {"label": "超高", "value": "4"}], "required": false}, {"name": "stylize", "type": "NUMBER", "group": "advanced", "label": "风格化", "order": 5, "required": false, "validation": {"min": 0, "max": 1000}, "defaultValue": 0, "description": "风格化强度 (0-1000)"}, {"name": "chaos", "type": "NUMBER", "group": "advanced", "label": "多样性", "order": 6, "required": false, "validation": {"min": 0, "max": 100}, "defaultValue": 0, "description": "结果多样性 (0-100)"}, {"name": "weird", "type": "NUMBER", "group": "advanced", "label": "独特性", "order": 7, "required": false, "validation": {"min": 0, "max": 3000}, "defaultValue": 0, "description": "独特美学探索 (0-3000)"}, {"name": "raw", "type": "BOOLEAN", "group": "advanced", "label": "原始模式", "order": 8, "required": false, "defaultValue": false, "description": "减少自动美化，更忠实于提示词"}, {"name": "imageUrl", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "order": 9, "required": false, "fileConfig": {"maxCount": 1, "maxSize": 20971520, "inputFormat": "URL"}, "description": "参考图片 (最多1张, 20MB)"}, {"name": "iw", "type": "NUMBER", "group": "image", "label": "图片权重", "order": 10, "required": false, "validation": {"min": 0, "max": 3}, "defaultValue": 1, "description": "参考图片权重 (0-3)"}, {"name": "sref", "type": "IMAGE_LIST", "group": "image", "label": "风格参考", "order": 11, "required": false, "fileConfig": {"maxCount": 1, "maxSize": 20971520, "inputFormat": "URL"}, "description": "风格参考图片 (最多1张)"}, {"name": "sw", "type": "NUMBER", "group": "image", "label": "风格权重", "order": 12, "required": false, "validation": {"min": 0, "max": 1000}, "defaultValue": 100, "description": "风格参考权重 (0-1000)"}, {"name": "sv", "type": "NUMBER", "group": "image", "label": "风格变化", "order": 13, "required": false, "validation": {"min": 1, "max": 6}, "defaultValue": 4, "description": "风格变化程度 (1-6)"}, {"name": "oref", "type": "IMAGE_LIST", "group": "image", "label": "对象参考", "order": 14, "required": false, "fileConfig": {"maxCount": 1, "maxSize": 20971520, "inputFormat": "URL"}, "description": "对象参考图片 (最多1张)"}, {"name": "ow", "type": "NUMBER", "group": "image", "label": "对象权重", "order": 15, "required": false, "validation": {"min": 1, "max": 1000}, "defaultValue": 100, "description": "对象参考权重 (1-1000)"}, {"name": "tile", "type": "BOOLEAN", "group": "advanced", "label": "平铺模式", "order": 16, "required": false, "defaultValue": false, "description": "生成可无缝平铺的图案"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image", "collapsed": true}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "image_url", "type": "STRING", "label": "图片URL"}, {"name": "output_type", "type": "STRING", "label": "输出格式"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-2003-000000000032', '00000000-0000-0000-0003-000000000032',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "order": 1, "required": true, "placeholder": "描述你想要生成的动漫风格图片...", "description": "生成图片的详细描述，适合动漫、插画风格，长度限制 1-8192 字符"}, {"name": "aspectRatio", "type": "SELECT", "group": "basic", "label": "宽高比", "order": 2, "default": "1:1", "options": [{"label": "1:1", "value": "1:1"}, {"label": "4:3", "value": "4:3"}, {"label": "3:2", "value": "3:2"}, {"label": "16:9", "value": "16:9"}, {"label": "3:4", "value": "3:4"}, {"label": "2:3", "value": "2:3"}, {"label": "9:16", "value": "9:16"}], "required": false, "defaultValue": "1:1"}, {"name": "stylize", "type": "NUMBER", "group": "advanced", "label": "风格化", "order": 3, "required": false, "validation": {"min": 0, "max": 1000}, "defaultValue": 0, "description": "风格化强度 (0-1000)"}, {"name": "chaos", "type": "NUMBER", "group": "advanced", "label": "多样性", "order": 4, "required": false, "validation": {"min": 0, "max": 100}, "defaultValue": 0, "description": "结果多样性 (0-100)"}, {"name": "weird", "type": "NUMBER", "group": "advanced", "label": "独特性", "order": 5, "required": false, "validation": {"min": 0, "max": 3000}, "defaultValue": 0, "description": "独特美学探索 (0-3000)"}, {"name": "raw", "type": "BOOLEAN", "group": "advanced", "label": "原始模式", "order": 6, "required": false, "defaultValue": false, "description": "减少自动美化，更忠实于提示词"}, {"name": "imageUrl", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "order": 7, "required": false, "fileConfig": {"maxCount": 1, "maxSize": 20971520, "inputFormat": "URL"}, "description": "参考图片 (最多1张, 20MB)"}, {"name": "iw", "type": "NUMBER", "group": "image", "label": "图片权重", "order": 8, "required": false, "validation": {"min": 0, "max": 3}, "defaultValue": 1, "description": "参考图片权重 (0-3)"}, {"name": "sref", "type": "IMAGE_LIST", "group": "image", "label": "风格参考", "order": 9, "required": false, "fileConfig": {"maxCount": 1, "maxSize": 20971520, "inputFormat": "URL"}, "description": "风格参考图片 (最多1张)"}, {"name": "sw", "type": "NUMBER", "group": "image", "label": "风格权重", "order": 10, "required": false, "validation": {"min": 0, "max": 1000}, "defaultValue": 100, "description": "风格参考权重 (0-1000)"}, {"name": "sv", "type": "NUMBER", "group": "image", "label": "风格变化", "order": 11, "required": false, "validation": {"min": 1, "max": 4}, "defaultValue": 4, "description": "风格变化程度 (1-4)"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image", "collapsed": true}, {"name": "advanced", "label": "高级参数", "order": 3, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "image_url", "type": "STRING", "label": "图片URL"}, {"name": "output_type", "type": "STRING", "label": "输出格式"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0),

('00000000-0000-0000-2003-000000000033', '00000000-0000-0000-0003-000000000033',
 '[{"name": "prompt", "type": "TEXTAREA", "group": "basic", "label": "提示词", "order": 1, "required": true, "placeholder": "描述你想要生成的视频内容...", "description": "视频描述提示词，可使用 @图N 引用参考图片"}, {"name": "duration", "type": "SELECT", "group": "basic", "label": "时长(秒)", "order": 2, "default": "4", "options": [{"label": "1秒", "value": "1"}, {"label": "2秒", "value": "2"}, {"label": "3秒", "value": "3"}, {"label": "4秒", "value": "4"}, {"label": "5秒", "value": "5"}, {"label": "6秒", "value": "6"}, {"label": "7秒", "value": "7"}, {"label": "8秒", "value": "8"}, {"label": "9秒", "value": "9"}, {"label": "10秒", "value": "10"}], "required": true, "defaultValue": "4"}, {"name": "aspectRatio", "type": "SELECT", "group": "basic", "label": "宽高比", "order": 3, "default": "16:9", "options": [{"label": "16:9", "value": "16:9"}, {"label": "9:16", "value": "9:16"}, {"label": "1:1", "value": "1:1"}], "required": false, "defaultValue": "16:9"}, {"name": "resolution", "type": "SELECT", "group": "basic", "label": "分辨率", "order": 4, "default": "720p", "options": [{"label": "720p", "value": "720p"}, {"label": "1080p", "value": "1080p"}], "required": false, "defaultValue": "720p"}, {"name": "imageUrls", "type": "IMAGE_LIST", "group": "image", "label": "参考图片", "order": 5, "required": true, "fileConfig": {"maxCount": 7, "maxSize": 52428800, "inputFormat": "URL"}, "description": "参考图片（必填，最多7张，50MB）"}, {"name": "videos", "type": "VIDEO_LIST", "group": "video", "label": "参考视频", "order": 6, "required": false, "fileConfig": {"maxCount": 2, "maxSize": 104857600, "inputFormat": "URL"}, "description": "参考视频（可选，最多2个，100MB）"}, {"name": "movementAmplitude", "type": "SELECT", "group": "advanced", "label": "运动幅度", "order": 7, "default": "auto", "options": [{"label": "自动", "value": "auto"}, {"label": "小幅", "value": "small"}, {"label": "中幅", "value": "medium"}, {"label": "大幅", "value": "large"}], "required": false, "defaultValue": "auto"}]',
 '[{"name": "basic", "label": "基础参数", "order": 1, "labelEn": "Basic"}, {"name": "image", "label": "图片输入", "order": 2, "labelEn": "Image"}, {"name": "video", "label": "视频输入", "order": 3, "labelEn": "Video"}, {"name": "advanced", "label": "高级参数", "order": 4, "labelEn": "Advanced", "collapsed": true}]',
 '[]',
 '[{"name": "video_url", "type": "STRING", "label": "视频URL"}, {"name": "output_type", "type": "STRING", "label": "输出格式"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000001', 0, 0)
ON CONFLICT (provider_id) WHERE deleted = 0 DO UPDATE SET
    input_schema = EXCLUDED.input_schema,
    input_groups = EXCLUDED.input_groups,
    exclusive_groups = EXCLUDED.exclusive_groups,
    output_schema = EXCLUDED.output_schema,
    updated_at = NOW();


-- =====================================================
-- TEXT 类型模型提供商 - 提示词生成 / 优化器
-- 通过 LlmBinding 直接调用 LLM，不走 HTTP
-- 统一按媒体类型划分：图片 / 视频 / 音频
-- =====================================================

INSERT INTO t_model_provider (
    id, name, description, plugin_id, plugin_type, provider_type,
    base_url, endpoint, http_method, auth_type, auth_config,
    api_key_ref, base_url_ref,
    supported_modes, callback_config, polling_config,
    credit_cost, rate_limit, timeout, max_retries,
    icon_url, priority, enabled, custom_headers,
    text_config,
    created_by, updated_by, deleted, version
) VALUES
-- =====================================================
-- T1. 图片提示词生成 / 优化 (Gemini)
-- =====================================================
('00000000-0000-0000-0004-000000000001', '图片提示词生成/优化 (Gemini)', '基于实体、风格和用户自定义输入，通过 Gemini 生成或优化图片提示词，支持多宫格布局', 'image-prompt-gemini', 'GROOVY', 'TEXT',
 '', '', 'POST', 'NONE', '{}',
 NULL, NULL,
 '["BLOCKING","STREAMING"]'::jsonb, '{}', '{}',
 1, 120, 60000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/gemini.svg', 200, TRUE, '{}',
 jsonb_build_object('llmProviderId', 'llm-google-gemini-2.5-flash'::text, 'systemPrompt', to_jsonb($SYS$你是专业的 AI 图片提示词生成与优化助手。

## 目标
- 基于实体信息、风格信息、宫格数量和用户自定义输入，输出可直接用于主流图片模型的高质量提示词。
- 如果用户提供了已有提示词或补充要求，需要在保留核心意图的前提下做优化、补全和结构化。
- 如果只提供实体与风格信息，则直接生成完整提示词。

## 输出要求
- 输出语言严格跟随 i18n。
- 只输出 JSON，不要输出解释、标题、Markdown 或额外说明。
- 宫格数量大于 1 时，必须明确描述 multi-panel / grid layout / panel consistency / panel breakdown。
- 宫格数量为 1 时，不要强行加入多宫格描述。

## 提示词偏好
- 优先覆盖：主体、动作、构图、镜头视角、光照、环境、材质、色彩、风格、质量描述。
- 对角色/场景/道具/分镜信息要忠实，不要编造与上下文冲突的设定。$SYS$::text), 'responseSchema', '{"type":"object","properties":{"prompt":{"type":"string","description":"Generated or optimized image prompt"},"i18n":{"type":"string","enum":["en","zh"]},"promptType":{"type":"string"},"mediaType":{"type":"string","enum":["IMAGE"]},"entityType":{"type":"string"},"entityIds":{"type":"array","items":{"type":"string"}}},"required":["prompt"]}'::jsonb, 'multimodalConfig', 'null'::jsonb),
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- =====================================================
-- T2. 视频提示词生成 / 优化 (Gemini)
-- =====================================================
('00000000-0000-0000-0004-000000000003', '视频提示词生成/优化 (Gemini)', '基于实体、风格和用户自定义输入，通过 Gemini 生成或优化视频提示词，支持多镜头/多段结构', 'video-prompt-gemini', 'GROOVY', 'TEXT',
 '', '', 'POST', 'NONE', '{}',
 NULL, NULL,
 '["BLOCKING","STREAMING"]'::jsonb, '{}', '{}',
 2, 120, 90000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/gemini.svg', 195, TRUE, '{}',
 jsonb_build_object('llmProviderId', 'llm-google-gemini-2.5-flash'::text, 'systemPrompt', to_jsonb($SYS$你是专业的 AI 视频提示词生成与优化助手。

## 目标
- 基于实体信息、风格信息、宫格数量和用户自定义输入，输出可直接用于主流视频模型的高质量提示词。
- 如果用户提供了已有提示词或补充要求，需要在保留核心意图的前提下做优化、补全和结构化。
- 如果只提供实体与风格信息，则直接生成完整提示词。

## 输出要求
- 输出语言严格跟随 i18n。
- 只输出 JSON，不要输出解释、标题、Markdown 或额外说明。
- 宫格数量可视为镜头数、关键段落数或分镜段数；当数量大于 1 时，必须体现 shot breakdown、continuity、camera transition、temporal consistency。
- 当数量为 1 时，输出单段完整视频提示词。

## 提示词偏好
- 优先覆盖：主体、动作轨迹、镜头语言、镜头运动、场景变化、光照氛围、节奏、时间连续性、风格与质感。
- 对角色/场景/道具/分镜信息要忠实，不要编造与上下文冲突的设定。$SYS$::text), 'responseSchema', '{"type":"object","properties":{"prompt":{"type":"string","description":"Generated or optimized video prompt"},"i18n":{"type":"string","enum":["en","zh"]},"promptType":{"type":"string"},"mediaType":{"type":"string","enum":["VIDEO"]},"entityType":{"type":"string"},"entityIds":{"type":"array","items":{"type":"string"}}},"required":["prompt"]}'::jsonb, 'multimodalConfig', 'null'::jsonb),
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- =====================================================
-- T3. 音频提示词生成 / 优化 (Gemini)
-- =====================================================
('00000000-0000-0000-0004-000000000005', '音频提示词生成/优化 (Gemini)', '基于实体、风格和用户自定义输入，通过 Gemini 生成或优化音频提示词，支持多段结构', 'audio-prompt-gemini', 'GROOVY', 'TEXT',
 '', '', 'POST', 'NONE', '{}',
 NULL, NULL,
 '["BLOCKING","STREAMING"]'::jsonb, '{}', '{}',
 1, 120, 60000, 2,
 'https://actionow.tos-cn-guangzhou.volces.com/brand/gemini.svg', 190, TRUE, '{}',
 jsonb_build_object('llmProviderId', 'llm-google-gemini-2.5-flash'::text, 'systemPrompt', to_jsonb($SYS$你是专业的 AI 音频提示词生成与优化助手。

## 目标
- 基于实体信息、风格信息、宫格数量和用户自定义输入，输出可直接用于主流音频模型的高质量提示词。
- 如果用户提供了已有提示词或补充要求，需要在保留核心意图的前提下做优化、补全和结构化。
- 如果只提供实体与风格信息，则直接生成完整提示词。

## 输出要求
- 输出语言严格跟随 i18n。
- 只输出 JSON，不要输出解释、标题、Markdown 或额外说明。
- 宫格数量可视为音频段落数、章节数或声音层次数；当数量大于 1 时，必须体现 segment breakdown、layering、transition、continuity。
- 当数量为 1 时，输出单段完整音频提示词。

## 提示词偏好
- 优先覆盖：声音主体、环境氛围、情绪、节奏、音色、乐器或声源、空间感、动态变化、层次关系。
- 对角色/场景/道具/分镜信息要忠实，不要编造与上下文冲突的设定。$SYS$::text), 'responseSchema', '{"type":"object","properties":{"prompt":{"type":"string","description":"Generated or optimized audio prompt"},"i18n":{"type":"string","enum":["en","zh"]},"promptType":{"type":"string"},"mediaType":{"type":"string","enum":["AUDIO"]},"entityType":{"type":"string"},"entityIds":{"type":"array","items":{"type":"string"}}},"required":["prompt"]}'::jsonb, 'multimodalConfig', 'null'::jsonb),
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    plugin_id = EXCLUDED.plugin_id,
    plugin_type = EXCLUDED.plugin_type,
    provider_type = EXCLUDED.provider_type,
    base_url = EXCLUDED.base_url,
    endpoint = EXCLUDED.endpoint,
    http_method = EXCLUDED.http_method,
    auth_type = EXCLUDED.auth_type,
    auth_config = EXCLUDED.auth_config,
    api_key_ref = EXCLUDED.api_key_ref,
    base_url_ref = EXCLUDED.base_url_ref,
    supported_modes = EXCLUDED.supported_modes,
    callback_config = EXCLUDED.callback_config,
    polling_config = EXCLUDED.polling_config,
    credit_cost = EXCLUDED.credit_cost,
    rate_limit = EXCLUDED.rate_limit,
    timeout = EXCLUDED.timeout,
    max_retries = EXCLUDED.max_retries,
    icon_url = EXCLUDED.icon_url,
    priority = EXCLUDED.priority,
    enabled = EXCLUDED.enabled,
    custom_headers = EXCLUDED.custom_headers,
    text_config = EXCLUDED.text_config,
    updated_at = NOW();

INSERT INTO t_model_provider_script (
    id, provider_id,
    request_builder_script, response_mapper_script, custom_logic_script,
    pricing_rules, pricing_script,
    created_by, updated_by, deleted, version
) VALUES

('00000000-0000-0000-1004-000000000001', '00000000-0000-0000-0004-000000000001',
$REQUEST$
// 图片提示词生成/优化 - 按 entityType/entityId 统一查询实体并调用 LLM
def firstText = { Object... values ->
    values.find { it != null && it.toString().trim() }?.toString()?.trim()
}

def normalizeEntityType = { value ->
    def raw = firstText(value)
    if (!raw) return null
    def upper = raw.toUpperCase()
    if (upper in ["CHARACTER", "ROLE", "CHAR"]) return "CHARACTER"
    if (upper in ["SCENE", "ENVIRONMENT"]) return "SCENE"
    if (upper in ["PROP", "ITEM", "OBJECT"]) return "PROP"
    if (upper in ["STORYBOARD", "SHOT", "PANEL"]) return "STORYBOARD"
    if (raw == "角色") return "CHARACTER"
    if (raw == "场景") return "SCENE"
    if (raw == "道具") return "PROP"
    if (raw == "分镜") return "STORYBOARD"
    return upper
}

def parseGridCount = { value ->
    def raw = firstText(value)
    if (!raw) return null
    if (raw ==~ /\d+/) return Integer.parseInt(raw)
    def matcher = raw =~ /(?i)^\s*(\d+)\s*x\s*(\d+)\s*$/
    if (matcher.matches()) {
        return Integer.parseInt(matcher[0][1]) * Integer.parseInt(matcher[0][2])
    }
    return null
}

def toIdList = { value ->
    if (!value) return []
    if (value instanceof Collection) {
        return value.findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }
    }
    return [value.toString().trim()]
}

def inferEntityBinding = {
    def aliases = [
        CHARACTER: firstText(inputs.characterId),
        SCENE: firstText(inputs.sceneId),
        PROP: firstText(inputs.propId),
        STORYBOARD: firstText(inputs.storyboardId)
    ]
    def matched = aliases.find { it.value }
    return matched ? [entityType: matched.key, entityId: matched.value] : [:]
}

def loadEntityContext = { entityType, entityId ->
    def normalized = normalizeEntityType(entityType)
    def entity
    def entityName
    def entityDesc
    def entityLines = []
    def entityIds = new LinkedHashSet([entityId])

    switch (normalized) {
        case "CHARACTER":
            entity = db.getCharacter(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("角色不存在: " + entityId)
            entityName = firstText(entity.name, "未知角色")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：角色",
                "角色名称：${entityName}",
                "角色描述：${entityDesc}"
            ])
            break
        case "SCENE":
            entity = db.getScene(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("场景不存在: " + entityId)
            entityName = firstText(entity.name, "未知场景")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            def envInfo = [firstText(entity.timeOfDay), firstText(entity.weather), firstText(entity.mood)].findAll { it }
            entityLines.addAll([
                "实体类型：场景",
                "场景名称：${entityName}",
                "场景描述：${entityDesc}",
                envInfo ? "环境氛围：${envInfo.join('、')}" : ""
            ].findAll { it })
            break
        case "PROP":
            entity = db.getProp(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("道具不存在: " + entityId)
            entityName = firstText(entity.name, "未知道具")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：道具",
                "道具名称：${entityName}",
                "道具描述：${entityDesc}",
                firstText(entity.material) ? "材质：${firstText(entity.material)}" : "",
                firstText(entity.category) ? "类别：${firstText(entity.category)}" : ""
            ].findAll { it })
            break
        case "STORYBOARD":
            entity = db.getStoryboard(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("分镜不存在: " + entityId)
            entityName = firstText(entity.name, "未知分镜")
            entityDesc = firstText(entity.fixedDesc, entity.description, entity.dialogueText, entity.dialogue_text, "无描述")
            entityLines.addAll([
                "实体类型：分镜",
                "分镜名称：${entityName}",
                "分镜描述：${entityDesc}",
                firstText(entity.actionDescription, entity.action_description) ? "动作描述：${firstText(entity.actionDescription, entity.action_description)}" : "",
                firstText(entity.cameraAngle, entity.camera_angle, entity.camera) ? "镜头信息：${firstText(entity.cameraAngle, entity.camera_angle, entity.camera)}" : ""
            ].findAll { it })

            toIdList(entity.characterIds ?: entity.character_ids).each { cid ->
                def character = db.getCharacter(cid)
                if (character && !character.isEmpty()) {
                    def desc = firstText(character.fixedDesc, character.description)
                    if (desc) entityLines.add("关联角色「${firstText(character.name, cid)}」：${desc}")
                    entityIds.add(cid)
                }
            }

            def sceneId = firstText(entity.sceneId, entity.scene_id)
            if (sceneId) {
                def scene = db.getScene(sceneId)
                if (scene && !scene.isEmpty()) {
                    def desc = firstText(scene.fixedDesc, scene.description)
                    if (desc) entityLines.add("关联场景「${firstText(scene.name, sceneId)}」：${desc}")
                    entityIds.add(sceneId)
                }
            }

            toIdList(entity.propIds ?: entity.prop_ids).each { pid ->
                def prop = db.getProp(pid)
                if (prop && !prop.isEmpty()) {
                    def desc = firstText(prop.fixedDesc, prop.description)
                    if (desc) entityLines.add("关联道具「${firstText(prop.name, pid)}」：${desc}")
                    entityIds.add(pid)
                }
            }
            break
        default:
            throw new IllegalArgumentException("不支持的 entityType: " + entityType)
    }

    return [
        entityType: normalized,
        entityName: entityName,
        entityDesc: entityDesc,
        entityLines: entityLines.findAll { it },
        entityIds: entityIds as List
    ]
}

def inferred = inferEntityBinding()
def entityType = normalizeEntityType(firstText(inputs.entityType, inferred.entityType))
def entityId = firstText(inputs.entityId, inferred.entityId)

def i18n = firstText(inputs.i18n, "en").toLowerCase()
if (!(i18n in ["en", "zh"])) i18n = "en"

def gridCount = parseGridCount(inputs.gridCount)
if (gridCount == null || gridCount < 1) gridCount = 1

// 灵感模式：无实体绑定时跳过实体加载
def context = null
def entityIds = new LinkedHashSet()
if (entityType && entityId) {
    context = loadEntityContext(entityType, entityId)
    entityIds.addAll(context.entityIds ?: [entityId])
}

def styleLines = []
def styleId = firstText(inputs.styleId)
if (styleId) {
    def style = db.getStyle(styleId)
    if (style && !style.isEmpty()) {
        def styleDesc = firstText(style.fixedDesc, style.description)
        styleLines.addAll([
            firstText(style.name) ? "风格名称：${firstText(style.name)}" : "",
            styleDesc ? "风格描述：${styleDesc}" : ""
        ].findAll { it })
        entityIds.add(styleId)
    }
}

def customInput = firstText(inputs.customInput, inputs.prompt, "")
def langNote = i18n == "zh" ? "中文" : "English"
def operation = customInput ? "优化并补全图片提示词" : "生成图片提示词"

def contextBlock = ""
if (context) {
    contextBlock = """实体类型：${context.entityType}

实体上下文：
${context.entityLines.join("\n")}

"""
}

def userPrompt = """请基于以下上下文${operation}，输出最终可直接用于图片模型的提示词：

媒体类型：IMAGE
输出语言：${langNote}
宫格数量：${gridCount}
${contextBlock}${styleLines ? "风格上下文：\n" + styleLines.join("\n") + "\n\n" : ""}用户自定义输入：
${customInput ?: "无"}

要求：
1. 只输出一个最终图片提示词。
2. 宫格数量大于 1 时，必须体现 multi-panel / grid layout / panel consistency / panel breakdown。
${context ? "3. 不能丢失实体核心设定和风格要点。" : "3. 基于用户输入的主题/描述，创造性地生成高质量提示词。"}
4. 不要输出 JSON 之外的任何说明。"""

def result = llm.chatStructured(userPrompt)
if (!result.success) {
    return [outputType: "TEXT", status: "FAILED", error: result.error]
}

def data = result.data ?: [:]
def prompt = firstText(data.prompt, result.content)
if (!prompt) {
    return [outputType: "TEXT", status: "FAILED", error: "LLM 未返回图片提示词"]
}

return [
    outputType: "TEXT",
    status: "SUCCEEDED",
    text: [content: prompt],
    metadata: [
        i18n: data.i18n ?: i18n,
        promptType: data.promptType ?: (customInput ? "OPTIMIZE" : "GENERATE"),
        mediaType: data.mediaType ?: "IMAGE",
        entityType: data.entityType ?: context?.entityType,
        entityId: entityId,
        entityIds: data.entityIds ?: (entityIds as List),
        styleId: styleId,
        gridCount: gridCount,
        mode: context ? "ENTITY" : "INSPIRATION",
        model: result.model,
        provider: result.provider,
        usage: result.usage
    ]
]
$REQUEST$,
 '', '',
 '{"baseCredits": 1, "minCredits": 1, "maxCredits": 5}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

('00000000-0000-0000-1004-000000000003', '00000000-0000-0000-0004-000000000003',
$REQUEST$
// 视频提示词生成/优化 - 按 entityType/entityId 统一查询实体并调用 LLM
def firstText = { Object... values ->
    values.find { it != null && it.toString().trim() }?.toString()?.trim()
}

def normalizeEntityType = { value ->
    def raw = firstText(value)
    if (!raw) return null
    def upper = raw.toUpperCase()
    if (upper in ["CHARACTER", "ROLE", "CHAR"]) return "CHARACTER"
    if (upper in ["SCENE", "ENVIRONMENT"]) return "SCENE"
    if (upper in ["PROP", "ITEM", "OBJECT"]) return "PROP"
    if (upper in ["STORYBOARD", "SHOT", "PANEL"]) return "STORYBOARD"
    if (raw == "角色") return "CHARACTER"
    if (raw == "场景") return "SCENE"
    if (raw == "道具") return "PROP"
    if (raw == "分镜") return "STORYBOARD"
    return upper
}

def parseGridCount = { value ->
    def raw = firstText(value)
    if (!raw) return null
    if (raw ==~ /\d+/) return Integer.parseInt(raw)
    def matcher = raw =~ /(?i)^\s*(\d+)\s*x\s*(\d+)\s*$/
    if (matcher.matches()) {
        return Integer.parseInt(matcher[0][1]) * Integer.parseInt(matcher[0][2])
    }
    return null
}

def toIdList = { value ->
    if (!value) return []
    if (value instanceof Collection) {
        return value.findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }
    }
    return [value.toString().trim()]
}

def inferEntityBinding = {
    def aliases = [
        CHARACTER: firstText(inputs.characterId),
        SCENE: firstText(inputs.sceneId),
        PROP: firstText(inputs.propId),
        STORYBOARD: firstText(inputs.storyboardId)
    ]
    def matched = aliases.find { it.value }
    return matched ? [entityType: matched.key, entityId: matched.value] : [:]
}

def loadEntityContext = { entityType, entityId ->
    def normalized = normalizeEntityType(entityType)
    def entity
    def entityName
    def entityDesc
    def entityLines = []
    def entityIds = new LinkedHashSet([entityId])

    switch (normalized) {
        case "CHARACTER":
            entity = db.getCharacter(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("角色不存在: " + entityId)
            entityName = firstText(entity.name, "未知角色")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：角色",
                "角色名称：${entityName}",
                "角色描述：${entityDesc}"
            ])
            break
        case "SCENE":
            entity = db.getScene(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("场景不存在: " + entityId)
            entityName = firstText(entity.name, "未知场景")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            def envInfo = [firstText(entity.timeOfDay), firstText(entity.weather), firstText(entity.mood)].findAll { it }
            entityLines.addAll([
                "实体类型：场景",
                "场景名称：${entityName}",
                "场景描述：${entityDesc}",
                envInfo ? "环境氛围：${envInfo.join('、')}" : ""
            ].findAll { it })
            break
        case "PROP":
            entity = db.getProp(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("道具不存在: " + entityId)
            entityName = firstText(entity.name, "未知道具")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：道具",
                "道具名称：${entityName}",
                "道具描述：${entityDesc}",
                firstText(entity.material) ? "材质：${firstText(entity.material)}" : "",
                firstText(entity.category) ? "类别：${firstText(entity.category)}" : ""
            ].findAll { it })
            break
        case "STORYBOARD":
            entity = db.getStoryboard(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("分镜不存在: " + entityId)
            entityName = firstText(entity.name, "未知分镜")
            entityDesc = firstText(entity.fixedDesc, entity.description, entity.dialogueText, entity.dialogue_text, "无描述")
            entityLines.addAll([
                "实体类型：分镜",
                "分镜名称：${entityName}",
                "分镜描述：${entityDesc}",
                firstText(entity.actionDescription, entity.action_description) ? "动作描述：${firstText(entity.actionDescription, entity.action_description)}" : "",
                firstText(entity.cameraAngle, entity.camera_angle, entity.camera) ? "镜头信息：${firstText(entity.cameraAngle, entity.camera_angle, entity.camera)}" : ""
            ].findAll { it })

            toIdList(entity.characterIds ?: entity.character_ids).each { cid ->
                def character = db.getCharacter(cid)
                if (character && !character.isEmpty()) {
                    def desc = firstText(character.fixedDesc, character.description)
                    if (desc) entityLines.add("关联角色「${firstText(character.name, cid)}」：${desc}")
                    entityIds.add(cid)
                }
            }

            def sceneId = firstText(entity.sceneId, entity.scene_id)
            if (sceneId) {
                def scene = db.getScene(sceneId)
                if (scene && !scene.isEmpty()) {
                    def desc = firstText(scene.fixedDesc, scene.description)
                    if (desc) entityLines.add("关联场景「${firstText(scene.name, sceneId)}」：${desc}")
                    entityIds.add(sceneId)
                }
            }

            toIdList(entity.propIds ?: entity.prop_ids).each { pid ->
                def prop = db.getProp(pid)
                if (prop && !prop.isEmpty()) {
                    def desc = firstText(prop.fixedDesc, prop.description)
                    if (desc) entityLines.add("关联道具「${firstText(prop.name, pid)}」：${desc}")
                    entityIds.add(pid)
                }
            }
            break
        default:
            throw new IllegalArgumentException("不支持的 entityType: " + entityType)
    }

    return [
        entityType: normalized,
        entityName: entityName,
        entityDesc: entityDesc,
        entityLines: entityLines.findAll { it },
        entityIds: entityIds as List
    ]
}

def inferred = inferEntityBinding()
def entityType = normalizeEntityType(firstText(inputs.entityType, inferred.entityType))
def entityId = firstText(inputs.entityId, inferred.entityId)

def i18n = firstText(inputs.i18n, "en").toLowerCase()
if (!(i18n in ["en", "zh"])) i18n = "en"

def gridCount = parseGridCount(inputs.gridCount)
if (gridCount == null || gridCount < 1) gridCount = 1

// 灵感模式：无实体绑定时跳过实体加载
def context = null
def entityIds = new LinkedHashSet()
if (entityType && entityId) {
    context = loadEntityContext(entityType, entityId)
    entityIds.addAll(context.entityIds ?: [entityId])
}

def styleLines = []
def styleId = firstText(inputs.styleId)
if (styleId) {
    def style = db.getStyle(styleId)
    if (style && !style.isEmpty()) {
        def styleDesc = firstText(style.fixedDesc, style.description)
        styleLines.addAll([
            firstText(style.name) ? "风格名称：${firstText(style.name)}" : "",
            styleDesc ? "风格描述：${styleDesc}" : ""
        ].findAll { it })
        entityIds.add(styleId)
    }
}

def customInput = firstText(inputs.customInput, inputs.prompt, "")
def langNote = i18n == "zh" ? "中文" : "English"
def operation = customInput ? "优化并补全视频提示词" : "生成视频提示词"

def contextBlock = ""
if (context) {
    contextBlock = """实体类型：${context.entityType}

实体上下文：
${context.entityLines.join("\n")}

"""
}

def userPrompt = """请基于以下上下文${operation}，输出最终可直接用于视频模型的提示词：

媒体类型：VIDEO
输出语言：${langNote}
段落/镜头数量：${gridCount}
${contextBlock}${styleLines ? "风格上下文：\n" + styleLines.join("\n") + "\n\n" : ""}用户自定义输入：
${customInput ?: "无"}

要求：
1. 只输出一个最终视频提示词。
2. 当段落/镜头数量大于 1 时，必须体现 shot breakdown、camera transition、temporal consistency、subject continuity。
${context ? "3. 重点描述主体动作、镜头运动、节奏、光照、时长感和风格质感，不能丢失实体核心设定。" : "3. 基于用户输入的主题/描述，创造性地生成高质量视频提示词。"}
4. 不要输出 JSON 之外的任何说明。"""

def result = llm.chatStructured(userPrompt)
if (!result.success) {
    return [outputType: "TEXT", status: "FAILED", error: result.error]
}

def data = result.data ?: [:]
def prompt = firstText(data.prompt, result.content)
if (!prompt) {
    return [outputType: "TEXT", status: "FAILED", error: "LLM 未返回视频提示词"]
}

return [
    outputType: "TEXT",
    status: "SUCCEEDED",
    text: [content: prompt],
    metadata: [
        i18n: data.i18n ?: i18n,
        promptType: data.promptType ?: (customInput ? "OPTIMIZE" : "GENERATE"),
        mediaType: data.mediaType ?: "VIDEO",
        entityType: data.entityType ?: context?.entityType,
        entityId: entityId,
        entityIds: data.entityIds ?: (entityIds as List),
        styleId: styleId,
        gridCount: gridCount,
        mode: context ? "ENTITY" : "INSPIRATION",
        model: result.model,
        provider: result.provider,
        usage: result.usage
    ]
]
$REQUEST$,
 '', '',
 '{"baseCredits": 2, "minCredits": 1, "maxCredits": 6}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

('00000000-0000-0000-1004-000000000005', '00000000-0000-0000-0004-000000000005',
$REQUEST$
// 音频提示词生成/优化 - 按 entityType/entityId 统一查询实体并调用 LLM
def firstText = { Object... values ->
    values.find { it != null && it.toString().trim() }?.toString()?.trim()
}

def normalizeEntityType = { value ->
    def raw = firstText(value)
    if (!raw) return null
    def upper = raw.toUpperCase()
    if (upper in ["CHARACTER", "ROLE", "CHAR"]) return "CHARACTER"
    if (upper in ["SCENE", "ENVIRONMENT"]) return "SCENE"
    if (upper in ["PROP", "ITEM", "OBJECT"]) return "PROP"
    if (upper in ["STORYBOARD", "SHOT", "PANEL"]) return "STORYBOARD"
    if (raw == "角色") return "CHARACTER"
    if (raw == "场景") return "SCENE"
    if (raw == "道具") return "PROP"
    if (raw == "分镜") return "STORYBOARD"
    return upper
}

def parseGridCount = { value ->
    def raw = firstText(value)
    if (!raw) return null
    if (raw ==~ /\d+/) return Integer.parseInt(raw)
    def matcher = raw =~ /(?i)^\s*(\d+)\s*x\s*(\d+)\s*$/
    if (matcher.matches()) {
        return Integer.parseInt(matcher[0][1]) * Integer.parseInt(matcher[0][2])
    }
    return null
}

def toIdList = { value ->
    if (!value) return []
    if (value instanceof Collection) {
        return value.findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }
    }
    return [value.toString().trim()]
}

def inferEntityBinding = {
    def aliases = [
        CHARACTER: firstText(inputs.characterId),
        SCENE: firstText(inputs.sceneId),
        PROP: firstText(inputs.propId),
        STORYBOARD: firstText(inputs.storyboardId)
    ]
    def matched = aliases.find { it.value }
    return matched ? [entityType: matched.key, entityId: matched.value] : [:]
}

def loadEntityContext = { entityType, entityId ->
    def normalized = normalizeEntityType(entityType)
    def entity
    def entityName
    def entityDesc
    def entityLines = []
    def entityIds = new LinkedHashSet([entityId])

    switch (normalized) {
        case "CHARACTER":
            entity = db.getCharacter(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("角色不存在: " + entityId)
            entityName = firstText(entity.name, "未知角色")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：角色",
                "角色名称：${entityName}",
                "角色描述：${entityDesc}"
            ])
            break
        case "SCENE":
            entity = db.getScene(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("场景不存在: " + entityId)
            entityName = firstText(entity.name, "未知场景")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            def envInfo = [firstText(entity.timeOfDay), firstText(entity.weather), firstText(entity.mood)].findAll { it }
            entityLines.addAll([
                "实体类型：场景",
                "场景名称：${entityName}",
                "场景描述：${entityDesc}",
                envInfo ? "环境氛围：${envInfo.join('、')}" : ""
            ].findAll { it })
            break
        case "PROP":
            entity = db.getProp(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("道具不存在: " + entityId)
            entityName = firstText(entity.name, "未知道具")
            entityDesc = firstText(entity.fixedDesc, entity.description, "无描述")
            entityLines.addAll([
                "实体类型：道具",
                "道具名称：${entityName}",
                "道具描述：${entityDesc}",
                firstText(entity.material) ? "材质：${firstText(entity.material)}" : "",
                firstText(entity.category) ? "类别：${firstText(entity.category)}" : ""
            ].findAll { it })
            break
        case "STORYBOARD":
            entity = db.getStoryboard(entityId)
            if (!entity || entity.isEmpty()) throw new IllegalArgumentException("分镜不存在: " + entityId)
            entityName = firstText(entity.name, "未知分镜")
            entityDesc = firstText(entity.fixedDesc, entity.description, entity.dialogueText, entity.dialogue_text, "无描述")
            entityLines.addAll([
                "实体类型：分镜",
                "分镜名称：${entityName}",
                "分镜描述：${entityDesc}",
                firstText(entity.actionDescription, entity.action_description) ? "动作描述：${firstText(entity.actionDescription, entity.action_description)}" : "",
                firstText(entity.sound, entity.audioDesc, entity.audio_desc, entity.bgm, entity.music) ? "声音信息：${firstText(entity.sound, entity.audioDesc, entity.audio_desc, entity.bgm, entity.music)}" : ""
            ].findAll { it })

            toIdList(entity.characterIds ?: entity.character_ids).each { cid ->
                def character = db.getCharacter(cid)
                if (character && !character.isEmpty()) {
                    def desc = firstText(character.fixedDesc, character.description)
                    if (desc) entityLines.add("关联角色「${firstText(character.name, cid)}」：${desc}")
                    entityIds.add(cid)
                }
            }

            def sceneId = firstText(entity.sceneId, entity.scene_id)
            if (sceneId) {
                def scene = db.getScene(sceneId)
                if (scene && !scene.isEmpty()) {
                    def desc = firstText(scene.fixedDesc, scene.description)
                    if (desc) entityLines.add("关联场景「${firstText(scene.name, sceneId)}」：${desc}")
                    entityIds.add(sceneId)
                }
            }

            toIdList(entity.propIds ?: entity.prop_ids).each { pid ->
                def prop = db.getProp(pid)
                if (prop && !prop.isEmpty()) {
                    def desc = firstText(prop.fixedDesc, prop.description)
                    if (desc) entityLines.add("关联道具「${firstText(prop.name, pid)}」：${desc}")
                    entityIds.add(pid)
                }
            }
            break
        default:
            throw new IllegalArgumentException("不支持的 entityType: " + entityType)
    }

    return [
        entityType: normalized,
        entityName: entityName,
        entityDesc: entityDesc,
        entityLines: entityLines.findAll { it },
        entityIds: entityIds as List
    ]
}

def inferred = inferEntityBinding()
def entityType = normalizeEntityType(firstText(inputs.entityType, inferred.entityType))
def entityId = firstText(inputs.entityId, inferred.entityId)

def i18n = firstText(inputs.i18n, "en").toLowerCase()
if (!(i18n in ["en", "zh"])) i18n = "en"

def gridCount = parseGridCount(inputs.gridCount)
if (gridCount == null || gridCount < 1) gridCount = 1

// 灵感模式：无实体绑定时跳过实体加载
def context = null
def entityIds = new LinkedHashSet()
if (entityType && entityId) {
    context = loadEntityContext(entityType, entityId)
    entityIds.addAll(context.entityIds ?: [entityId])
}

def styleLines = []
def styleId = firstText(inputs.styleId)
if (styleId) {
    def style = db.getStyle(styleId)
    if (style && !style.isEmpty()) {
        def styleDesc = firstText(style.fixedDesc, style.description)
        styleLines.addAll([
            firstText(style.name) ? "风格名称：${firstText(style.name)}" : "",
            styleDesc ? "风格描述：${styleDesc}" : ""
        ].findAll { it })
        entityIds.add(styleId)
    }
}

def customInput = firstText(inputs.customInput, inputs.prompt, "")
def langNote = i18n == "zh" ? "中文" : "English"
def operation = customInput ? "优化并补全音频提示词" : "生成音频提示词"

def contextBlock = ""
if (context) {
    contextBlock = """实体类型：${context.entityType}

实体上下文：
${context.entityLines.join("\n")}

"""
}

def userPrompt = """请基于以下上下文${operation}，输出最终可直接用于音频模型的提示词：

媒体类型：AUDIO
输出语言：${langNote}
段落/层次数量：${gridCount}
${contextBlock}${styleLines ? "风格上下文：\n" + styleLines.join("\n") + "\n\n" : ""}用户自定义输入：
${customInput ?: "无"}

要求：
1. 只输出一个最终音频提示词。
2. 当段落/层次数量大于 1 时，必须体现 segment breakdown、layering、transition、continuity。
${context ? "3. 重点描述声源、情绪、节奏、音色、空间感和动态变化，不能丢失实体核心设定。" : "3. 基于用户输入的主题/描述，创造性地生成高质量音频提示词。"}
4. 不要输出 JSON 之外的任何说明。"""

def result = llm.chatStructured(userPrompt)
if (!result.success) {
    return [outputType: "TEXT", status: "FAILED", error: result.error]
}

def data = result.data ?: [:]
def prompt = firstText(data.prompt, result.content)
if (!prompt) {
    return [outputType: "TEXT", status: "FAILED", error: "LLM 未返回音频提示词"]
}

return [
    outputType: "TEXT",
    status: "SUCCEEDED",
    text: [content: prompt],
    metadata: [
        i18n: data.i18n ?: i18n,
        promptType: data.promptType ?: (customInput ? "OPTIMIZE" : "GENERATE"),
        mediaType: data.mediaType ?: "AUDIO",
        entityType: data.entityType ?: context?.entityType,
        entityId: entityId,
        entityIds: data.entityIds ?: (entityIds as List),
        styleId: styleId,
        gridCount: gridCount,
        mode: context ? "ENTITY" : "INSPIRATION",
        model: result.model,
        provider: result.provider,
        usage: result.usage
    ]
]
$REQUEST$,
 '', '',
 '{"baseCredits": 1, "minCredits": 1, "maxCredits": 5}', NULL,
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0)
ON CONFLICT (provider_id) WHERE deleted = 0 DO UPDATE SET
    request_builder_script = EXCLUDED.request_builder_script,
    response_mapper_script = EXCLUDED.response_mapper_script,
    custom_logic_script = EXCLUDED.custom_logic_script,
    pricing_rules = EXCLUDED.pricing_rules,
    pricing_script = EXCLUDED.pricing_script,
    updated_at = NOW();

INSERT INTO t_model_provider_schema (
    id, provider_id,
    input_schema, input_groups, exclusive_groups, output_schema,
    created_by, updated_by, deleted, version
) VALUES

('00000000-0000-0000-2004-000000000001', '00000000-0000-0000-0004-000000000001',
 '[{"name":"entityType","type":"SELECT","group":"basic","label":"实体类型","labelEn":"Entity Type","order":1,"required":false,"description":"作为提示词上下文的实体类型（灵感模式可不填）","defaultValue":"CHARACTER","options":[{"label":"角色","value":"CHARACTER"},{"label":"场景","value":"SCENE"},{"label":"道具","value":"PROP"},{"label":"分镜","value":"STORYBOARD"}]},{"name":"entityId","type":"TEXT","component":"EntitySelect","componentProps":{"entityTypeField":"entityType","supportedTypes":["CHARACTER","SCENE","PROP","STORYBOARD"]},"group":"basic","label":"实体ID","labelEn":"Entity ID","order":2,"required":false,"description":"需要生成或优化提示词的实体ID（灵感模式可不填）","placeholder":"请输入实体ID"},{"name":"styleId","type":"STYLE","group":"basic","label":"风格","labelEn":"Style","order":3,"required":false,"description":"可选的风格设定"},{"name":"gridCount","type":"NUMBER","group":"basic","label":"宫格数量","labelEn":"Grid Count","order":4,"required":false,"defaultValue":1,"validation":{"min":1,"max":16},"description":"图片场景表示宫格数量，多段内容可表示镜头/段落数量"},{"name":"customInput","type":"TEXTAREA","group":"basic","label":"用户自定义输入","labelEn":"Custom Input","order":5,"required":false,"description":"补充要求或待优化的原始提示词"},{"name":"i18n","type":"SELECT","group":"basic","label":"输出语言","labelEn":"Language","order":6,"required":true,"defaultValue":"en","options":[{"label":"English","value":"en"},{"label":"中文","value":"zh"}],"description":"提示词输出语言"}]',
 '[{"name":"basic","label":"基础参数","order":1,"labelEn":"Basic"}]',
 '[]',
 '[{"name":"prompt","type":"STRING","label":"生成的提示词"},{"name":"i18n","type":"STRING","label":"输出语言"},{"name":"mediaType","type":"STRING","label":"媒体类型"},{"name":"promptType","type":"STRING","label":"提示词类型"},{"name":"entityType","type":"STRING","label":"实体类型"},{"name":"entityIds","type":"ARRAY","label":"关联实体ID"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

('00000000-0000-0000-2004-000000000003', '00000000-0000-0000-0004-000000000003',
 '[{"name":"entityType","type":"SELECT","group":"basic","label":"实体类型","labelEn":"Entity Type","order":1,"required":false,"description":"作为提示词上下文的实体类型（灵感模式可不填）","defaultValue":"CHARACTER","options":[{"label":"角色","value":"CHARACTER"},{"label":"场景","value":"SCENE"},{"label":"道具","value":"PROP"},{"label":"分镜","value":"STORYBOARD"}]},{"name":"entityId","type":"TEXT","component":"EntitySelect","componentProps":{"entityTypeField":"entityType","supportedTypes":["CHARACTER","SCENE","PROP","STORYBOARD"]},"group":"basic","label":"实体ID","labelEn":"Entity ID","order":2,"required":false,"description":"需要生成或优化提示词的实体ID（灵感模式可不填）","placeholder":"请输入实体ID"},{"name":"styleId","type":"STYLE","group":"basic","label":"风格","labelEn":"Style","order":3,"required":false,"description":"可选的风格设定"},{"name":"gridCount","type":"NUMBER","group":"basic","label":"宫格数量","labelEn":"Grid Count","order":4,"required":false,"defaultValue":1,"validation":{"min":1,"max":16},"description":"视频场景可表示镜头数、关键段落数或分镜段数"},{"name":"customInput","type":"TEXTAREA","group":"basic","label":"用户自定义输入","labelEn":"Custom Input","order":5,"required":false,"description":"补充要求或待优化的原始提示词"},{"name":"i18n","type":"SELECT","group":"basic","label":"输出语言","labelEn":"Language","order":6,"required":true,"defaultValue":"en","options":[{"label":"English","value":"en"},{"label":"中文","value":"zh"}],"description":"提示词输出语言"}]',
 '[{"name":"basic","label":"基础参数","order":1,"labelEn":"Basic"}]',
 '[]',
 '[{"name":"prompt","type":"STRING","label":"生成的提示词"},{"name":"i18n","type":"STRING","label":"输出语言"},{"name":"mediaType","type":"STRING","label":"媒体类型"},{"name":"promptType","type":"STRING","label":"提示词类型"},{"name":"entityType","type":"STRING","label":"实体类型"},{"name":"entityIds","type":"ARRAY","label":"关联实体ID"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

('00000000-0000-0000-2004-000000000005', '00000000-0000-0000-0004-000000000005',
 '[{"name":"entityType","type":"SELECT","group":"basic","label":"实体类型","labelEn":"Entity Type","order":1,"required":false,"description":"作为提示词上下文的实体类型（灵感模式可不填）","defaultValue":"CHARACTER","options":[{"label":"角色","value":"CHARACTER"},{"label":"场景","value":"SCENE"},{"label":"道具","value":"PROP"},{"label":"分镜","value":"STORYBOARD"}]},{"name":"entityId","type":"TEXT","component":"EntitySelect","componentProps":{"entityTypeField":"entityType","supportedTypes":["CHARACTER","SCENE","PROP","STORYBOARD"]},"group":"basic","label":"实体ID","labelEn":"Entity ID","order":2,"required":false,"description":"需要生成或优化提示词的实体ID（灵感模式可不填）","placeholder":"请输入实体ID"},{"name":"styleId","type":"STYLE","group":"basic","label":"风格","labelEn":"Style","order":3,"required":false,"description":"可选的风格设定"},{"name":"gridCount","type":"NUMBER","group":"basic","label":"宫格数量","labelEn":"Grid Count","order":4,"required":false,"defaultValue":1,"validation":{"min":1,"max":16},"description":"音频场景可表示段落数、章节数或层次数量"},{"name":"customInput","type":"TEXTAREA","group":"basic","label":"用户自定义输入","labelEn":"Custom Input","order":5,"required":false,"description":"补充要求或待优化的原始提示词"},{"name":"i18n","type":"SELECT","group":"basic","label":"输出语言","labelEn":"Language","order":6,"required":true,"defaultValue":"en","options":[{"label":"English","value":"en"},{"label":"中文","value":"zh"}],"description":"提示词输出语言"}]',
 '[{"name":"basic","label":"基础参数","order":1,"labelEn":"Basic"}]',
 '[]',
 '[{"name":"prompt","type":"STRING","label":"生成的提示词"},{"name":"i18n","type":"STRING","label":"输出语言"},{"name":"mediaType","type":"STRING","label":"媒体类型"},{"name":"promptType","type":"STRING","label":"提示词类型"},{"name":"entityType","type":"STRING","label":"实体类型"},{"name":"entityIds","type":"ARRAY","label":"关联实体ID"}]',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0)
ON CONFLICT (provider_id) WHERE deleted = 0 DO UPDATE SET
    input_schema = EXCLUDED.input_schema,
    input_groups = EXCLUDED.input_groups,
    exclusive_groups = EXCLUDED.exclusive_groups,
    output_schema = EXCLUDED.output_schema,
    updated_at = NOW();

-- 兼容升级：移除旧的分镜专用 TEXT provider
UPDATE t_model_provider
SET deleted = 1,
    enabled = FALSE,
    updated_at = NOW()
WHERE id = '00000000-0000-0000-0004-000000000007'
  AND deleted = 0;

UPDATE t_model_provider_script
SET deleted = 1,
    updated_at = NOW()
WHERE provider_id = '00000000-0000-0000-0004-000000000007'
  AND deleted = 0;

UPDATE t_model_provider_schema
SET deleted = 1,
    updated_at = NOW()
WHERE provider_id = '00000000-0000-0000-0004-000000000007'
  AND deleted = 0;


-- =====================================================
-- AI Provider 全局系统配置 (API Key / Base URL)
-- =====================================================

INSERT INTO t_system_config (id, config_key, config_value, config_type, scope, description, value_type, sensitive, enabled, module, group_name, display_name, sort_order, created_by) VALUES
    -- OpenAI
    ('00000000-0000-0000-0002-000000000101', 'ai.provider.openai.api_key',       '',                                              'AI_PROVIDER', 'GLOBAL', 'OpenAI API Key',                  'STRING', TRUE,  TRUE, 'ai', 'provider', 'OpenAI API Key',       101, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000102', 'ai.provider.openai.base_url',      'https://api.openai.com/v1',                     'AI_PROVIDER', 'GLOBAL', 'OpenAI API Base URL',             'STRING', FALSE, TRUE, 'ai', 'provider', 'OpenAI Base URL',      102, '00000000-0000-0000-0000-000000000000'),
    -- Google (Gemini)
    ('00000000-0000-0000-0002-000000000103', 'ai.provider.google.api_key',       '',                                              'AI_PROVIDER', 'GLOBAL', 'Google AI API Key (Gemini)',      'STRING', TRUE,  TRUE, 'ai', 'provider', 'Google API Key',       103, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000112', 'ai.provider.google.base_url',      'https://generativelanguage.googleapis.com/v1beta','AI_PROVIDER', 'GLOBAL', 'Google AI API Base URL',          'STRING', FALSE, TRUE, 'ai', 'provider', 'Google Base URL',      104, '00000000-0000-0000-0000-000000000000'),
    -- Anthropic (Claude)
    ('00000000-0000-0000-0002-000000000104', 'ai.provider.anthropic.api_key',    '',                                              'AI_PROVIDER', 'GLOBAL', 'Anthropic API Key (Claude)',      'STRING', TRUE,  TRUE, 'ai', 'provider', 'Anthropic API Key',    105, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000113', 'ai.provider.anthropic.base_url',   'https://api.anthropic.com',                     'AI_PROVIDER', 'GLOBAL', 'Anthropic API Base URL',          'STRING', FALSE, TRUE, 'ai', 'provider', 'Anthropic Base URL',   106, '00000000-0000-0000-0000-000000000000'),
    -- VolcEngine (豆包)
    ('00000000-0000-0000-0002-000000000105', 'ai.provider.volcengine.api_key',   '',                                              'AI_PROVIDER', 'GLOBAL', '火山引擎 API Key (豆包)',           'STRING', TRUE,  TRUE, 'ai', 'provider', '火山引擎 API Key',     107, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000106', 'ai.provider.volcengine.base_url',  'https://ark.cn-beijing.volces.com',             'AI_PROVIDER', 'GLOBAL', '火山引擎 API Base URL',             'STRING', FALSE, TRUE, 'ai', 'provider', '火山引擎 Base URL',    108, '00000000-0000-0000-0000-000000000000'),
    -- Zhipu (GLM)
    ('00000000-0000-0000-0002-000000000107', 'ai.provider.zhipu.api_key',        '',                                              'AI_PROVIDER', 'GLOBAL', '智谱 API Key (GLM)',               'STRING', TRUE,  TRUE, 'ai', 'provider', '智谱 API Key',         109, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000114', 'ai.provider.zhipu.base_url',       'https://open.bigmodel.cn/api/paas/v4',          'AI_PROVIDER', 'GLOBAL', '智谱 API Base URL',                'STRING', FALSE, TRUE, 'ai', 'provider', '智谱 Base URL',        110, '00000000-0000-0000-0000-000000000000'),
    -- Moonshot (Kimi)
    ('00000000-0000-0000-0002-000000000108', 'ai.provider.moonshot.api_key',     '',                                              'AI_PROVIDER', 'GLOBAL', 'Moonshot API Key (Kimi)',         'STRING', TRUE,  TRUE, 'ai', 'provider', 'Moonshot API Key',     111, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000115', 'ai.provider.moonshot.base_url',    'https://api.moonshot.cn/v1',                    'AI_PROVIDER', 'GLOBAL', 'Moonshot API Base URL',           'STRING', FALSE, TRUE, 'ai', 'provider', 'Moonshot Base URL',    112, '00000000-0000-0000-0000-000000000000'),
    -- DeepSeek
    ('00000000-0000-0000-0002-000000000109', 'ai.provider.deepseek.api_key',     '',                                              'AI_PROVIDER', 'GLOBAL', 'DeepSeek API Key',                'STRING', TRUE,  TRUE, 'ai', 'provider', 'DeepSeek API Key',     113, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000110', 'ai.provider.deepseek.base_url',    'https://api.deepseek.com',                      'AI_PROVIDER', 'GLOBAL', 'DeepSeek API Base URL',           'STRING', FALSE, TRUE, 'ai', 'provider', 'DeepSeek Base URL',    114, '00000000-0000-0000-0000-000000000000'),
    -- Alibaba (通义千问 DashScope)
    ('00000000-0000-0000-0002-000000000111', 'ai.provider.alibaba.api_key',      '',                                              'AI_PROVIDER', 'GLOBAL', '阿里云百炼 API Key (通义千问)',      'STRING', TRUE,  TRUE, 'ai', 'provider', '阿里云百炼 API Key',   115, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000116', 'ai.provider.alibaba.base_url',     'https://dashscope.aliyuncs.com/compatible-mode/v1','AI_PROVIDER', 'GLOBAL', '阿里云百炼 API Base URL (OpenAI 兼容)','STRING', FALSE, TRUE, 'ai', 'provider', '阿里云百炼 Base URL',  116, '00000000-0000-0000-0000-000000000000'),
    -- MiniMax
    ('00000000-0000-0000-0002-000000000117', 'ai.provider.minimax.api_key',      '',                                              'AI_PROVIDER', 'GLOBAL', 'MiniMax API Key',                 'STRING', TRUE,  TRUE, 'ai', 'provider', 'MiniMax API Key',      117, '00000000-0000-0000-0000-000000000000'),
    ('00000000-0000-0000-0002-000000000118', 'ai.provider.minimax.base_url',     'https://api.minimax.io',                        'AI_PROVIDER', 'GLOBAL', 'MiniMax API Base URL',             'STRING', FALSE, TRUE, 'ai', 'provider', 'MiniMax Base URL',     118, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (id) DO NOTHING;

-- =====================================================
-- LLM Provider 全局配置种子数据
-- 常用 LLM 模型（Agent 对话模型）
-- =====================================================

INSERT INTO t_llm_provider (
    id, provider, model_id, model_name,
    temperature, max_output_tokens, top_p, top_k,
    api_endpoint, api_endpoint_ref, completions_path, api_key_ref,
    extra_config, context_window, max_input_tokens,
    enabled, priority, description,
    created_by, updated_by, deleted, version
) VALUES
-- OpenAI GPT-4o
('llm-openai-gpt-4o', 'OPENAI', 'gpt-4o', 'GPT-4o',
 0.7, 16384, 0.95, 40,
 NULL, 'ai.provider.openai.base_url', NULL, 'ai.provider.openai.api_key',
 '{}', 128000, 128000,
 TRUE, 100, 'OpenAI GPT-4o 多模态模型',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- OpenAI GPT-4o Mini
('llm-openai-gpt-4o-mini', 'OPENAI', 'gpt-4o-mini', 'GPT-4o Mini',
 0.7, 16384, 0.95, 40,
 NULL, 'ai.provider.openai.base_url', NULL, 'ai.provider.openai.api_key',
 '{}', 128000, 128000,
 TRUE, 90, 'OpenAI GPT-4o Mini 轻量模型',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- Google Gemini 2.5 Flash
('llm-google-gemini-2.5-flash', 'GOOGLE', 'gemini-2.5-flash-preview-05-20', 'Gemini 2.5 Flash',
 0.7, 65536, 0.95, 40,
 NULL, 'ai.provider.google.base_url', NULL, 'ai.provider.google.api_key',
 '{}', 1048576, 1048576,
 TRUE, 110, 'Google Gemini 2.5 Flash 高速模型',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- Google Gemini 2.5 Pro
('llm-google-gemini-2.5-pro', 'GOOGLE', 'gemini-2.5-pro', 'Gemini 2.5 Pro',
 0.7, 65536, 0.95, 40,
 NULL, 'ai.provider.google.base_url', NULL, 'ai.provider.google.api_key',
 '{}', 1048576, 1048576,
 TRUE, 105, 'Google Gemini 2.5 Pro 高性能模型',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- Anthropic Claude Sonnet 4
('llm-anthropic-claude-sonnet', 'ANTHROPIC', 'claude-sonnet-4-20250514', 'Claude Sonnet 4',
 0.7, 8192, 0.95, 40,
 NULL, 'ai.provider.anthropic.base_url', NULL, 'ai.provider.anthropic.api_key',
 '{}', 200000, 200000,
 TRUE, 95, 'Anthropic Claude Sonnet 4',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- DeepSeek V3 (OpenAI 兼容)
('llm-deepseek-chat', 'OPENAI', 'deepseek-chat', 'DeepSeek V3',
 0.7, 8192, 0.95, 40,
 NULL, 'ai.provider.deepseek.base_url', NULL, 'ai.provider.deepseek.api_key',
 '{}', 64000, 64000,
 TRUE, 80, 'DeepSeek V3 对话模型 (OpenAI 兼容)',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- DeepSeek R1 (OpenAI 兼容)
('llm-deepseek-reasoner', 'OPENAI', 'deepseek-reasoner', 'DeepSeek R1',
 0.7, 8192, 0.95, 40,
 NULL, 'ai.provider.deepseek.base_url', NULL, 'ai.provider.deepseek.api_key',
 '{}', 64000, 64000,
 TRUE, 75, 'DeepSeek R1 推理模型 (OpenAI 兼容)',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0),

-- 通义千问 Max (OpenAI 兼容, DashScope)
('llm-alibaba-qwen-max', 'OPENAI', 'qwen-max', '通义千问 Max',
 0.7, 8192, 0.95, 40,
 NULL, 'ai.provider.alibaba.base_url', '/chat/completions', 'ai.provider.alibaba.api_key',
 '{}', 32768, 32768,
 TRUE, 70, '阿里云通义千问 Max (DashScope OpenAI 兼容)',
 '00000000-0000-0000-0000-000000000000', '00000000-0000-0000-0000-000000000000', 0, 0)
ON CONFLICT (id) DO NOTHING;


-- =====================================================
-- 完成
-- =====================================================

DO $$
BEGIN
    RAISE NOTICE '==========================================';
    RAISE NOTICE 'Model Provider Initialization Complete!';
    RAISE NOTICE '==========================================';
    RAISE NOTICE '初始化数据:';
    RAISE NOTICE '  - AI Provider System Config: 11 (API Key / Base URL)';
    RAISE NOTICE '  - LLM Models: 8 (GPT-4o, GPT-4o Mini, Gemini 2.5 Flash/Pro, Claude Sonnet 4, DeepSeek V3/R1, Qwen Max)';
    RAISE NOTICE '  - Image Models: 5 (Seedream 4.5, 4.0, Nano Banana Pro, Midjourney V7, Midjourney Niji 7)';
    RAISE NOTICE '  - Video Models: 4 (Seedance 1.5 Pro, 1.0 Pro, 1.0 Pro Fast, Vidu Q2 Pro)';
    RAISE NOTICE '  - Text Models: 4 (提示词生成器 x4 类型, Gemini 2.5 Flash)';
    RAISE NOTICE '==========================================';
END $$;
