/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.server;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理客户端连接的具体实现类
 */
public class NIOServerCnxnFactory extends ServerCnxnFactory implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxnFactory.class);

    static {
        /**
         * this is to avoid the jvm bug:
         * NullPointerException in Selector.open()
         * http://bugs.sun.com/view_bug.do?bug_id=6427854
         *
         * 发生在 jdk5.0 的bug，后续版本已经修复
         * 这里这样做是为了避免bug
         */
        try {
            Selector.open().close();
        } catch(IOException ie) {
            LOG.error("Selector failed to open", ie);
        }
    }

    ServerSocketChannel ss; // 服务器的 Channel

    final Selector selector = Selector.open(); // 开启一个 selector

    /**
     * We use this buffer to do efficient socket I/O. Since there is a single
     * sender thread per NIOServerCnxn instance, we can use a member variable to
     * only allocate it once.
     *
     * 直接分配在机器内存的缓冲区，用于提升 io 速度
     *
    */
    final ByteBuffer directBuffer = ByteBuffer.allocateDirect(64 * 1024);


    /**
     * 一个 ip 可以发起多个客户端连接，所以该map的值是一个set
     */
    final HashMap<InetAddress, Set<NIOServerCnxn>> ipMap =
        new HashMap<InetAddress, Set<NIOServerCnxn>>( );

    int maxClientCnxns = 60; // 最大客户端连接数

    /**
     *
     * 创建没有连接数限制的 NIOServerCnxnFactory
     *
     * Construct a new server connection factory which will accept an unlimited number
     * of concurrent connections from each client (up to the file descriptor
     * limits of the operating system). startup(zks) must be called subsequently.
     * @throws IOException
     */
    public NIOServerCnxnFactory() throws IOException {
    }

    Thread thread;
    @Override
    public void configure(InetSocketAddress addr, int maxcc) throws IOException {
        configureSaslLogin(); // 首先配置安全认证机制

        thread = new ZooKeeperThread(this, "NIOServerCxn.Factory:" + addr);
        thread.setDaemon(true); // 将该类作为守护进程启动
        maxClientCnxns = maxcc; // 覆盖默认的最大连接数
        this.ss = ServerSocketChannel.open(); // 开启一个socket服务器端channel
        ss.socket().setReuseAddress(true); // 设置可重用端口
        LOG.info("binding to port " + addr);
        ss.socket().bind(addr); // 将 serverChannel 绑定到ip
        ss.configureBlocking(false); // nio 模式
        ss.register(selector, SelectionKey.OP_ACCEPT); // 注册 accept 事件
    }

    /** {@inheritDoc} */
    public int getMaxClientCnxnsPerHost() {
        return maxClientCnxns;
    }

    /** {@inheritDoc} */
    public void setMaxClientCnxnsPerHost(int max) {
        maxClientCnxns = max;
    }

    @Override
    public void start() {
        // ensure thread is started once and only once
        if (thread.getState() == Thread.State.NEW) { // 如果当前线程处于新建状态，则启动进程
            thread.start();
        }
    }

    @Override
    public void startup(ZooKeeperServer zks) throws IOException,
            InterruptedException {
        start(); // 启动该进程
        setZooKeeperServer(zks); // 设置服务器实例

        // 并初始化zk服务的一些数据
        zks.startdata();
        zks.startup();
    }

    /**
     * 获取本机地址
     * @return
     */
    @Override
    public InetSocketAddress getLocalAddress(){
        return (InetSocketAddress)ss.socket().getLocalSocketAddress();
    }

    /**
     * 获取本机端口
     * @return
     */
    @Override
    public int getLocalPort(){
        return ss.socket().getLocalPort();
    }

    /**
     * 添加一个与客户端的连接
     * @param cnxn
     */
    private void addCnxn(NIOServerCnxn cnxn) {
        synchronized (cnxns) {
            cnxns.add(cnxn);
            synchronized (ipMap){
                InetAddress addr = cnxn.sock.socket().getInetAddress(); // 获取新连接的 ip
                Set<NIOServerCnxn> s = ipMap.get(addr);
                if (s == null) {
                    // in general we will see 1 connection from each
                    // host, setting the initial cap to 2 allows us
                    // to minimize mem usage in the common case
                    // of 1 entry --  we need to set the initial cap
                    // to 2 to avoid rehash when the first entry is added
                    s = new HashSet<NIOServerCnxn>(2);
                    s.add(cnxn);
                    ipMap.put(addr,s);
                } else {
                    s.add(cnxn);
                }
            }
        }
    }

    /**
     * 移除与客户端的连接
     * @param cnxn
     */
    public void removeCnxn(NIOServerCnxn cnxn) {
        synchronized(cnxns) {
            // Remove the related session from the sessionMap.
            long sessionId = cnxn.getSessionId();
            if (sessionId != 0) {
                sessionMap.remove(sessionId); // 移除该会话
            }

            // if this is not in cnxns then it's already closed
            if (!cnxns.remove(cnxn)) { // 移除该连接
                return;
            }

            synchronized (ipMap) {
                Set<NIOServerCnxn> s =
                        ipMap.get(cnxn.getSocketAddress());
                s.remove(cnxn); // 移除该连接
            }

            unregisterConnection(cnxn); // jmx 框架移除该 MBean
        }
    }

    /**
     * 新建连接请求
     * @param sock 客户端 socket
     * @param sk 关注的事件类型
     * @return
     * @throws IOException
     */
    protected NIOServerCnxn createConnection(SocketChannel sock,
            SelectionKey sk) throws IOException {
        return new NIOServerCnxn(zkServer, sock, sk, this);
    }

    /**
     * 获取指定客户端ip开启了多少个客户端
     * @param cl
     * @return
     */
    private int getClientCnxnCount(InetAddress cl) {
        // The ipMap lock covers both the map, and its contents
        // (that is, the cnxn sets shouldn't be modified outside of
        // this lock)
        synchronized (ipMap) {
            Set<NIOServerCnxn> s = ipMap.get(cl);
            if (s == null) return 0;
            return s.size();
        }
    }

    /**
     * 该进程的主入口
     */
    public void run() {
        while (!ss.socket().isClosed()) { // 服务器端 serverSocket 未关闭
            try {
                selector.select(1000); // 至少有一个感兴趣的io事件发生时停止阻塞，或者超时
                Set<SelectionKey> selected;
                synchronized (this) {
                    selected = selector.selectedKeys(); // 获取准备就绪的io事件
                }
                ArrayList<SelectionKey> selectedList = new ArrayList<SelectionKey>(
                        selected); // 浅复制一份 selected
                Collections.shuffle(selectedList); // 打乱
                for (SelectionKey k : selectedList) {
                    if ((k.readyOps() & SelectionKey.OP_ACCEPT) != 0) { // 就绪且是客户端连接请求
                        SocketChannel sc = ((ServerSocketChannel) k
                                .channel()).accept(); // 接受连接请求
                        InetAddress ia = sc.socket().getInetAddress(); // 获取客户端ip
                        int cnxncount = getClientCnxnCount(ia); // 获取该ip已创建的客户端数
                        if (maxClientCnxns > 0 && cnxncount >= maxClientCnxns){ // 不允许一个ip创建超过 maxClientCnxns 的数量
                            LOG.warn("Too many connections from " + ia
                                     + " - max is " + maxClientCnxns );
                            sc.close(); // 直接关闭和客户端的 socket
                        } else {
                            LOG.info("Accepted socket connection from "
                                     + sc.socket().getRemoteSocketAddress());
                            sc.configureBlocking(false); // 设置为 nio

                            // 将该客户端的读事件注册到 selector 上
                            SelectionKey sk = sc.register(selector,
                                    SelectionKey.OP_READ);

                            NIOServerCnxn cnxn = createConnection(sc, sk); // 创建表示客户端的 NIOServerCnxn
                            sk.attach(cnxn); // 将该 sk 绑定到 cnxn 上，可以通过 sk.attachment() 获取绑定的对象
                            addCnxn(cnxn); // 添加客户端
                        }
                    } else if ((k.readyOps() & (SelectionKey.OP_READ | SelectionKey.OP_WRITE)) != 0) { // 就绪且如果是读或者写事件
                        NIOServerCnxn c = (NIOServerCnxn) k.attachment(); // 获取该 k 绑定的 客户端
                        c.doIO(k); // 交给NIOServerCnxn处理io事件
                    } else {
                        if (LOG.isDebugEnabled()) { // 超出预期的 io 事件
                            LOG.debug("Unexpected ops in select "
                                      + k.readyOps());
                        }
                    }
                }
                selected.clear(); // 清空
            } catch (RuntimeException e) {
                LOG.warn("Ignoring unexpected runtime exception", e);
            } catch (Exception e) {
                LOG.warn("Ignoring exception", e);
            }
        }
        // 服务器端 serverSocket 已关闭，清理所有连接
        closeAll();
        LOG.info("NIOServerCnxn factory exited run method");
    }

    /**
     * clear all the connections in the selector
     *
     */
    @Override
    @SuppressWarnings("unchecked")
    synchronized public void closeAll() {
        selector.wakeup(); // 将调用 selector.select() 的线程唤醒
        HashSet<NIOServerCnxn> cnxns;
        synchronized (this.cnxns) {
            cnxns = (HashSet<NIOServerCnxn>)this.cnxns.clone(); // 浅复制一份
        }
        // got to clear all the connections that we have in the selector
        for (NIOServerCnxn cnxn: cnxns) {
            try {
                // don't hold this.cnxns lock as deadlock may occur
                cnxn.close(); // 将与该客户的连接断开
            } catch (Exception e) {
                LOG.warn("Ignoring exception closing cnxn sessionid 0x"
                         + Long.toHexString(cnxn.sessionId), e);
            }
        }
    }

    public void shutdown() {
        try {
            ss.close(); // 将 serverSocket 关闭
            closeAll(); // 断开所有与客户端的连接
            thread.interrupt(); // 中断该守护进程
            thread.join(); // 等待线程终止
            if (login != null) {
                login.shutdown(); // 关闭 sasl 认证
            }
        } catch (InterruptedException e) {
            LOG.warn("Ignoring interrupted exception during shutdown", e);
        } catch (Exception e) {
            LOG.warn("Ignoring unexpected exception during shutdown", e);
        }
        try {
            selector.close(); // 关闭 selector
        } catch (IOException e) {
            LOG.warn("Selector closing", e);
        }
        if (zkServer != null) {
            zkServer.shutdown(); // 关闭 zk 服务器
        }
    }

    /**
     * 关闭指定会话
     * @param sessionId
     */
    @Override
    public synchronized void closeSession(long sessionId) {
        selector.wakeup();
        closeSessionWithoutWakeup(sessionId);
    }

    @SuppressWarnings("unchecked")
    private void closeSessionWithoutWakeup(long sessionId) {
        NIOServerCnxn cnxn = (NIOServerCnxn) sessionMap.remove(sessionId);
        if (cnxn != null) {
            try {
                cnxn.close(); // 关闭与该客户端的连接
            } catch (Exception e) {
                LOG.warn("exception during session close", e);
            }
        }
    }

    @Override
    public void join() throws InterruptedException {
        thread.join();
    }

    @Override
    public Iterable<ServerCnxn> getConnections() {
        return cnxns;
    }
}
