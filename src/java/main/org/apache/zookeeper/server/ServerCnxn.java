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
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.jute.Record;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Id;
import org.apache.zookeeper.proto.ReplyHeader;
import org.apache.zookeeper.proto.RequestHeader;

/**
 * Interface to a Server connection - represents a connection from a client
 * to the server.
 *
 * 代表一个客户端连接, 模式使用的实现类是 NIOServerCnxn
 *
 */
public abstract class ServerCnxn implements Stats, Watcher {
    // This is just an arbitrary object to represent requests issued by
    // (aka owned by) this class
    // 一个用来代表自己的对象，当作这个对象的 id 就好
    final public static Object me = new Object();

    // ACL 权限验证信息
    protected ArrayList<Id> authInfo = new ArrayList<Id>();

    /**
     * If the client is of old version, we don't send r-o mode info to it.
     * The reason is that if we would, old C client doesn't read it, which
     * results in TCP RST packet, i.e. "connection reset by peer".
     *
     * 用来标示客户端是否是老版本的zk客户端，用于处理兼容性
     */
    boolean isOldClient = true;

    abstract int getSessionTimeout();

    abstract void close();

    public abstract void sendResponse(ReplyHeader h, Record r, String tag)
        throws IOException;

    /* notify the client the session is closing and close/cleanup socket
     * 通知客户端该会话关闭中
     */
    abstract void sendCloseSession();

    public abstract void process(WatchedEvent event);

    abstract long getSessionId();

    abstract void setSessionId(long sessionId);

    /**
     * auth info for the cnxn, returns an unmodifyable list
     * 返回该客户端所具有的权限
     */
    public List<Id> getAuthInfo() {
        return Collections.unmodifiableList(authInfo);
    }

    /**
     * 添加权限信息
     * @param id
     */
    public void addAuthInfo(Id id) {
        if (authInfo.contains(id) == false) {
            authInfo.add(id);
        }
    }

    public boolean removeAuthInfo(Id id) {
        return authInfo.remove(id);
    }

    abstract void sendBuffer(ByteBuffer closeConn);

    abstract void enableRecv();

    abstract void disableRecv();

    abstract void setSessionTimeout(int sessionTimeout);

    /**
     * Wrapper method to return the socket address
     */
    public abstract InetAddress getSocketAddress();

    protected ZooKeeperSaslServer zooKeeperSaslServer = null; // 安全认证服务器

    protected static class CloseRequestException extends IOException {
        private static final long serialVersionUID = -7854505709816442681L;

        public CloseRequestException(String msg) {
            super(msg);
        }
    }

    protected static class EndOfStreamException extends IOException {
        private static final long serialVersionUID = -8255690282104294178L;

        public EndOfStreamException(String msg) {
            super(msg);
        }

        public String toString() {
            return "EndOfStreamException: " + getMessage();
        }
    }

