package com.example.agentx.domain.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.memory.model.MemoryPendingCandidateEntity;

/** memory_pending_candidates 表数据访问 */
@Mapper
public interface MemoryPendingCandidateRepository extends BaseMapper<MemoryPendingCandidateEntity> {
}
