package com.mwb.ai.claw.example.web.service;

import java.util.ArrayList;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.mwb.ai.claw.example.web.model.User;
import com.mwb.ai.claw.example.web.storage.UserStorage;

/**
 * 用户领域服务（service 层）：封装用户查询与创建的基础业务规则（默认值补齐），
 * 供 logic 层编排调用，持久化委托 storage 层。
 */
@Service
public class UserService {

    private final UserStorage storage;

    public UserService(UserStorage storage) {
        this.storage = storage;
    }

    /** 按用户名查询，不存在返回 empty */
    public Optional<User> findByUsername(String username) {
        return storage.findByUsername(username);
    }

    /** 新增用户：补齐默认值后持久化 */
    public User create(User user) {
        if (user.getCreatedAt() == 0) {
            user.setCreatedAt(System.currentTimeMillis());
        }
        if (user.getTools() == null) {
            user.setTools(new ArrayList<>());
        }
        return storage.save(user);
    }
}