    /* 这部分是 zk 的四字节指令，使用 telnet 或者 nc 来使用这些指令 */

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int confCmd =
        ByteBuffer.wrap("conf".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int consCmd =
        ByteBuffer.wrap("cons".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int crstCmd =
        ByteBuffer.wrap("crst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int dumpCmd =
        ByteBuffer.wrap("dump".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int enviCmd =
        ByteBuffer.wrap("envi".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int getTraceMaskCmd =
        ByteBuffer.wrap("gtmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int ruokCmd =
        ByteBuffer.wrap("ruok".getBytes()).getInt();
    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int setTraceMaskCmd =
        ByteBuffer.wrap("stmk".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int srvrCmd =
        ByteBuffer.wrap("srvr".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int srstCmd =
        ByteBuffer.wrap("srst".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int statCmd =
        ByteBuffer.wrap("stat".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int wchcCmd =
        ByteBuffer.wrap("wchc".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int wchpCmd =
        ByteBuffer.wrap("wchp".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int wchsCmd =
        ByteBuffer.wrap("wchs".getBytes()).getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int mntrCmd = ByteBuffer.wrap("mntr".getBytes())
            .getInt();

    /*
     * See <a href="{@docRoot}/../../../docs/zookeeperAdmin.html#sc_zkCommands">
     * Zk Admin</a>. this link is for all the commands.
     */
    protected final static int isroCmd = ByteBuffer.wrap("isro".getBytes())
            .getInt();

    final static Map<Integer, String> cmd2String = new HashMap<Integer, String>();

    private static final String ZOOKEEPER_4LW_COMMANDS_WHITELIST = "zookeeper.4lw.commands.whitelist";

    private static final Logger LOG = LoggerFactory.getLogger(ServerCnxn.class);

    private static final Set<String> whiteListedCommands = new HashSet<String>();

    private static boolean whiteListInitialized = false;

    // @VisibleForTesting
    // 这个方法用于测试
    public synchronized static void resetWhiteList() {
        whiteListInitialized = false;
        whiteListedCommands.clear();
    }

    /**
     * Return the string representation of the specified command code.
     * 根据 code 返回其所代表的四字节字符串
     */
    public static String getCommandString(int command) {
        return cmd2String.get(command);
    }

    /**
     * Check if the specified command code is from a known command.
     *
     * 检查代表字符指令的code是否合法
     *
     * @param command The integer code of command.
     * @return true if the specified command is known, false otherwise.
     */
    public static boolean isKnown(int command) {
        return cmd2String.containsKey(command);
    }

    /**
     * Check if the specified command is enabled.
     *
     * In ZOOKEEPER-2693 we introduce a configuration option to only
     * allow a specific set of white listed commands to execute.
     * A command will only be executed if it is also configured
     * in the white list.
     *
     * 检查指定的 四字符指令 是否启用
     *
     * 在 ZOOKEEPER-2693 中，我们说过只有白名单中的四字符指令才可以被执行
     *
     * @param command The command string.
     * @return true if the specified command is enabled.
     */
    public synchronized static boolean isEnabled(String command) {
        if (whiteListInitialized) { // 如果白名单初始化好了
            return whiteListedCommands.contains(command);
        }

        String commands = System.getProperty(ZOOKEEPER_4LW_COMMANDS_WHITELIST); // 获取白名单
        if (commands != null) {
            String[] list = commands.split(",");
            for (String cmd : list) {
                if (cmd.trim().equals("*")) { // * 表示添加所有四字符指令
                    for (Map.Entry<Integer, String> entry : cmd2String.entrySet()) {
                        whiteListedCommands.add(entry.getValue());
                    }
                    break;
                }
                if (!cmd.trim().isEmpty()) { // 添加到白名单
                    whiteListedCommands.add(cmd.trim());
                }
            }
        } else { // 未指定白名单的情景下
            for (Map.Entry<Integer, String> entry : cmd2String.entrySet()) {
                String cmd = entry.getValue();
                if (cmd.equals("wchc") || cmd.equals("wchp")) { // 除了这两个外，其余的四字符指令默认都是可用的
                    // ZOOKEEPER-2693 / disable these exploitable commands by default.
                    continue;
                }
                whiteListedCommands.add(cmd);
            }
        }

        // Readonly mode depends on "isro".
        if (System.getProperty("readonlymode.enabled", "false").equals("true")) {
            whiteListedCommands.add("isro");
        }
        // zkServer.sh depends on "srvr".
        whiteListedCommands.add("srvr");
        whiteListInitialized = true;
        LOG.info("The list of known four letter word commands is : {}", Collections.singletonList(cmd2String));
        LOG.info("The list of enabled four letter word commands is : {}", Collections.singletonList(whiteListedCommands));
        return whiteListedCommands.contains(command);
    }

    // specify all of the commands that are available
    // 将所有的 四字符指令 添加到集合
    static {
        cmd2String.put(confCmd, "conf");
        cmd2String.put(consCmd, "cons");
        cmd2String.put(crstCmd, "crst");
        cmd2String.put(dumpCmd, "dump");
        cmd2String.put(enviCmd, "envi");
        cmd2String.put(getTraceMaskCmd, "gtmk");
        cmd2String.put(ruokCmd, "ruok");
        cmd2String.put(setTraceMaskCmd, "stmk");
        cmd2String.put(srstCmd, "srst");
        cmd2String.put(srvrCmd, "srvr");
        cmd2String.put(statCmd, "stat");
        cmd2String.put(wchcCmd, "wchc");
        cmd2String.put(wchpCmd, "wchp");
        cmd2String.put(wchsCmd, "wchs");
        cmd2String.put(mntrCmd, "mntr");
        cmd2String.put(isroCmd, "isro");
    }

    // 修改统计数据
    protected void packetReceived() {
        incrPacketsReceived();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats().incrementPacketsReceived();
        }
    }

    // 修改统计数据
    protected void packetSent() {
        incrPacketsSent();
        ServerStats serverStats = serverStats();
        if (serverStats != null) {
            serverStats().incrementPacketsSent();
        }
    }

    protected abstract ServerStats serverStats();

    // 与客户端成功创建连接的时间
    protected final Date established = new Date();

    // 接受的packet数量
    protected final AtomicLong packetsReceived = new AtomicLong();
    // 发送的packet数量
    protected final AtomicLong packetsSent = new AtomicLong();

    // 最小延迟
    protected long minLatency;
    // 最大延迟
    protected long maxLatency;
    // 最后操作类型
    protected String lastOp;
    protected long lastCxid;
    protected long lastZxid;
    protected long lastResponseTime;
    protected long lastLatency;

    protected long count; // 状态被改变的次数
    protected long totalLatency;

    /**
     * 复位所有状态
     */
    public synchronized void resetStats() {
        packetsReceived.set(0);
        packetsSent.set(0);
        minLatency = Long.MAX_VALUE;
        maxLatency = 0;
        lastOp = "NA";
        lastCxid = -1;
        lastZxid = -1;
        lastResponseTime = 0;
        lastLatency = 0;

        count = 0;
        totalLatency = 0;
    }

    protected long incrPacketsReceived() {
        return packetsReceived.incrementAndGet();
    }
    
    protected void incrOutstandingRequests(RequestHeader h) {
    }

    protected long incrPacketsSent() {
        return packetsSent.incrementAndGet();
    }

    /**
     * 将状态更新到最新
     */
    protected synchronized void updateStatsForResponse(long cxid, long zxid,
            String op, long start, long end)
    {
        // don't overwrite with "special" xids - we're interested
        // in the clients last real operation
        if (cxid >= 0) {
            lastCxid = cxid;
        }
        lastZxid = zxid;
        lastOp = op;
        lastResponseTime = end;
        long elapsed = end - start;
        lastLatency = elapsed;
        if (elapsed < minLatency) {
            minLatency = elapsed;
        }
        if (elapsed > maxLatency) {
            maxLatency = elapsed;
        }
        count++;
        totalLatency += elapsed;
    }

    public Date getEstablished() {
        return (Date)established.clone();
    }

    public abstract long getOutstandingRequests();

    public long getPacketsReceived() {
        return packetsReceived.longValue();
    }

    public long getPacketsSent() {
        return packetsSent.longValue();
    }

    public synchronized long getMinLatency() {
        return minLatency == Long.MAX_VALUE ? 0 : minLatency;
    }

    public synchronized long getAvgLatency() {
        return count == 0 ? 0 : totalLatency / count;
    }

    public synchronized long getMaxLatency() {
        return maxLatency;
    }

    public synchronized String getLastOperation() {
        return lastOp;
    }

    public synchronized long getLastCxid() {
        return lastCxid;
    }

    public synchronized long getLastZxid() {
        return lastZxid;
    }

    public synchronized long getLastResponseTime() {
        return lastResponseTime;
    }

    public synchronized long getLastLatency() {
        return lastLatency;
    }

    /**
     * Prints detailed stats information for the connection.
     *
     * 打印详细关于此连接的状态信息
     *
     * @see dumpConnectionInfo(PrintWriter, boolean) for brief stats
     */
    @Override
    public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpConnectionInfo(pwriter, false);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    public abstract InetSocketAddress getRemoteSocketAddress();
    public abstract int getInterestOps();
    
    /**
     * Print information about the connection.
     *
     * 将当前连接的详细信息装载进 PrintWriter
     *
     * @param brief iff true prints brief details, otw full detail
     * @return information about this connection
     */
    protected synchronized void
    dumpConnectionInfo(PrintWriter pwriter, boolean brief) {
        pwriter.print(" ");
        pwriter.print(getRemoteSocketAddress());
        pwriter.print("[");
        int interestOps = getInterestOps();
        pwriter.print(interestOps == 0 ? "0" : Integer.toHexString(interestOps));
        pwriter.print("](queued=");
        pwriter.print(getOutstandingRequests());
        pwriter.print(",recved=");
        pwriter.print(getPacketsReceived());
        pwriter.print(",sent=");
        pwriter.print(getPacketsSent());

        if (!brief) {
            long sessionId = getSessionId();
            if (sessionId != 0) {
                pwriter.print(",sid=0x");
                pwriter.print(Long.toHexString(sessionId));
                pwriter.print(",lop=");
                pwriter.print(getLastOperation());
                pwriter.print(",est=");
                pwriter.print(getEstablished().getTime());
                pwriter.print(",to=");
                pwriter.print(getSessionTimeout());
                long lastCxid = getLastCxid();
                if (lastCxid >= 0) {
                    pwriter.print(",lcxid=0x");
                    pwriter.print(Long.toHexString(lastCxid));
                }
                pwriter.print(",lzxid=0x");
                pwriter.print(Long.toHexString(getLastZxid()));
                pwriter.print(",lresp=");
                pwriter.print(getLastResponseTime());
                pwriter.print(",llat=");
                pwriter.print(getLastLatency());
                pwriter.print(",minlat=");
                pwriter.print(getMinLatency());
                pwriter.print(",avglat=");
                pwriter.print(getAvgLatency());
                pwriter.print(",maxlat=");
                pwriter.print(getMaxLatency());
            }
        }
        pwriter.print(")");
    }

}
