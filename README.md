为 zookeeper(3.4.16) 翻译了原有的注释，并且添加了更为丰富的注释。

#怎么从源码运行zk？

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

#FAQ
##1. ant eclipse 失败
>多半是网络问题，请自行搭建梯子，或者更换ant下载源