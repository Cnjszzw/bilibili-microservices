package com.bilibili.content.mq;

import com.alibaba.fastjson.JSONObject;
import com.imooc.bilibili.dao.ContentDao;
import com.imooc.bilibili.domain.UserMoment;
import com.imooc.bilibili.domain.constant.UserMomentsConstant;
import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;

/**
 * RocketMQ 事务消息监听器
 * 保证视频投稿和动态创建的原子性
 * <p>
 * 流程：
 * 1. sendMessageInTransaction → 发送半消息到 Broker
 * 2. executeLocalTransaction → Spring @Transactional 未提交，返回 UNKNOWN
 * 3. Broker 超时后调用 checkLocalTransaction → 查 DB 验证视频是否已入库 → COMMIT 或 ROLLBACK
 */
@RocketMQTransactionListener
public class MomentTransactionListener implements RocketMQLocalTransactionListener {

    private static final Logger logger = LoggerFactory.getLogger(MomentTransactionListener.class);

    @Autowired
    private ContentDao contentDao;

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        // Spring 事务未提交，无法验证 DB 状态，交给 check 回调
        logger.info("事务消息半消息已发送，等待 Spring TX 提交后回查");
        return RocketMQLocalTransactionState.UNKNOWN;
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        // Broker 回查：验证视频内容是否已入库
        try {
            String body = new String((byte[]) msg.getPayload());
            UserMoment moment = JSONObject.parseObject(body, UserMoment.class);
            // 通过 contentId 查 Content 表，验证视频是否持久化成功
            if (moment.getContentId() != null && contentDao.getContentById(moment.getContentId()) != null) {
                logger.info("回查成功：Content({}) 已入库，提交消息", moment.getContentId());
                return RocketMQLocalTransactionState.COMMIT;
            }
            logger.warn("回查失败：Content({}) 未找到，回滚消息", moment.getContentId());
            return RocketMQLocalTransactionState.ROLLBACK;
        } catch (Exception e) {
            logger.error("回查异常", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }
}
