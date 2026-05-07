package com.example.agentx.domain.rag.repository;

import org.apache.ibatis.annotations.Mapper;
import com.example.agentx.domain.rag.model.FileDetailEntity;
import com.example.agentx.domain.rag.model.RagQaDatasetEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** @author shilong.zang
 * @date 17:44 <br/>
 */
@Mapper
public interface RagQaDatasetRepository extends MyBatisPlusExtRepository<RagQaDatasetEntity> {

}
