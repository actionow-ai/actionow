package com.actionow.system.service.impl;

import com.actionow.common.core.exception.BusinessException;
import com.actionow.common.core.result.PageResult;
import com.actionow.common.core.result.ResultCode;
import com.actionow.system.dto.GiftCodeRedemptionResponse;
import com.actionow.system.dto.GiftCodeRequest;
import com.actionow.system.dto.GiftCodeResponse;
import com.actionow.system.entity.GiftCode;
import com.actionow.system.entity.GiftCodeRedemption;
import com.actionow.system.mapper.GiftCodeMapper;
import com.actionow.system.mapper.GiftCodeRedemptionMapper;
import com.actionow.system.service.GiftCodeService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 礼包码服务实现
 *
 * @author Actionow
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GiftCodeServiceImpl implements GiftCodeService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 12;
    private static final int CODE_GEN_MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GiftCodeMapper giftCodeMapper;
    private final GiftCodeRedemptionMapper redemptionMapper;

    @Override
    @Transactional
    public GiftCodeResponse create(GiftCodeRequest request, String operatorId) {
        GiftCode entity = new GiftCode();
        BeanUtils.copyProperties(request, entity, "code", "status");

        String code = StringUtils.hasText(request.getCode())
                ? request.getCode().trim().toUpperCase()
                : generateUniqueCode();
        if (giftCodeMapper.selectByCode(code) != null) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "兑换码已存在");
        }
        entity.setCode(code);
        if (entity.getMaxRedemptions() == null) {
            entity.setMaxRedemptions(1);
        }
        entity.setRedeemedCount(0);
        entity.setStatus("ACTIVE");
        entity.setCreatedBy(operatorId);

        validateValidPeriod(entity.getValidFrom(), entity.getValidUntil());

        giftCodeMapper.insert(entity);
        log.info("创建礼包码 code={} points={} operator={}", code, entity.getPoints(), operatorId);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public GiftCodeResponse update(String id, GiftCodeRequest request, String operatorId) {
        GiftCode entity = requireExisting(id);

        if (request.getName() != null) entity.setName(request.getName());
        if (request.getDescription() != null) entity.setDescription(request.getDescription());
        if (request.getPoints() != null) entity.setPoints(request.getPoints());
        if (request.getValidFrom() != null) entity.setValidFrom(request.getValidFrom());
        if (request.getValidUntil() != null) entity.setValidUntil(request.getValidUntil());
        if (request.getMaxRedemptions() != null) {
            if (request.getMaxRedemptions() < entity.getRedeemedCount()) {
                throw new BusinessException(ResultCode.PARAM_INVALID,
                        "可兑换次数不能小于已兑换次数 " + entity.getRedeemedCount());
            }
            entity.setMaxRedemptions(request.getMaxRedemptions());
        }
        if (request.getStatus() != null) {
            if (!"ACTIVE".equals(request.getStatus()) && !"DISABLED".equals(request.getStatus())) {
                throw new BusinessException(ResultCode.PARAM_INVALID, "状态只能为 ACTIVE 或 DISABLED");
            }
            entity.setStatus(request.getStatus());
        }
        entity.setUpdatedBy(operatorId);

        validateValidPeriod(entity.getValidFrom(), entity.getValidUntil());

        giftCodeMapper.updateById(entity);
        return toResponse(entity);
    }

    @Override
    @Transactional
    public void delete(String id, String operatorId) {
        GiftCode entity = requireExisting(id);
        if (entity.getRedeemedCount() != null && entity.getRedeemedCount() > 0) {
            throw new BusinessException(ResultCode.PARAM_INVALID,
                    "已被兑换的礼包码不能删除，请改为禁用");
        }
        giftCodeMapper.deleteById(id);
        log.info("删除礼包码 id={} operator={}", id, operatorId);
    }

    @Override
    public GiftCodeResponse getById(String id) {
        return toResponse(requireExisting(id));
    }

    @Override
    public PageResult<GiftCodeResponse> listPage(Long current, Long size, String keyword, String status) {
        if (current == null || current < 1) current = 1L;
        if (size == null || size < 1) size = 20L;
        if (size > 100) size = 100L;

        LambdaQueryWrapper<GiftCode> wrapper = new LambdaQueryWrapper<GiftCode>()
                .like(StringUtils.hasText(keyword), GiftCode::getCode, keyword)
                .eq(StringUtils.hasText(status), GiftCode::getStatus, status)
                .orderByDesc(GiftCode::getCreatedAt);

        Page<GiftCode> page = new Page<>(current, size);
        IPage<GiftCode> result = giftCodeMapper.selectPage(page, wrapper);

        if (result.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }
        List<GiftCodeResponse> records = result.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    @Override
    public PageResult<GiftCodeRedemptionResponse> listRedemptions(String giftCodeId, Long current, Long size) {
        if (current == null || current < 1) current = 1L;
        if (size == null || size < 1) size = 20L;
        if (size > 100) size = 100L;

        LambdaQueryWrapper<GiftCodeRedemption> wrapper = new LambdaQueryWrapper<GiftCodeRedemption>()
                .eq(StringUtils.hasText(giftCodeId), GiftCodeRedemption::getGiftCodeId, giftCodeId)
                .orderByDesc(GiftCodeRedemption::getCreatedAt);

        Page<GiftCodeRedemption> page = new Page<>(current, size);
        IPage<GiftCodeRedemption> result = redemptionMapper.selectPage(page, wrapper);

        if (result.getRecords().isEmpty()) {
            return PageResult.empty(current, size);
        }

        // 批量取 code
        List<String> codeIds = result.getRecords().stream()
                .map(GiftCodeRedemption::getGiftCodeId).distinct().collect(Collectors.toList());
        java.util.Map<String, String> codeMap = giftCodeMapper.selectBatchIds(codeIds).stream()
                .collect(Collectors.toMap(GiftCode::getId, GiftCode::getCode));

        List<GiftCodeRedemptionResponse> records = result.getRecords().stream().map(r -> {
            GiftCodeRedemptionResponse dto = new GiftCodeRedemptionResponse();
            BeanUtils.copyProperties(r, dto);
            dto.setCode(codeMap.get(r.getGiftCodeId()));
            return dto;
        }).collect(Collectors.toList());

        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    private GiftCode requireExisting(String id) {
        GiftCode entity = giftCodeMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "礼包码不存在");
        }
        return entity;
    }

    private void validateValidPeriod(LocalDateTime from, LocalDateTime until) {
        if (from != null && until != null && from.isAfter(until)) {
            throw new BusinessException(ResultCode.PARAM_INVALID, "生效时间不能晚于过期时间");
        }
    }

    private String generateUniqueCode() {
        for (int i = 0; i < CODE_GEN_MAX_ATTEMPTS; i++) {
            String code = randomCode();
            if (giftCodeMapper.selectByCode(code) == null) {
                return code;
            }
        }
        throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成兑换码失败，请重试");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private GiftCodeResponse toResponse(GiftCode entity) {
        GiftCodeResponse dto = new GiftCodeResponse();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
