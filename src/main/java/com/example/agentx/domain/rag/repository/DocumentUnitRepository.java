package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentUnitRepository extends MyBatisPlusExtRepository<DocumentUnitEntity> {

}
