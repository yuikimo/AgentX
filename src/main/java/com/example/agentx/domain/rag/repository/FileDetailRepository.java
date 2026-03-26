package com.example.agentx.domain.rag.repository;

import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件详情仓库接口
 *
 */
@Mapper
public interface FileDetailRepository extends MyBatisPlusExtRepository<FileDetailEntity> {

}