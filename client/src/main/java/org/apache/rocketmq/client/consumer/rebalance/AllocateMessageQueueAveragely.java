/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.client.consumer.rebalance;

import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.common.message.MessageQueue;

/**
 * Average Hashing queue algorithm
 */
public class AllocateMessageQueueAveragely extends AbstractAllocateMessageQueueStrategy {
    /* 平均分配 消息分区队列 */
    @Override
    public List<MessageQueue> allocate(String consumerGroup, String currentCID, List<MessageQueue> mqAll,
        List<String> cidAll) {

        List<MessageQueue> result = new ArrayList<>();
        if (!check(consumerGroup, currentCID, mqAll, cidAll)) {
            return result;
        }
        // mqAll.size() = 7,  cidAll.size() = 3
        int index = cidAll.indexOf(currentCID);  // 当前消费者在排好序的列表中的位置
        int mod = mqAll.size() % cidAll.size();  // 队列数 % 消费者数，即"多余的队列数" -- 队列数>消费者数时，mod 的值恰好就是"多分1个队列的消费者个数"。 --  7 % 3 = 1    ← 有 1 个"多余队列"要分给前面的消费者
        int averageSize =                        // 当前消费者应该分到几个队列
            mqAll.size() <= cidAll.size() ? 1 : (mod > 0 && index < mod ? mqAll.size() / cidAll.size()  //多分1个队列
                + 1 : mqAll.size() / cidAll.size());
        int startIndex = (mod > 0 && index < mod) ? index * averageSize : index * averageSize + mod;  // 从 mqAll 的哪个位置开始取
        int range = Math.min(averageSize, mqAll.size() - startIndex);   // min( 分配数，剩余数 )
        for (int i = 0; i < range; i++) {
            result.add(mqAll.get((startIndex + i) % mqAll.size()));
        }
        return result;
    }

    @Override
    public String getName() {
        return "AVG";
    }
}

/*
 * 算法特点总结
  - 每个消费者拿到的是连续段的队列（Q0~Q2，Q3~Q4），而非交错分配
  - 多余队列只分给编号靠前的消费者，最多差1个
  - 上层代码先对 mqAll 和 cidAll 排序，保证所有消费者在本地算出相同结果
  - 每个消费者独立计算自己的分配结果，不需要 Broker 仲裁
 *
 *
 *
 */
