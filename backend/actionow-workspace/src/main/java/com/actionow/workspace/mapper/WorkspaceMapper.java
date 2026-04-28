package com.actionow.workspace.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.actionow.workspace.entity.Workspace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 工作空间 Mapper
 * 使用 LambdaQueryWrapper 确保 JacksonTypeHandler 正确处理 JSONB 字段（config）。
 *
 * @author Actionow
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<Workspace> {

    /**
     * 根据所有者ID查询
     */
    default List<Workspace> selectByOwnerId(String ownerId) {
        return selectList(new LambdaQueryWrapper<Workspace>()
                .eq(Workspace::getOwnerId, ownerId));
    }

    /**
     * 检查工作空间名称是否存在
     */
    @Select("SELECT COUNT(*) FROM t_workspace WHERE name = #{name} AND owner_id = #{ownerId} AND deleted = 0")
    int countByNameAndOwner(@Param("name") String name, @Param("ownerId") String ownerId);

    /**
     * 检查slug是否存在
     */
    @Select("SELECT COUNT(*) FROM t_workspace WHERE slug = #{slug} AND deleted = 0")
    int countBySlug(@Param("slug") String slug);

    /**
     * 原子性增加成员计数
     */
    @Update("UPDATE t_workspace SET member_count = member_count + 1 WHERE id = #{workspaceId} AND deleted = 0")
    int incrementMemberCount(@Param("workspaceId") String workspaceId);

    /**
     * 原子性减少成员计数
     */
    @Update("UPDATE t_workspace SET member_count = GREATEST(member_count - 1, 0) WHERE id = #{workspaceId} AND deleted = 0")
    int decrementMemberCount(@Param("workspaceId") String workspaceId);

    /**
     * 根据ID列表批量查询
     */
    default List<Workspace> selectByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(new LambdaQueryWrapper<Workspace>()
                .in(Workspace::getId, ids));
    }

    /**
     * 统计用户创建的工作空间数量
     */
    @Select("SELECT COUNT(*) FROM t_workspace WHERE owner_id = #{ownerId} AND deleted = 0")
    int countByOwnerId(@Param("ownerId") String ownerId);

    /**
     * 系统管理员分页搜索 workspace（按 name/slug 模糊匹配，可选只看内部测试 workspace）
     */
    @Select("<script>" +
            "SELECT * FROM t_workspace WHERE deleted = 0" +
            "<if test='q != null and q != \"\"'>" +
            "  AND (LOWER(name) LIKE CONCAT('%', LOWER(#{q}), '%')" +
            "    OR LOWER(slug) LIKE CONCAT('%', LOWER(#{q}), '%')" +
            "    OR id::text = #{q})" +
            "</if>" +
            "<if test='internalOnly != null and internalOnly == true'>" +
            "  AND is_internal = true" +
            "</if>" +
            " ORDER BY created_at DESC" +
            "</script>")
    IPage<Workspace> adminSearchPage(Page<Workspace> page,
                                     @Param("q") String q,
                                     @Param("internalOnly") Boolean internalOnly);
}
