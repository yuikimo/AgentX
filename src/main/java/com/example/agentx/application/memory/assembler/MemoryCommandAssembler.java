package com.example.agentx.application.memory.assembler;

import com.example.agentx.domain.memory.model.CandidateMemory;
import com.example.agentx.domain.memory.model.MemoryType;
import com.example.agentx.interfaces.dto.memory.CreateMemoryRequest;

public class MemoryCommandAssembler {

    public static CandidateMemory toCandidate(CreateMemoryRequest req) {
        CandidateMemory cm = new CandidateMemory();
        cm.setType(MemoryType.safeOf(req.getType()));
        cm.setText(req.getText());
        cm.setImportance(req.getImportance());
        cm.setTags(req.getTags());
        cm.setData(req.getData());
        return cm;
    }
}
