为 zookeeper(3.4.16) 翻译了原有的注释，并且添加了更为丰富的注释。

# 为什么会有此项目
zookeeper 代码设计精巧，涵盖分布式系统核心的内容。从中能够学习到io处理，分布式一致性等知识，且代码量并非很多，非常适合学习。

目前注释尚未覆盖完全，预计在2～3个月内完成完全覆盖

# 阅读源码的顺序
org.apache.zookeeper.server.quorum.QuorumPeerMain 是 zk 服务器端的主入口程序，建议从这里开始按顺序阅读。

需要注意 ./src/java/generated/ 里的类是由 zk 自动生成的，里面大抵是些 DTO，了解一下即可。

org.apache.zookeeper.ZooKeeper 是 zookeeper 的java客户端程序，要了解客户端的运行机制，可以从这里开始学习。

# 从zookeeper源码我能学到什么
你能够学到以下知识点：
  1. nio 网络编程模型
  2. 数据的快照，日志存储与恢复
  3. 集群间通讯方式
  4. 分布式系统如何保持一致性（paxos算法）
  5. jmx, jaas 框架的使用
  6. zk的底层序列化实现jute
  7. 响应式编程

当然还有很多

# 怎么从源码运行zk？

必须环境：
  1. ant 构建工具  
  2. JAVA-sdk 1.8+  
  3. idea(社区版) 或 eclipse  

准备好要求的环境后在终端打开项目根目录，执行  
`ant eclipse`  
执行完后将项目以 eclipse 项目的形式导入 idea 或者导入 eclipse

笔者使用的是 idea，这里以 idea 为例：

找到 org.apache.zookeeper.server.quorum.QuorumPeerMain 将其设为启动入口

添加 Program Arguments 的值(即QuorumPeerMain的传入参数)为 conf/zoo.cfg

运行项目，在官网下载 zk 编译好的客户端，然后

`./bin/zkCli.sh -server 127.0.0.1:2181`

# FAQ
## 1. ant eclipse 失败
>多半是网络问题，请自行搭建梯子，或者更换ant下载源
