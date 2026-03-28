package com.example.agentx.domain.rag.service.management;

import org.springframework.stereotype.Service;
import com.example.agentx.domain.rag.model.UserRagFileEntity;
import com.example.agentx.domain.rag.repository.UserRagFileRepository;

@Service
public class UserRagFileDomainService {

    private final UserRagFileRepository userRagFileRepository;

    public UserRagFileDomainService(UserRagFileRepository userRagFileRepository) {
        this.userRagFileRepository = userRagFileRepository;
    }

    public UserRagFileEntity getById(String id) {
        return userRagFileRepository.selectById(id);
    }
}
