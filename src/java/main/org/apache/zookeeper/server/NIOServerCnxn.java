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

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.Version;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;
import org.apache.zookeeper.proto.WatcherEvent;
import org.apache.zookeeper.server.quorum.Leader;
import org.apache.zookeeper.server.quorum.LeaderZooKeeperServer;
import org.apache.zookeeper.server.quorum.ReadOnlyZooKeeperServer;
import org.apache.zookeeper.server.util.OSMXBean;

/**
 * This class handles communication with clients using NIO. There is one per
 * client, but only one thread doing the communication.
 *
 * 实现了 ServerCnxn，每个客户端连接由该对象代表，使用的是java自带的nio
 */
public class NIOServerCnxn extends ServerCnxn {
    static final Logger LOG = LoggerFactory.getLogger(NIOServerCnxn.class);

    NIOServerCnxnFactory factory; // 生产该对象的工厂类

    final SocketChannel sock; // socket 连接

    protected final SelectionKey sk;

    boolean initialized;

    ByteBuffer lenBuffer = ByteBuffer.allocate(4);

    ByteBuffer incomingBuffer = lenBuffer;

    // 缓冲队列
    LinkedBlockingQueue<ByteBuffer> outgoingBuffers = new LinkedBlockingQueue<ByteBuffer>();

    int sessionTimeout;

    protected final ZooKeeperServer zkServer;

    /**
     * The number of requests that have been submitted but not yet responded to.
     */
    int outstandingRequests;

    /**
     * This is the id that uniquely identifies the session of a client. Once
     * this session is no longer active, the ephemeral nodes will go away.
     */
    long sessionId;

    static long nextSessionId = 1;
    int outstandingLimit = 1;

    public NIOServerCnxn(ZooKeeperServer zk, SocketChannel sock,
            SelectionKey sk, NIOServerCnxnFactory factory) throws IOException {
        this.zkServer = zk;
        this.sock = sock;
        this.sk = sk;
        this.factory = factory;
        if (this.factory.login != null) { // 如果开启了安全机制，则进行相关配置
            this.zooKeeperSaslServer = new ZooKeeperSaslServer(factory.login);
        }
        if (zk != null) { // 将 outstandingLimit 与全局设置为一致
            outstandingLimit = zk.getGlobalOutstandingLimit();
        }
        sock.socket().setTcpNoDelay(true);  // 禁用 nagle 算法，避免发生ack等待
        /* set socket linger to false, so that socket close does not
         * block */
        sock.socket().setSoLinger(false, -1); // socket关闭后立即返回，未发送的数据将直接关闭
        InetAddress addr = ((InetSocketAddress) sock.socket()
                .getRemoteSocketAddress()).getAddress(); // 获取客户端ip
        authInfo.add(new Id("ip", addr.getHostAddress())); // acl 添加根据ip进行限制的权限控制
        sk.interestOps(SelectionKey.OP_READ); // 设置感兴趣的事件为读取事件
    }

    /* Send close connection packet to the client, doIO will eventually
     * close the underlying machinery (like socket, selectorkey, etc...)
     *
     * 发送 关闭连接的packet 给客户端，然后 doIO() 函数会将底层的socket, selectorkey等等都关闭
     */
    public void sendCloseSession() {
        sendBuffer(ServerCnxnFactory.closeConn);
    }

    /**
     * send buffer without using the asynchronous
     * calls to selector and then close the socket
     *
     * 采用阻塞的方式发送数据给客户端，然后关闭此连接
     *
     * @param bb
     */
    void sendBufferSync(ByteBuffer bb) {
       try {
           /* configure socket to be blocking
            * so that we dont have to do write in
            * a tight while loop
            */
           sock.configureBlocking(true); // 设置为阻塞模式
           if (bb != ServerCnxnFactory.closeConn) { // 不为关闭连接的 packet
               if (sock.isOpen()) { // 客户端还没有关闭
                   sock.write(bb); // 发送数据
               }
               packetSent(); // 统计发送的 packet
           } 
       } catch (IOException ie) {
           LOG.error("Error sending data synchronously ", ie);
       }
    }

    /**
     * 将 bb 里的数据发送给客户端，如果一次没有发送完，
     * 则将未发送完的数据放入 outgoingBuffers, 然后将 sk 的监听事件添加 写 事件
     * @param bb
     */
    public void sendBuffer(ByteBuffer bb) {
        try {
            internalSendBuffer(bb);
        } catch(Exception e) {
            LOG.error("Unexpected Exception: ", e);
        }
    }

