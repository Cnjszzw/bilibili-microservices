package com.imooc.bilibili.service.config;

import com.bilibili.content.mq.MomentTransactionListener;
import com.imooc.bilibili.domain.constant.UserMomentsConstant;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Content Service RocketMQ 配置
 * 创建事务消息生产者，用于视频投稿→发动态的分布式事务
 */
@Configuration
public class RocketMQConfig {

    @Value("${rocketmq.name.server.address}")
    private String nameServerAddr;

    @Autowired
    private MomentTransactionListener momentTransactionListener;

    @Bean("momentTransactionProducer")
    public TransactionMQProducer momentTransactionProducer() throws Exception {
        TransactionMQProducer producer = new TransactionMQProducer(UserMomentsConstant.GROUP_MOMENTS + "_TX");
        producer.setNamesrvAddr(nameServerAddr);
        // 事务回查线程池
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2, 5, 100, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000),
                r -> new Thread(r, "moment-tx-check-thread")
        );
        producer.setExecutorService(executor);
        producer.setTransactionListener(momentTransactionListener);
        producer.start();
        return producer;
    }
}
