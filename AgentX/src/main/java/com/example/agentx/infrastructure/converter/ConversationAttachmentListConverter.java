package com.example.agentx.infrastructure.converter;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.postgresql.util.PGobject;
import com.example.agentx.domain.conversation.model.ConversationAttachment;
import com.example.agentx.infrastructure.utils.JsonUtils;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

@MappedJdbcTypes(JdbcType.OTHER)
public class ConversationAttachmentListConverter extends BaseTypeHandler<List<ConversationAttachment>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<ConversationAttachment> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        jsonObject.setValue(JsonUtils.toJsonString(parameter == null ? Collections.emptyList() : parameter));
        ps.setObject(i, jsonObject);
    }

    @Override
    public List<ConversationAttachment> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<ConversationAttachment> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<ConversationAttachment> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<ConversationAttachment> parse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        return JsonUtils.parseArray(json, ConversationAttachment.class);
    }
}