    /**
     * This method implements the internals of sendBuffer. We
     * have separated it from send buffer to be able to catch
     * exceptions when testing.
     *
     * 该方法本来实现自 sendBuffer, 现在将一部分逻辑剥离出来，仅仅是为了方便测试
     *
     * @param bb Buffer to send.
     */
    protected void internalSendBuffer(ByteBuffer bb) {
        if (bb != ServerCnxnFactory.closeConn) { // 要发送的 bb 不是关闭连接
            // We check if write interest here because if it is NOT set,
            // nothing is queued, so we can try to send the buffer right
            // away without waking up the selector
            // 首先检查interestOps中是否存在WRITE操作，如果没有
            // 则表示直接发送缓冲而不必先唤醒selector
            if(sk.isValid() &&
                    ((sk.interestOps() & SelectionKey.OP_WRITE) == 0)) { // sk 还是有效的且监听的非 write 事件
                try {
                    sock.write(bb); // 将缓冲写入 channel 发送给客户端
                } catch (IOException e) {
                    // we are just doing best effort right now
                    // 这里的异常处理机制官方还没弄好
                }
            }
            // if there is nothing left to send, we are done
            // 如果这里 bb 的内容都发送完了，就直接返回
            if (bb.remaining() == 0) {
                packetSent(); // 统计发送包信息
                return;
            }
        }

        synchronized(this.factory){ // bb 的数据没有发送完，或者 bb 表示的是关闭连接的数据包
            sk.selector().wakeup(); // 直接唤醒 NIOServerFactory 中调用 selector.select() 的地方，或者让下次调用不会阻塞
            if (LOG.isTraceEnabled()) {
                LOG.trace("Add a buffer to outgoingBuffers, sk " + sk
                        + " is valid: " + sk.isValid());
            }
            outgoingBuffers.add(bb); // 发送队列添加未发送完的 bb
            if (sk.isValid()) {
                sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE); // 在原有的事件上再添加对写事件感兴趣
            }
        }
    }

    /** Read the request payload (everything following the length prefix) */
    private void readPayload() throws IOException, InterruptedException {
        if (incomingBuffer.remaining() != 0) { // have we read length bytes? 是否有可读的数据
            int rc = sock.read(incomingBuffer); // sock is non-blocking, so ok，之前已经将内容长度读取了出来了。且incomingBuffer大小已经正确分配
            if (rc < 0) {
                throw new EndOfStreamException(
                        "Unable to read additional data from client sessionid 0x"
                        + Long.toHexString(sessionId)
                        + ", likely client has closed socket");
            }
        }

        if (incomingBuffer.remaining() == 0) { // have we read length bytes? 完整地读写完了内容
            packetReceived(); // 统计数据
            incomingBuffer.flip(); // 准备读
            if (!initialized) { // 未初始化
                readConnectRequest(); // 读取连接请求并将 initialized 设置为 true
            } else {
                readRequest(); // 处理 packet
            }
            lenBuffer.clear();
            incomingBuffer = lenBuffer; // 完整读取完了本次请求，准备开始下一次请求
        }
    }

    /**
     * Only used in order to allow testing
     */
    protected boolean isSocketOpen() {
        return sock.isOpen();
    }

    @Override
    public InetAddress getSocketAddress() {
        if (sock == null) {
            return null;
        }

        return sock.socket().getInetAddress();
    }

    /**
     * Handles read/write IO on connection.
     *
     * 处理读写事件，由 NIOServerCnxnFactory 负责调用
     */
    void doIO(SelectionKey k) throws InterruptedException {
        try {
            if (isSocketOpen() == false) { // socket 已关闭，此时非正常
                LOG.warn("trying to do i/o on a null socket for session:0x"
                         + Long.toHexString(sessionId));

                return;
            }
            if (k.isReadable()) { // 处理读事件
                int rc = sock.read(incomingBuffer); // 读到 incomingBuffer 里，并获取读了多少字节
                if (rc < 0) { // 客户端已经异常关闭
                    throw new EndOfStreamException(
                            "Unable to read additional data from client sessionid 0x"
                            + Long.toHexString(sessionId)
                            + ", likely client has closed socket");
                }
                if (incomingBuffer.remaining() == 0) { // incomingBuffer 是否已经被读满
                    boolean isPayload; // 是消息体？
                    if (incomingBuffer == lenBuffer) { // start of next request 表示上一次内容已经读取结束，开始新的内容
                        incomingBuffer.flip(); // 准备从缓冲区中获取消息体的长度
                        // 当读取的是内容长度时则为true，否则为false
                        isPayload = readLength(k);// 确定是否为四字符请求，不是则为 true， 是则为false
                        incomingBuffer.clear();
                    } else {
                        // continuation
                        isPayload = true;
                    }
                    if (isPayload) { // not the case for 4letterword，实际的内容开始读取
                        readPayload(); // 读取内容，内容分为连接请求和具体的事务请求
                    }
                    else {
                        // four letter words take care
                        // need not do anything else
                        // 如果是四字符请求，其结果已经在 checkFourLetterWord 函数中发送了，所以这里直接返回
                        return;
                    }
                }
            }
            if (k.isWritable()) { // 处理写事件
                // ZooLog.logTraceMessage(LOG,
                // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK
                // "outgoingBuffers.size() = " +
                // outgoingBuffers.size());
                if (outgoingBuffers.size() > 0) { // 需要写出的队列不为空
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK,
                    // "sk " + k + " is valid: " +
                    // k.isValid());

                    /*
                     * This is going to reset the buffer position to 0 and the
                     * limit to the size of the buffer, so that we can fill it
                     * with data from the non-direct buffers that we need to
                     * send.
                     */
                    ByteBuffer directBuffer = factory.directBuffer;
                    directBuffer.clear(); // 准备写

                    for (ByteBuffer b : outgoingBuffers) {
                        if (directBuffer.remaining() < b.remaining()) { // 单个写出的数据量过大
                            /*
                             * When we call put later, if the directBuffer is to
                             * small to hold everything, nothing will be copied,
                             * so we've got to slice the buffer if it's too big.
                             */
                            b = (ByteBuffer) b.slice().limit(directBuffer.remaining()); // 设置到 directBuffer 可以容纳
                        }
                        /*
                         * put() is going to modify the positions of both
                         * buffers, put we don't want to change the position of
                         * the source buffers (we'll do that after the send, if
                         * needed), so we save and reset the position after the
                         * copy
                         */
                        int p = b.position();
                        directBuffer.put(b); // 放入
                        b.position(p); // put 函数会影响 b 的position，我们在这里将它恢复
                        if (directBuffer.remaining() == 0) { // 直到将 directBuffer 放满，我们才退出循环
                            break;
                        }
                    }
                    /*
                     * Do the flip: limit becomes position, position gets set to
                     * 0. This sets us up for the write.
                     */
                    directBuffer.flip(); // 设置好 position 和 limit 的位置，准备发送数据

                    int sent = sock.write(directBuffer); // 发送数据，并获取发送了多少数据
                    ByteBuffer bb;

                    // Remove the buffers that we have sent
                    while (outgoingBuffers.size() > 0) {
                        bb = outgoingBuffers.peek();
                        if (bb == ServerCnxnFactory.closeConn) {
                            throw new CloseRequestException("close requested");
                        }
                        int left = bb.remaining() - sent; // 还剩余多少没发送完
                        if (left > 0) { // 当前的 buffer 还没有发送完
                            /*
                             * We only partially sent this buffer, so we update
                             * the position and exit the loop.
                             */
                            bb.position(bb.position() + sent); // 设置正确的位置以备后续发送
                            break; // 直接退出该循环，因为第一个就没发送完，超过了 factory.directBuffer 的承载量
                        }
                        packetSent(); // 每发送完一个 outgoingBuffers 中的一个包便统计数据
                        /* We've sent the whole buffer, so drop the buffer */
                        sent -= bb.remaining();
                        outgoingBuffers.remove(); // 该 buffer 已经完全发送给客户端了，将其从发送队列中移除
                    }
                    // ZooLog.logTraceMessage(LOG,
                    // ZooLog.CLIENT_DATA_PACKET_TRACE_MASK, "after send,
                    // outgoingBuffers.size() = " + outgoingBuffers.size());
                }

                synchronized(this.factory){
                    if (outgoingBuffers.size() == 0) {
                        if (!initialized
                                && (sk.interestOps() & SelectionKey.OP_READ) == 0) { // 未初始化且不是读事件
                            throw new CloseRequestException("responded to info probe");
                        }
                        sk.interestOps(sk.interestOps()
                                & (~SelectionKey.OP_WRITE)); // outgoingBuffers 发送完了，注册写之外的事件
                    } else {
                        sk.interestOps(sk.interestOps()
                                | SelectionKey.OP_WRITE); // outgoingBuffers 未发送完，注册写事件
                    }
                }
            }

        /* 发生异常则关闭该会话 */
        } catch (CancelledKeyException e) {
            LOG.warn("CancelledKeyException causing close of session 0x"
                     + Long.toHexString(sessionId));
            if (LOG.isDebugEnabled()) {
                LOG.debug("CancelledKeyException stack trace", e);
            }
            close(); // 移除会话并关闭连接
        } catch (CloseRequestException e) {
            // expecting close to log session closure
            close();
        } catch (EndOfStreamException e) {
            LOG.warn(e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("EndOfStreamException stack trace", e);
            }
            // expecting close to log session closure
            close();
        } catch (IOException e) {
            LOG.warn("Exception causing close of session 0x"
                     + Long.toHexString(sessionId) + ": " + e.getMessage());
            if (LOG.isDebugEnabled()) {
                LOG.debug("IOException stack trace", e);
            }
            close();
        }
    }

    /**
     * 交给 zkServer 处理客户端发来的数据
     * @throws IOException
     */
    private void readRequest() throws IOException {
        zkServer.processPacket(this, incomingBuffer);
    }
    
    protected void incrOutstandingRequests(RequestHeader h) {
        if (h.getXid() >= 0) {
            synchronized (this) {
                outstandingRequests++;
            }
            synchronized (this.factory) {        
                // check throttling
                if (zkServer.getInProcess() > outstandingLimit) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Throttling recv " + zkServer.getInProcess());
                    }
                    disableRecv();
                    // following lines should not be needed since we are
                    // already reading
                    // } else {
                    // enableRecv();
                }
            }
        }

    }

    /**
     * 停止从客户端接收数据
     */
    public void disableRecv() {
        sk.interestOps(sk.interestOps() & (~SelectionKey.OP_READ));
    }

    /**
     * 启用从客户端接收数据
     */
    public void enableRecv() {
        synchronized (this.factory) {
            sk.selector().wakeup();
            if (sk.isValid()) {
                int interest = sk.interestOps();
                if ((interest & SelectionKey.OP_READ) == 0) {
                    sk.interestOps(interest | SelectionKey.OP_READ);
                }
            }
        }
    }

    /**
     * 交给 zkServer 处理连接请求，并将该连接状态设为初始化完成
     */
    private void readConnectRequest() throws IOException, InterruptedException {
        if (!isZKServerRunning()) {
            throw new IOException("ZooKeeperServer not running");
        }
        zkServer.processConnectRequest(this, incomingBuffer);
        initialized = true;
    }

    /**
     * clean up the socket related to a command and also make sure we flush the
     * data before we do that
     *
     * 这个函数负责处理 四字符 请求的流的关闭
     *
     * @param pwriter
     *            the pwriter for a command socket
     */
    private void cleanupWriterSocket(PrintWriter pwriter) {
        try {
            if (pwriter != null) {
                pwriter.flush();
                pwriter.close();
            }
        } catch (Exception e) {
            LOG.info("Error closing PrintWriter ", e);
        } finally {
            try {
                close();
            } catch (Exception e) {
                LOG.error("Error closing a command socket ", e);
            }
        }
    }

    /**
     * This class wraps the sendBuffer method of NIOServerCnxn. It is
     * responsible for chunking up the response to a client. Rather
     * than cons'ing up a response fully in memory, which may be large
     * for some commands, this class chunks up the result.
     *
     * 将 sendBuffer 分块， 并且每当有 2048 的字符数时，
     * 调用 sendBufferSync() 将数据发送出去
     *
     */
    private class SendBufferWriter extends Writer {
        private StringBuffer sb = new StringBuffer();
        
        /**
         * Check if we are ready to send another chunk.
         * @param force force sending, even if not a full chunk
         *              强制发送
         */
        private void checkFlush(boolean force) {
            if ((force && sb.length() > 0) || sb.length() > 2048) { // 如果当前缓冲区满足要求则发送
                sendBufferSync(ByteBuffer.wrap(sb.toString().getBytes()));
                // clear our internal buffer
                sb.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            if (sb == null) return;
            checkFlush(true); // 关闭前将剩余的数据发送
            sb = null; // clear out the ref to ensure no reuse
        }

        @Override
        public void flush() throws IOException {
            checkFlush(true);
        }

        /**
         * 写入数据
         */
        @Override
        public void write(char[] cbuf, int off, int len) throws IOException {
            sb.append(cbuf, off, len);
            checkFlush(false);
        }
    }

    private static final String ZK_NOT_SERVING =
        "This ZooKeeper instance is not currently serving requests";
    
    /**
     * Set of threads for commmand ports. All the 4
     * letter commands are run via a thread. Each class
     * maps to a corresponding 4 letter command. CommandThread
     * is the abstract class from which all the others inherit.
     *
     * 该类用于处理各种不同的四字符指令
     */
    private abstract class CommandThread extends Thread {
        PrintWriter pw;
        
        CommandThread(PrintWriter pw) {
            this.pw = pw;
        }
        
        public void run() {
            try {
                commandRun();
            } catch (IOException ie) {
                LOG.error("Error in running command ", ie);
            } finally {
                cleanupWriterSocket(pw);
            }
        }
        
        public abstract void commandRun() throws IOException;
    }
    
    private class RuokCommand extends CommandThread {
        public RuokCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            pw.print("imok");
            
        }
    }
    
    private class TraceMaskCommand extends CommandThread {
        TraceMaskCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            long traceMask = ZooTrace.getTextTraceLevel();
            pw.print(traceMask);
        }
    }
    
    private class SetTraceMaskCommand extends CommandThread {
        long trace = 0;
        SetTraceMaskCommand(PrintWriter pw, long trace) {
            super(pw);
            this.trace = trace;
        }
        
        @Override
        public void commandRun() {
            pw.print(trace);
        }
    }
    
    private class EnvCommand extends CommandThread {
        EnvCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            List<Environment.Entry> env = Environment.list();

            pw.println("Environment:");
            for(Environment.Entry e : env) {
                pw.print(e.getKey());
                pw.print("=");
                pw.println(e.getValue());
            }
            
        } 
    }
    
    private class ConfCommand extends CommandThread {
        ConfCommand(PrintWriter pw) {
            super(pw);
        }
            
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            } else {
                zkServer.dumpConf(pw);
            }
        }
    }
    
    private class StatResetCommand extends CommandThread {
        public StatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            }
            else { 
                zkServer.serverStats().reset();
                pw.println("Server stats reset.");
            }
        }
    }
    
    private class CnxnStatResetCommand extends CommandThread {
        public CnxnStatResetCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            } else {
                synchronized(factory.cnxns){
                    for(ServerCnxn c : factory.cnxns){
                        c.resetStats();
                    }
                }
                pw.println("Connection stats reset.");
            }
        }
    }

    private class DumpCommand extends CommandThread {
        public DumpCommand(PrintWriter pw) {
            super(pw);
        }
        
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            }
            else {
                pw.println("SessionTracker dump:");
                zkServer.sessionTracker.dumpSessions(pw);
                pw.println("ephemeral nodes dump:");
                zkServer.dumpEphemerals(pw);
            }
        }
    }
    
    private class StatCommand extends CommandThread {
        int len;
        public StatCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            }
            else {   
                pw.print("Zookeeper version: ");
                pw.println(Version.getFullVersion());
                if (zkServer instanceof ReadOnlyZooKeeperServer) {
                    pw.println("READ-ONLY mode; serving only " +
                               "read-only clients");
                }
                if (len == statCmd) {
                    LOG.info("Stat command output");
                    pw.println("Clients:");
                    // clone should be faster than iteration
                    // ie give up the cnxns lock faster
                    HashSet<NIOServerCnxn> cnxnset;
                    synchronized(factory.cnxns){
                        cnxnset = (HashSet<NIOServerCnxn>)factory
                        .cnxns.clone();
                    }
                    for(NIOServerCnxn c : cnxnset){
                        c.dumpConnectionInfo(pw, true);
                        pw.println();
                    }
                    pw.println();
                }
                pw.print(zkServer.serverStats().toString());
                pw.print("Node count: ");
                pw.println(zkServer.getZKDatabase().getNodeCount());
            }
            
        }
    }
    
    private class ConsCommand extends CommandThread {
        public ConsCommand(PrintWriter pw) {
            super(pw);
        }
        
        @SuppressWarnings("unchecked")
        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            } else {
                // clone should be faster than iteration
                // ie give up the cnxns lock faster
                HashSet<NIOServerCnxn> cnxns;
                synchronized (factory.cnxns) {
                    cnxns = (HashSet<NIOServerCnxn>) factory.cnxns.clone();
                }
                for (NIOServerCnxn c : cnxns) {
                    c.dumpConnectionInfo(pw, false);
                    pw.println();
                }
                pw.println();
            }
        }
    }
    
    private class WatchCommand extends CommandThread {
        int len = 0;
        public WatchCommand(PrintWriter pw, int len) {
            super(pw);
            this.len = len;
        }

        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
            } else {
                DataTree dt = zkServer.getZKDatabase().getDataTree();
                if (len == wchsCmd) {
                    dt.dumpWatchesSummary(pw);
                } else if (len == wchpCmd) {
                    dt.dumpWatches(pw, true);
                } else {
                    dt.dumpWatches(pw, false);
                }
                pw.println();
            }
        }
    }

    private class MonitorCommand extends CommandThread {

        MonitorCommand(PrintWriter pw) {
            super(pw);
        }

        @Override
        public void commandRun() {
            if(!isZKServerRunning()) {
                pw.println(ZK_NOT_SERVING);
                return;
            }
            ZKDatabase zkdb = zkServer.getZKDatabase();
            ServerStats stats = zkServer.serverStats();

            print("version", Version.getFullVersion());

            print("avg_latency", stats.getAvgLatency());
            print("max_latency", stats.getMaxLatency());
            print("min_latency", stats.getMinLatency());

            print("packets_received", stats.getPacketsReceived());
            print("packets_sent", stats.getPacketsSent());
            print("num_alive_connections", stats.getNumAliveClientConnections());

            print("outstanding_requests", stats.getOutstandingRequests());

            print("server_state", stats.getServerState());
            print("znode_count", zkdb.getNodeCount());

            print("watch_count", zkdb.getDataTree().getWatchCount());
            print("ephemerals_count", zkdb.getDataTree().getEphemeralsCount());
            print("approximate_data_size", zkdb.getDataTree().approximateDataSize());

            OSMXBean osMbean = new OSMXBean();
            if (osMbean != null && osMbean.getUnix() == true) {
                print("open_file_descriptor_count", osMbean.getOpenFileDescriptorCount());
                print("max_file_descriptor_count", osMbean.getMaxFileDescriptorCount());
            }

            if(stats.getServerState().equals("leader")) {
                Leader leader = ((LeaderZooKeeperServer)zkServer).getLeader();

                print("followers", leader.getLearners().size());
                print("synced_followers", leader.getForwardingFollowers().size());
                print("pending_syncs", leader.getNumPendingSyncs());
            }
        }

        private void print(String key, long number) {
            print(key, "" + number);
        }

        private void print(String key, String value) {
            pw.print("zk_");
            pw.print(key);
            pw.print("\t");
            pw.println(value);
        }

    }

    private class IsroCommand extends CommandThread {

        public IsroCommand(PrintWriter pw) {
            super(pw);
        }

        @Override
        public void commandRun() {
            if (!isZKServerRunning()) {
                pw.print("null");
            } else if (zkServer instanceof ReadOnlyZooKeeperServer) {
                pw.print("ro");
            } else {
                pw.print("rw");
            }
        }
    }

    private class NopCommand extends CommandThread {
        private String msg;

        public NopCommand(PrintWriter pw, String msg) {
            super(pw);
            this.msg = msg;
        }

        @Override
        public void commandRun() {
            pw.println(msg);
        }
    }

    /** Return if four letter word found and responded to, otw false
     *
     * 该函数负责检查是否是 四字符 请求，如果是则向客户端返回相应的请求体并返回 true
     * 如果不是，则返回 false
     */
    private boolean checkFourLetterWord(final SelectionKey k, final int len)
    throws IOException
    {
        // We take advantage of the limited size of the length to look
        // for cmds. They are all 4-bytes which fits inside of an int
        // 不是已知的四字符请求，直接返回 false
        if (!ServerCnxn.isKnown(len)) {
            return false;
        }

        packetReceived();

        /** cancel the selection key to remove the socket handling
         * from selector. This is to prevent netcat problem wherein
         * netcat immediately closes the sending side after sending the
         * commands and still keeps the receiving channel open. 
         * The idea is to remove the selectionkey from the selector
         * so that the selector does not notice the closed read on the
         * socket channel and keep the socket alive to write the data to
         * and makes sure to close the socket after its done writing the data
         *
         * 这里的取消注册是为了解决 nc 的一个bug，
         * nc 在发送请求后会立即关闭自己的发送通道，然后保持接受通道打开。
         * 这里立即从 selector 中取消注册，使得 selector 不会注意到 nc 的关闭事件，
         * 然后保持可以在 socket 写数据
         *
         */
        if (k != null) {
            try {
                k.cancel(); // 取消关注该事件
            } catch(Exception e) {
                LOG.error("Error cancelling command selection key ", e);
            }
        }

        // SendBufferWriter 内部使用的是 StringBuffer，如果数据达到一定量，则会立即使用同步的方式将数据发送
        final PrintWriter pwriter = new PrintWriter(
                new BufferedWriter(new SendBufferWriter()));

        // 获取四字符指令的字符串形式
        String cmd = ServerCnxn.getCommandString(len);

        // ZOOKEEPER-2693: don't execute 4lw if it's not enabled.
        if (!ServerCnxn.isEnabled(cmd)) { // 该四字符指令不允许使用
            LOG.debug("Command {} is not executed because it is not in the whitelist.", cmd);
            NopCommand nopCmd = new NopCommand(pwriter, cmd + " is not executed because it is not in the whitelist.");
            nopCmd.start();
            return true;
        }

        LOG.info("Processing " + cmd + " command from "
                + sock.socket().getRemoteSocketAddress());

        /* 检查属于那个类别的四字符指令，则返回相应的响应体 */
        if (len == ruokCmd) {
            RuokCommand ruok = new RuokCommand(pwriter);
            ruok.start();
            return true;
        } else if (len == getTraceMaskCmd) {
            TraceMaskCommand tmask = new TraceMaskCommand(pwriter);
            tmask.start();
            return true;
        } else if (len == setTraceMaskCmd) {
            incomingBuffer = ByteBuffer.allocate(8);
            int rc = sock.read(incomingBuffer);
            if (rc < 0) {
                throw new IOException("Read error");
            }

            incomingBuffer.flip();
            long traceMask = incomingBuffer.getLong();
            ZooTrace.setTextTraceLevel(traceMask);
            SetTraceMaskCommand setMask = new SetTraceMaskCommand(pwriter, traceMask);
            setMask.start();
            return true;
        } else if (len == enviCmd) {
            EnvCommand env = new EnvCommand(pwriter);
            env.start();
            return true;
        } else if (len == confCmd) {
            ConfCommand ccmd = new ConfCommand(pwriter);
            ccmd.start();
            return true;
        } else if (len == srstCmd) {
            StatResetCommand strst = new StatResetCommand(pwriter);
            strst.start();
            return true;
        } else if (len == crstCmd) {
            CnxnStatResetCommand crst = new CnxnStatResetCommand(pwriter);
            crst.start();
            return true;
        } else if (len == dumpCmd) {
            DumpCommand dump = new DumpCommand(pwriter);
            dump.start();
            return true;
        } else if (len == statCmd || len == srvrCmd) {
            StatCommand stat = new StatCommand(pwriter, len);
            stat.start();
            return true;
        } else if (len == consCmd) {
            ConsCommand cons = new ConsCommand(pwriter);
            cons.start();
            return true;
        } else if (len == wchpCmd || len == wchcCmd || len == wchsCmd) {
            WatchCommand wcmd = new WatchCommand(pwriter, len);
            wcmd.start();
            return true;
        } else if (len == mntrCmd) {
            MonitorCommand mntr = new MonitorCommand(pwriter);
            mntr.start();
            return true;
        } else if (len == isroCmd) {
            IsroCommand isro = new IsroCommand(pwriter);
            isro.start();
            return true;
        }
        return false;
    } // checkFourLetterWord 函数结束

    /** Reads the first 4 bytes of lenBuffer, which could be true length or
     *  four letter word.
     *
     * 读取前 4 个字节，前四个字节代表此次消息中消息体的大小。
     * 如果前 4 个字节是 四字符请求的编码，那么这次请求是通过 telnet 来的请求，且是一个四字符请求
     *
     * @param k selection key
     * @return true if length read, otw false (wasn't really the length)
     *          false 表示此次请求是四字符请求, true 表示读取到了长度
     * @throws IOException if buffer size exceeds maxBuffer size
     */
    private boolean readLength(SelectionKey k) throws IOException {
        // Read the length, now get the buffer
        int len = lenBuffer.getInt(); // 获取消息体长度
        if (!initialized && checkFourLetterWord(sk, len)) { // 未初始化，且是 四字符请求，
            return false;
        }
        if (len < 0 || len > BinaryInputArchive.maxBuffer) { // 请求体长度异常
            throw new IOException("Len error " + len);
        }
        if (!isZKServerRunning()) { // 服务器未运行
            throw new IOException("ZooKeeperServer not running");
        }
        incomingBuffer = ByteBuffer.allocate(len); // 读取到了数据长度，准备开始读取内容
        return true;
    }

    public long getOutstandingRequests() {
        synchronized (this) {
            synchronized (this.factory) {
                return outstandingRequests;
            }
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionTimeout()
     */
    public int getSessionTimeout() {
        return sessionTimeout;
    }

    @Override
    public String toString() {
        return "NIOServerCnxn object with sock = " + sock + " and sk = " + sk;
    }

    /*
     * Close the cnxn and remove it from the factory cnxns list.
     * 
     * This function returns immediately if the cnxn is not on the cnxns list.
     *
     * 关闭当前会话
     */
    @Override
    public void close() {
        factory.removeCnxn(this);

        if (zkServer != null) {
            zkServer.removeCnxn(this);
        }

        closeSock(); // 关闭 socket

        if (sk != null) {
            try {
                // need to cancel this selection key from the selector
                sk.cancel();
            } catch (Exception e) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ignoring exception during selectionkey cancel", e);
                }
            }
        }
    }

    /**
     * Close resources associated with the sock of this cnxn.
     * 在 close() 函数中被调用
     */
    private void closeSock() {
        if (sock.isOpen() == false) {
            return;
        }

        LOG.info("Closed socket connection for client "
                + sock.socket().getRemoteSocketAddress()
                + (sessionId != 0 ?
                        " which had sessionid 0x" + Long.toHexString(sessionId) :
                        " (no session established for client)"));
        try {
            /*
             * The following sequence of code is stupid! You would think that
             * only sock.close() is needed, but alas, it doesn't work that way.
             * If you just do sock.close() there are cases where the socket
             * doesn't actually close...
             */
            sock.socket().shutdownOutput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during output shutdown", e);
            }
        }
        try {
            sock.socket().shutdownInput();
        } catch (IOException e) {
            // This is a relatively common exception that we can't avoid
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during input shutdown", e);
            }
        }
        try {
            sock.socket().close();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socket close", e);
            }
        }
        try {
            sock.close();
            // XXX The next line doesn't seem to be needed, but some posts
            // to forums suggest that it is needed. Keep in mind if errors in
            // this section arise.
            // factory.selector.wakeup();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("ignoring exception during socketchannel close", e);
            }
        }
    }
    
    private final static byte fourBytes[] = new byte[4];

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#sendResponse(org.apache.zookeeper.proto.ReplyHeader,
     *      org.apache.jute.Record, java.lang.String)
     */
    @Override
    synchronized public void sendResponse(ReplyHeader h, Record r, String tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Make space for length
            BinaryOutputArchive bos = BinaryOutputArchive.getArchive(baos);
            try {
                baos.write(fourBytes);
                bos.writeRecord(h, "header");
                if (r != null) {
                    bos.writeRecord(r, tag);
                }
                baos.close();
            } catch (IOException e) {
                LOG.error("Error serializing response");
            }
            byte b[] = baos.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(b);
            bb.putInt(b.length - 4).rewind();
            sendBuffer(bb);
            if (h.getXid() > 0) {
                synchronized(this){
                    outstandingRequests--;
                }
                // check throttling
                synchronized (this.factory) {        
                    if (zkServer.getInProcess() < outstandingLimit
                            || outstandingRequests < 1) {
                        sk.selector().wakeup();
                        enableRecv();
                    }
                }
            }
         } catch(Exception e) {
            LOG.warn("Unexpected exception. Destruction averted.", e);
         }
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#process(org.apache.zookeeper.proto.WatcherEvent)
     */
    @Override
    synchronized public void process(WatchedEvent event) {
        ReplyHeader h = new ReplyHeader(-1, -1L, 0);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                                     "Deliver event " + event + " to 0x"
                                     + Long.toHexString(this.sessionId)
                                     + " through " + this);
        }

        // Convert WatchedEvent to a type that can be sent over the wire
        WatcherEvent e = event.getWrapper();

        sendResponse(h, e, "notification");
    }

    /*
     * (non-Javadoc)
     *
     * @see org.apache.zookeeper.server.ServerCnxnIface#getSessionId()
     */
    @Override
    public long getSessionId() {
        return sessionId;
    }

    @Override
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        this.factory.addSession(sessionId, this);
    }

    @Override
    public void setSessionTimeout(int sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    @Override
    public int getInterestOps() {
        return sk.isValid() ? sk.interestOps() : 0;
    }

    @Override
    public InetSocketAddress getRemoteSocketAddress() {
        if (sock.isOpen() == false) {
            return null;
        }
        return (InetSocketAddress) sock.socket().getRemoteSocketAddress();
    }

    @Override
    protected ServerStats serverStats() {
        if (!isZKServerRunning()) {
            return null;
        }
        return zkServer.serverStats();
    }

    /**
     * @return true if the server is running, false otherwise.
     */
    boolean isZKServerRunning() {
        return zkServer != null && zkServer.isRunning();
    }
}
