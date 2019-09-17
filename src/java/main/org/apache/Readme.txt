参考自 博文 https://www.cnblogs.com/leesf456/p/6098255.html

客户端的主要成员
    ClientWatchManager， 客户端Watcher管理器
    HostProvider，客户端地址列表管理器
    ClientCnxn，客户端核心线程，内部包含了SendThread和EventThread两个线程，SendThread为I/O线程，主要负责Zookeeper客户端和服务器之间的网络I/O通信；EventThread为事件线程，主要负责对服务端事件进行处理
    Packet，是ClientCnxn内部定义的一个堆协议层的封装，用作Zookeeper中请求和响应的载体。Packet包含了请求头（requestHeader）、响应头（replyHeader）、请求体（request）、响应体（response）、节点路径（clientPath/serverPath）、注册的Watcher（watchRegistration）等信息，然而，并非Packet中所有的属性都在客户端与服务端之间进行网络传输，只会将requestHeader、request、readOnly三个属性序列化，并生成可用于底层网络传输的ByteBuffer，其他属性都保存在客户端的上下文中，不会进行与服务端之间的网络传输

    ClientCnxn，维护着outgoingQueue（客户端的请求发送队列）和pendingQueue（服务端响应的等待队列），outgoingQueue专门用于存储那些需要发送到服务端的Packet集合，pendingQueue用于存储那些已经从客户端发送到服务端的，但是需要等待服务端响应的Packet集合

服务器端主要成员
    SessionTracker，会话管理，分桶策略，分配的原则是每个会话的下次超时时间点（ExpirationTime）
        ExpirationTime = ((CurrentTime + SessionTimeOut) / ExpirationInterval + 1) * ExpirationInterval

    QuorumPeerMain  作为统一的启动类
    ZooKeeperServerMain  如果发现是单机模式则 QuorumPeerMain 会委托给该类启动
    ServerStats  服务器运行时统计器
    FileTxnSnapLog  数据管理器
    ServerCnxnFactory  通过配置系统属性zookeper.serverCnxnFactory来指定使用Zookeeper自己实现的NIO还是使用Netty框架作为Zookeeper服务端网络连接工厂
    ZKDatabase, 负责管理ZooKeeper的所有会话记录以及DataTree和事务日志的存储
    QuorumPeer, 将核心组件如FileTxnSnapLog、ServerCnxnFactory、ZKDatabase注册到QuorumPeer中，同时配置QuorumPeer的参数，如服务器列表地址、Leader选举算法和会话超时时间限制等

    vote 的数据结构
        id：被推举的Leader的SID。
        zxid：被推举的Leader事务ID。
        electionEpoch：逻辑时钟，用来判断多个投票是否在同一轮选举周期中，该值在服务端是一个自增序列，每次进入新一轮的投票后，都会对该值进行加1操作。
        peerEpoch：被推举的Leader的epoch。
        state：当前服务器的状态。

    QuorumCnxManager，负责各台服务器之间的底层Leader选举过程中的网络通信
    　　　　· recvQueue：消息接收队列，用于存放那些从其他服务器接收到的消息。

    　　　　· queueSendMap：消息发送队列，用于保存那些待发送的消息，按照SID进行分组。

    　　　　· senderWorkerMap：发送器集合，每个SenderWorker消息发送器，都对应一台远程Zookeeper服务器，负责消息的发送，也按照SID进行分组。

    　　　　· lastMessageSent：最近发送过的消息，为每个SID保留最近发送过的一个消息。

集群间消息通讯
    DIFF、TRUNC、SNAP、UPTODATE
