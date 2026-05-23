package com.imooc.bilibili.service;

import com.imooc.bilibili.dao.UserDao;
import com.imooc.bilibili.domain.User;
import com.imooc.bilibili.domain.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 微服务版 UserService（精简）
 * Content Service 直接通过共享数据库读取用户基本信息
 * 用户写操作（注册、登录、关注等）仍在 legacy 服务
 */
@Service
public class UserService {

    @Autowired
    private UserDao userDao;

    public User getUserInfo(Long userId) {
        User user = userDao.getUserById(userId);
        UserInfo userInfo = userDao.getUserInfoByUserId(userId);
        user.setUserInfo(userInfo);
        return user;
    }

    public List<UserInfo> getUserInfoByUserIds(Set<Long> userIds) {
        return userDao.getUserInfoByUserIds(userIds);
    }
}
