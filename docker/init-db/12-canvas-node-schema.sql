-- =====================================================
-- Canvas 节点表 schema 修复合集
--
-- 本脚本整合了 canvas 模块上线初期发现的两类 schema 问题，对所有
-- tenant_* schema + public 幂等执行：
--
-- 1) Schema 漂移修复（原 12-canvas-node-fix.sql）
--    问题：CanvasNode entity 声明了 node_type / content 字段，但 01-core-schema.sql
--          的 t_canvas_node 表没有这两列，且 entity_type / entity_id / layer
--          被定义为 NOT NULL；MyBatis SELECT node_type → PSQLException 整个画布挂掉。
--    修法：ADD COLUMN IF NOT EXISTS + DROP NOT NULL（兼容 freeform 节点）。
--
-- 2) 软删 unique 冲突修复（原 13-canvas-node-partial-unique.sql）
--    问题：UNIQUE (canvas_id, entity_type, entity_id) 不带 deleted 条件 →
--          软删的旧节点仍占 unique key 槽位，"删除-重建"同实体节点撞 duplicate key。
--    修法：drop 表级 unique，改成 partial unique index WHERE deleted = 0。
-- =====================================================

DO $$
DECLARE
    sch text;
BEGIN
    FOR sch IN
        SELECT schema_name
        FROM information_schema.schemata
        WHERE schema_name LIKE 'tenant_%' OR schema_name = 'public'
    LOOP
        -- 仅处理已经创建过 t_canvas_node 的 schema
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = sch AND table_name = 't_canvas_node'
        ) THEN
            CONTINUE;
        END IF;

        -- ---- (1) Schema 漂移修复 -----------------------------------------
        EXECUTE format(
            'ALTER TABLE %I.t_canvas_node ADD COLUMN IF NOT EXISTS node_type VARCHAR(50) DEFAULT ''ENTITY''',
            sch
        );
        EXECUTE format(
            'ALTER TABLE %I.t_canvas_node ADD COLUMN IF NOT EXISTS content JSONB',
            sch
        );
        -- DROP NOT NULL 在已是 NULLABLE 的列上是 no-op，幂等
        EXECUTE format('ALTER TABLE %I.t_canvas_node ALTER COLUMN entity_type DROP NOT NULL', sch);
        EXECUTE format('ALTER TABLE %I.t_canvas_node ALTER COLUMN entity_id   DROP NOT NULL', sch);
        EXECUTE format('ALTER TABLE %I.t_canvas_node ALTER COLUMN layer       DROP NOT NULL', sch);

        -- ---- (2) Unique 约束改为 partial index ---------------------------
        -- 删旧的全表 UNIQUE constraint（PostgreSQL 默认命名）
        EXECUTE format(
            'ALTER TABLE %I.t_canvas_node DROP CONSTRAINT IF EXISTS t_canvas_node_canvas_id_entity_type_entity_id_key',
            sch
        );
        -- 建部分唯一索引（仅约束 deleted = 0 的记录）
        EXECUTE format(
            'CREATE UNIQUE INDEX IF NOT EXISTS uk_t_canvas_node_canvas_entity_active
             ON %I.t_canvas_node (canvas_id, entity_type, entity_id)
             WHERE deleted = 0',
            sch
        );

        RAISE NOTICE 'canvas-node-schema migration applied to %.t_canvas_node', sch;
    END LOOP;
END$$;
