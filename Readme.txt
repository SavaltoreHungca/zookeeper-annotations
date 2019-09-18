部分内容参考自网络，若有不正确的地方还多请指正：

客户端的主要成员

    ClientWatchManager，         客户端Watcher管理器

    HostProvider，               客户端地址列表管理器

    ClientCnxn，                 客户端核心线程，内部包含了SendThread和EventThread两个线程，SendThread为I/O线程，
                                 主要负责Zookeeper客户端和服务器之间的网络I/O通信；EventThread为事件线程，主要负责
                                 对服务端事件进行处理

    Packet，                     是ClientCnxn内部定义的一个堆协议层的封装，用作Zookeeper中请求和响应的载体。
                                 Packet包含了请求头（requestHeader）、响应头（replyHeader）、请求体（request）、
                                 响应体（response）、节点路径（clientPath/serverPath）、注册的Watcher（watchRegistration）
                                 等信息，然而，并非Packet中所有的属性都在客户端与服务端之间进行网络传输，
                                 只会将requestHeader、request、readOnly三个属性序列化，并生成可用于底层网络传输的ByteBuffer，
                                 其他属性都保存在客户端的上下文中，不会进行与服务端之间的网络传输

    ClientCnxn，                 维护着outgoingQueue（客户端的请求发送队列）和pendingQueue（服务端响应的等待队列），
                                 outgoingQueue专门用于存储那些需要发送到服务端的Packet集合，pendingQueue用于存储那些已经从客户
                                 端发送到服务端的，但是需要等待服务端响应的Packet集合

服务器端主要成员

    SessionTracker，             会话管理，分桶策略，分配的原则是每个会话的下次超时时间点（ExpirationTime）
                                 ExpirationTime = ((CurrentTime + SessionTimeOut) / ExpirationInterval + 1) * ExpirationInterval

    QuorumPeerMain，             作为统一的启动类

    ZooKeeperServerMain          如果发现是单机模式则 QuorumPeerMain 会委托给该类启动

    ServerStats                  服务器运行时统计器

    FileTxnSnapLog               负责处理快照，日志等

    ServerCnxnFactory            通过配置系统属性zookeper.serverCnxnFactory来指定使用Zookeeper自己实现的NIO还是使用
                                 Netty框架作为Zookeeper服务端网络连接工厂

    ZKDatabase,                  负责管理ZooKeeper的所有会话记录以及DataTree和事务日志的存储

    QuorumPeer,                  将核心组件如FileTxnSnapLog、ServerCnxnFactory、ZKDatabase注册到QuorumPeer中，
                                 同时配置QuorumPeer的参数，如服务器列表地址、Leader选举算法和会话超时时间限制等

    vote                         代表选票

    QuorumCnxManager，           负责各台服务器之间的底层Leader选举过程中的网络通信
    　　　　                        · recvQueue：消息接收队列，用于存放那些从其他服务器接收到的消息。
    　　　　                        · queueSendMap：消息发送队列，用于保存那些待发送的消息，按照SID进行分组。
    　　　　                        · senderWorkerMap：发送器集合，每个SenderWorker消息发送器，都对应一台远程Zookeeper服务器，负责消息的发送，也按照SID进行分组。
    　　　　                        · lastMessageSent：最近发送过的消息，为每个SID保留最近发送过的一个消息。

集群间消息通讯

    DIFF、TRUNC、SNAP、UPTODATE


权限控制机制 ACL

    每个 znode 都会包含acl信息，acl信息包含：schema, id, permissions

    zk 提供了以下几种验证模式：

        digest：         Client端由用户名和密码验证，譬如user:password，digest的密码生成方式是Sha1摘要的base64形式

        auth：           不使用任何id，代表任何已确认用户。

        ip：             Client端由IP地址验证，譬如172.2.0.0/24

        world：          固定用户为anyone，为所有Client端开放权限

        super：          在这种scheme情况下，对应的id拥有超级权限，可以做任何事情(cdrwa）
                         注意的是，exists操作和getAcl操作并不受ACL许可控制，因此任何客户端可以查询节点的状态和节点的ACL。
                         节点的权限（perms）主要有以下几种：（Znode ACL权限用一个int型数字perms表示，perms的5个二进制位
                         分别表示setacl、delete、create、write、read。比如0x1f=adcwr，0x1=----r，0x15=a-c-r。）

                            Create  允许对子节点Create操作

                            Read    允许对本节点GetChildren和GetData操作

                            Write   允许对本节点SetData操作

                            Delete  允许对子节点Delete操作

                            Admin   允许对本节点setAcl操作

zk 的配额机制

    可以为节点设置配额限制，如可创建节点数数量限制，允许使用空间大小限制

    可以在 /zookeeper/quota/ 节点下查看各个已配额的节点的信息