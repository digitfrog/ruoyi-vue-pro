package cn.iocoder.dashboard.modules.system.service.errorcode.impl;

import cn.hutool.core.collection.CollUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.dashboard.modules.tool.framework.errorcode.core.dto.ErrorCodeAutoGenerateReqDTO;
import cn.iocoder.dashboard.modules.tool.framework.errorcode.core.dto.ErrorCodeRespDTO;
import cn.iocoder.dashboard.modules.system.convert.errorcode.SysErrorCodeConvert;
import cn.iocoder.dashboard.modules.system.controller.errorcode.vo.SysErrorCodeCreateReqVO;
import cn.iocoder.dashboard.modules.system.controller.errorcode.vo.SysErrorCodeExportReqVO;
import cn.iocoder.dashboard.modules.system.controller.errorcode.vo.SysErrorCodePageReqVO;
import cn.iocoder.dashboard.modules.system.controller.errorcode.vo.SysErrorCodeUpdateReqVO;
import cn.iocoder.dashboard.modules.system.dal.dataobject.errorcode.SysErrorCodeDO;
import cn.iocoder.dashboard.modules.system.dal.mysql.errorcode.SysErrorCodeMapper;
import cn.iocoder.dashboard.modules.system.enums.errorcode.SysErrorCodeTypeEnum;
import cn.iocoder.dashboard.modules.system.service.errorcode.SysErrorCodeService;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.dashboard.modules.system.enums.SysErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertMap;
import static cn.iocoder.yudao.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * 错误码 Service 实现类
 *
 * @author dlyan
 */
@Service
@Validated
@Slf4j
public class SysErrorCodeServiceImpl implements SysErrorCodeService {

    @Resource
    private SysErrorCodeMapper errorCodeMapper;

    @Override
    public Long createErrorCode(SysErrorCodeCreateReqVO createReqVO) {
        // 校验 code 重复
        validateCodeDuplicate(createReqVO.getCode(), null);

        // 插入
        SysErrorCodeDO errorCode = SysErrorCodeConvert.INSTANCE.convert(createReqVO)
                .setType(SysErrorCodeTypeEnum.MANUAL_OPERATION.getType());
        errorCodeMapper.insert(errorCode);
        // 返回
        return errorCode.getId();
    }

    @Override
    public void updateErrorCode(SysErrorCodeUpdateReqVO updateReqVO) {
        // 校验存在
        this.validateErrorCodeExists(updateReqVO.getId());
        // 校验 code 重复
        validateCodeDuplicate(updateReqVO.getCode(), updateReqVO.getId());

        // 更新
        SysErrorCodeDO updateObj = SysErrorCodeConvert.INSTANCE.convert(updateReqVO)
                .setType(SysErrorCodeTypeEnum.MANUAL_OPERATION.getType());
        errorCodeMapper.updateById(updateObj);
    }

    @Override
    public void deleteErrorCode(Long id) {
        // 校验存在
        this.validateErrorCodeExists(id);
        // 删除
        errorCodeMapper.deleteById(id);
    }

    /**
     * 校验错误码的唯一字段是否重复
     *
     * 是否存在相同编码的错误码
     *
     * @param code 错误码编码
     * @param id 错误码编号
     */
    @VisibleForTesting
    public void validateCodeDuplicate(Integer code, Long id) {
        SysErrorCodeDO errorCodeDO = errorCodeMapper.selectByCode(code);
        if (errorCodeDO == null) {
            return;
        }
        // 如果 id 为空，说明不用比较是否为相同 id 的错误码
        if (id == null) {
            throw exception(ERROR_CODE_DUPLICATE);
        }
        if (!errorCodeDO.getId().equals(id)) {
            throw exception(ERROR_CODE_DUPLICATE);
        }
    }

    @VisibleForTesting
    public void validateErrorCodeExists(Long id) {
        if (errorCodeMapper.selectById(id) == null) {
            throw exception(ERROR_CODE_NOT_EXISTS);
        }
    }

    @Override
    public SysErrorCodeDO getErrorCode(Long id) {
        return errorCodeMapper.selectById(id);
    }

    @Override
    public PageResult<SysErrorCodeDO> getErrorCodePage(SysErrorCodePageReqVO pageReqVO) {
        return errorCodeMapper.selectPage(pageReqVO);
    }

    @Override
    public List<SysErrorCodeDO> getErrorCodeList(SysErrorCodeExportReqVO exportReqVO) {
        return errorCodeMapper.selectList(exportReqVO);
    }

    @Override
    @Transactional
    public void autoGenerateErrorCodes(List<ErrorCodeAutoGenerateReqDTO> autoGenerateDTOs) {
        if (CollUtil.isEmpty(autoGenerateDTOs)) {
            return;
        }
        // 获得错误码
        List<SysErrorCodeDO> errorCodeDOs = errorCodeMapper.selectListByCodes(
                convertSet(autoGenerateDTOs, ErrorCodeAutoGenerateReqDTO::getCode));
        Map<Integer, SysErrorCodeDO> errorCodeDOMap = convertMap(errorCodeDOs, SysErrorCodeDO::getCode);

        // 遍历 autoGenerateBOs 数组，逐个插入或更新。考虑到每次量级不大，就不走批量了
        autoGenerateDTOs.forEach(autoGenerateDTO -> {
            SysErrorCodeDO errorCodeDO = errorCodeDOMap.get(autoGenerateDTO.getCode());
            // 不存在，则进行新增
            if (errorCodeDO == null) {
                errorCodeDO = SysErrorCodeConvert.INSTANCE.convert(autoGenerateDTO)
                        .setType(SysErrorCodeTypeEnum.AUTO_GENERATION.getType());
                errorCodeMapper.insert(errorCodeDO);
                return;
            }
            // 存在，则进行更新。更新有三个前置条件：
            // 条件 1. 只更新自动生成的错误码，即 Type 为 ErrorCodeTypeEnum.AUTO_GENERATION
            if (!SysErrorCodeTypeEnum.AUTO_GENERATION.getType().equals(errorCodeDO.getType())) {
                return;
            }
            // 条件 2. 分组 applicationName 必须匹配，避免存在错误码冲突的情况
            if (!autoGenerateDTO.getApplicationName().equals(errorCodeDO.getApplicationName())) {
                log.error("[autoGenerateErrorCodes][自动创建({}/{}) 错误码失败，数据库中已经存在({}/{})]",
                        autoGenerateDTO.getCode(), autoGenerateDTO.getApplicationName(),
                        errorCodeDO.getCode(), errorCodeDO.getApplicationName());
                return;
            }
            // 条件 3. 错误提示语存在差异
            if (autoGenerateDTO.getMessage().equals(errorCodeDO.getMessage())) {
                return;
            }
            // 最终匹配，进行更新
            errorCodeMapper.updateById(new SysErrorCodeDO().setId(errorCodeDO.getId()).setMessage(autoGenerateDTO.getMessage()));
        });
    }

    @Override
    public List<ErrorCodeRespDTO> getErrorCodeList(String applicationName, Date minUpdateTime) {
        List<SysErrorCodeDO> errorCodeDOs = errorCodeMapper.selectListByApplicationNameAndUpdateTimeGt(
                applicationName, minUpdateTime);
        return SysErrorCodeConvert.INSTANCE.convertList03(errorCodeDOs);
    }

}

