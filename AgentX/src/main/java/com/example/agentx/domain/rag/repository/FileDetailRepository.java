package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** 文件详情仓库接口
 * @author zang */
@Mapper
public interface FileDetailRepository extends MyBatisPlusExtRepository<FileDetailEntity> {

}