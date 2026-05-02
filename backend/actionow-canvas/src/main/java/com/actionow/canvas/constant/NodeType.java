package com.actionow.canvas.constant;

/**
 * Canvas 节点类型常量
 */
public interface NodeType {
    String ENTITY = "ENTITY";           // 业务实体节点（绑定 Project 实体）
    String STICKY_NOTE = "STICKY_NOTE"; // 便签（纯文本，不绑定实体）
    String IFRAME = "IFRAME";           // 嵌入外部网页（Figma/Miro/Google Docs）
    String SHAPE = "SHAPE";             // 几何形状（矩形/圆形/箭头/线条）
    String GROUP = "GROUP";             // 分组容器
}
