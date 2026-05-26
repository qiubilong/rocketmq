这是 RocketMQ 默认的**平均分配算法**（`AllocateMessageQueueAveragely`），用一个具体例子来拆解最直观。

## 核心变量含义

```java
int index = cidAll.indexOf(currentCID);     // 当前消费者在排好序的列表中的位置
int mod = mqAll.size() % cidAll.size();     // 队列数 % 消费者数，即"多余的队列数"
int averageSize = ...                        // 当前消费者应该分到几个队列
int startIndex = ...                         // 从 mqAll 的哪个位置开始取
int range = Math.min(averageSize, mqAll.size() - startIndex);  // 实际取几个
```


## 核心逻辑图解

以 **7个队列（Q0~Q6）、3个消费者（C0、C1、C2）** 为例：

```
mqAll.size() = 7,  cidAll.size() = 3
mod = 7 % 3 = 1    ← 有 1 个"多余队列"要分给前面的消费者
```


**index < mod（即前 mod 个消费者）多分 1 个：**

| 消费者 | index | averageSize | startIndex | 分配结果 |
|--------|-------|-------------|------------|---------|
| C0 | 0 | 7/3+1 = **3** | 0×3 = 0 | Q0, Q1, Q2 |
| C1 | 1 | 7/3 = **2** | 1×2+1 = 3 | Q3, Q4 |
| C2 | 2 | 7/3 = **2** | 2×2+1 = 5 | Q5, Q6 |

公式分支：
```java
// index < mod → 多分1个
averageSize = mqAll.size() / cidAll.size() + 1;
startIndex  = index * averageSize;             // 紧密排列

// index >= mod → 正常分配
averageSize = mqAll.size() / cidAll.size();
startIndex  = index * averageSize + mod;       // 跳过前面多分的部分
```


`mod` 就像一个**偏移修正量**，加在后半段消费者的 `startIndex` 上，保证队列不重叠。

## 极端情况：队列数 ≤ 消费者数

```java
int averageSize = mqAll.size() <= cidAll.size() ? 1 : ...
```


如 3个队列、5个消费者：
- `averageSize` 全部设为 1
- `startIndex = index * 1 + mod`（mod = 3%5 = 3）
- C0 (index=0): startIndex = 0×1 = 0 → Q0
- C1 (index=1): startIndex = 1×1 = 1 → Q1
- C2 (index=2): startIndex = 2×1 = 2 → Q2
- C3 (index=3): startIndex = 3×1+3 = 6 ≥ mqAll.size() → range = 0 → **空**
- C4 (index=4): 同上 → **空**

即超出的消费者没有任何队列，处于**空闲状态**。

## `range` 的保护作用

```java
int range = Math.min(averageSize, mqAll.size() - startIndex);
```


防止 `startIndex + averageSize` 越界，是一个边界安全保证（实际上按上面逻辑计算正确时不会越界，这是双保险）。

## 算法特点总结

| 特点 | 说明 |
|------|------|
| **连续分配** | 每个消费者拿到的是连续段的队列（Q0~Q2，Q3~Q4），而非交错分配 |
| **差距最多1** | 多余队列只分给编号靠前的消费者，最多差1个 |
| **依赖排序** | 上层代码先对 `mqAll` 和 `cidAll` 排序，保证所有消费者在本地算出相同结果 |
| **无中心协调** | 每个消费者独立计算自己的分配结果，不需要 Broker 仲裁 |

最后一点正是客户端重平衡（Client Rebalance）的精髓：**排序后各自独立计算，结果天然一致**。




--- 

--- 
#  举一个mod=2的例子看看

以 **8个队列（Q0~Q7）、3个消费者（C0、C1、C2）** 为例：

```
mqAll.size() = 8,  cidAll.size() = 3
mod = 8 % 3 = 2    ← 有 2 个多余队列，分给 index < 2 的消费者
```


## 逐个计算

**C0（index=0，index < mod=2，多分1个）**
```
averageSize = 8/3 + 1 = 3
startIndex  = 0 × 3 = 0
range       = min(3, 8-0) = 3
→ 分到：Q0, Q1, Q2
```


**C1（index=1，index < mod=2，多分1个）**
```
averageSize = 8/3 + 1 = 3
startIndex  = 1 × 3 = 3
range       = min(3, 8-3) = 3
→ 分到：Q3, Q4, Q5
```


**C2（index=2，index >= mod=2，正常分配）**
```
averageSize = 8/3 = 2
startIndex  = 2 × 2 + 2 = 6
range       = min(2, 8-6) = 2
→ 分到：Q6, Q7
```


## 汇总

| 消费者 | index | averageSize | startIndex | 分配队列 | 队列数 |
|--------|-------|-------------|------------|---------|--------|
| C0 | 0 | 3 | 0 | Q0、Q1、Q2 | 3 |
| C1 | 1 | 3 | 3 | Q3、Q4、Q5 | 3 |
| C2 | 2 | 2 | 6 | Q6、Q7 | 2 |

```
Q0 Q1 Q2 | Q3 Q4 Q5 | Q6 Q7
[  C0   ] [  C1   ] [ C2 ]
```


验证：3 + 3 + 2 = 8 ✅，差值最多为 1（3-2=1）✅。

## mod=2 和 mod=1 的关键区别

| | mod=1（7队列/3消费者） | mod=2（8队列/3消费者） |
|--|--|--|
| 多分1个的消费者数 | 1个（仅C0） | 2个（C0、C1） |
| 分配结果 | 3, 2, 2 | 3, 3, 2 |

规律：**`mod` 的值恰好就是"多分1个队列的消费者个数"**。
