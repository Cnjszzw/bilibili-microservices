package com.bilibili.content.mq;

import com.alibaba.fastjson.JSONObject;
import com.imooc.bilibili.dao.ContentDao;
import com.imooc.bilibili.domain.UserMoment;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 事务消息监听器（原生客户端 API）
 * 保证视频投稿和动态创建的原子性
 * <p>
 * 流程：
 * 1. TransactionMQProducer.sendMessageInTransaction → 发送半消息到 Broker
 * 2. executeLocalTransaction → Spring TX 未提交，返回 UNKNOW
 * 3. Broker 超时后调用 checkLocalTransaction → 查 DB 验证 → COMMIT 或 ROLLBACK
 */
@Component
public class MomentTransactionListener implements TransactionListener {

    private static final Logger logger = LoggerFactory.getLogger(MomentTransactionListener.class);

    @Autowired
    private ContentDao contentDao;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        logger.info("事务消息半消息已发送，等待 Spring TX 提交后回查");
        return LocalTransactionState.UNKNOW;
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        try {
            String body = new String(msg.getBody());
            UserMoment moment = JSONObject.parseObject(body, UserMoment.class);
            if (moment.getContentId() != null
                    && contentDao.getContentById(moment.getContentId()) != null) {
                logger.info("回查成功：Content({}) 已入库，提交消息", moment.getContentId());
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            logger.warn("回查失败：Content({}) 未找到，回滚消息", moment.getContentId());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception e) {
            logger.error("回查异常", e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }
}
