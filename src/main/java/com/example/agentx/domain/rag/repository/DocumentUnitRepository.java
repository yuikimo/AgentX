package com.example.agentx.domain.rag.repository;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.example.agentx.domain.rag.model.DocumentUnitEntity;
import com.example.agentx.infrastructure.repository.MyBatisPlusExtRepository;

/** @author shilong.zang
 * @date 21:07 <br/>
 */
@Mapper
public interface DocumentUnitRepository extends MyBatisPlusExtRepository<DocumentUnitEntity> {

    @Select({
            "<script>",
            "SELECT du.*",
            "FROM document_unit du",
            "INNER JOIN file_detail fd ON fd.id = du.file_id",
            "WHERE du.is_vector = true",
            "  AND du.deleted_at IS NULL",
            "  AND fd.deleted_at IS NULL",
            "  AND (file_id, page) IN",
            "  <foreach collection='refs' item='ref' open='(' separator=',' close=')'>",
            "    (#{ref.fileId}, #{ref.page})",
            "  </foreach>",
            "ORDER BY du.file_id ASC, du.page ASC, du.id ASC",
            "</script>"
    })
    List<DocumentUnitEntity> selectAdjacentChunks(@Param("refs") List<FilePageRef> refs);

    @Select({
            "<script>",
            "SELECT du.*",
            "FROM document_unit du",
            "INNER JOIN file_detail fd ON fd.id = du.file_id",
            "WHERE du.is_vector = true",
            "  AND du.deleted_at IS NULL",
            "  AND fd.deleted_at IS NULL",
            "  AND du.chunk_index IS NOT NULL",
            "  AND (du.file_id, du.chunk_index) IN",
            "  <foreach collection='refs' item='ref' open='(' separator=',' close=')'>",
            "    (#{ref.fileId}, #{ref.chunkIndex})",
            "  </foreach>",
            "ORDER BY du.file_id ASC, du.chunk_index ASC, du.id ASC",
            "</script>"
    })
    List<DocumentUnitEntity> selectAdjacentChunksByChunkIndexes(@Param("refs") List<FileChunkRef> refs);

    class FilePageRef {
        private final String fileId;
        private final Integer page;

        public FilePageRef(String fileId, Integer page) {
            this.fileId = fileId;
            this.page = page;
        }

        public String getFileId() {
            return fileId;
        }

        public Integer getPage() {
            return page;
        }
    }

    class FileChunkRef {
        private final String fileId;
        private final Integer chunkIndex;

        public FileChunkRef(String fileId, Integer chunkIndex) {
            this.fileId = fileId;
            this.chunkIndex = chunkIndex;
        }

        public String getFileId() {
            return fileId;
        }

        public Integer getChunkIndex() {
            return chunkIndex;
        }
    }
}
