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

package org.apache.zookeeper.server.quorum;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Map.Entry;

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.apache.zookeeper.server.ZooKeeperServer;
import org.apache.zookeeper.server.quorum.QuorumPeer.LearnerType;
import org.apache.zookeeper.server.quorum.QuorumPeer.QuorumServer;
import org.apache.zookeeper.server.quorum.auth.QuorumAuth;
import org.apache.zookeeper.server.quorum.flexible.QuorumHierarchical;
import org.apache.zookeeper.server.quorum.flexible.QuorumMaj;
import org.apache.zookeeper.server.quorum.flexible.QuorumVerifier;

/**
 * 集群配置的存储类
 */
@InterfaceAudience.Public
public class QuorumPeerConfig {
    private static final Logger LOG = LoggerFactory.getLogger(QuorumPeerConfig.class);

    protected InetSocketAddress clientPortAddress; // 服务器地址
    protected String dataDir; // 快照目录
    protected String dataLogDir; // 日志目录
    protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME; // tick time，可以看为 zk 的一个基础时间单位
    protected int maxClientCnxns = 60; // 最大客户端连接数
    /** defaults to -1 if not set explicitly */
    protected int minSessionTimeout = -1;
    /** defaults to -1 if not set explicitly */
    protected int maxSessionTimeout = -1;

    protected int initLimit; // 限制连接到 leader 的时间, 单位为 tickTime 的值
    protected int syncLimit; // leader 到节点的超时限制, 单位为 tickTime 的值
    protected int electionAlg = 3; // 选举算法
    protected int electionPort = 2182; // 用于集群间通讯的端口
    protected boolean quorumListenOnAllIPs = false;
    protected final HashMap<Long,QuorumServer> servers =
        new HashMap<Long, QuorumServer>();
    protected final HashMap<Long,QuorumServer> observers =
        new HashMap<Long, QuorumServer>();

    protected long serverId; // 本机的 sid
    protected HashMap<Long, Long> serverWeight = new HashMap<Long, Long>();
    protected HashMap<Long, Long> serverGroup = new HashMap<Long, Long>();
    protected int numGroups = 0;
    protected QuorumVerifier quorumVerifier; // 用于校验机器是否属于集群
    protected int snapRetainCount = 3; // 快照保留数
    protected int purgeInterval = 0; // 清理快照和日志的时间间隔
    protected boolean syncEnabled = true; // 是否让 observer 也从leader同步数据

    protected LearnerType peerType = LearnerType.PARTICIPANT; // 本机服务类型

    /** Configurations for the quorumpeer-to-quorumpeer sasl authentication */
    protected boolean quorumServerRequireSasl = false;
    protected boolean quorumLearnerRequireSasl = false;
    protected boolean quorumEnableSasl = false;
    protected String quorumServicePrincipal = QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL_DEFAULT_VALUE;
    protected String quorumLearnerLoginContext = QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    protected String quorumServerLoginContext = QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT_DFAULT_VALUE;
    protected int quorumCnxnThreadsSize; // 集群间通讯所使用的线程池大小

    /**
     * Minimum snapshot retain count.
     * @see org.apache.zookeeper.server.PurgeTxnLog#purge(File, File, int)
     */
    private final int MIN_SNAP_RETAIN_COUNT = 3;

    @SuppressWarnings("serial")
    public static class ConfigException extends Exception {
        public ConfigException(String msg) {
            super(msg);
        }
        public ConfigException(String msg, Exception e) {
            super(msg, e);
        }
    }

    private static String[] splitWithLeadingHostname(String s)
            throws ConfigException
    {
        /* Does it start with an IPv6 literal? */
        if (s.startsWith("[")) {
            int i = s.indexOf("]:");
            if (i < 0) {
                throw new ConfigException(s + " starts with '[' but has no matching ']:'");
            }

            String[] sa = s.substring(i + 2).split(":");
            String[] nsa = new String[sa.length + 1];
            nsa[0] = s.substring(1, i);
            System.arraycopy(sa, 0, nsa, 1, sa.length);

            return nsa;
        } else {
            return s.split(":");
        }
    }

    /**
     * Parse a ZooKeeper configuration file
     * 解析zk配置文件
     * @param path the patch of the configuration file
     * @throws ConfigException error processing configuration
     */
    public void parse(String path) throws ConfigException {
        File configFile = new File(path); // 文件路径

        LOG.info("Reading configuration from: " + configFile);

        try {
            if (!configFile.exists()) { // 文件不存在
                throw new IllegalArgumentException(configFile.toString()
                        + " file is missing");
            }

            Properties cfg = new Properties(); // 属性源
            FileInputStream in = new FileInputStream(configFile);
            try {
                cfg.load(in); // 将文件里的属性配置加载进属性源
            } finally {
                in.close();
            }

            parseProperties(cfg); // 开始解析
        } catch (IOException e) { // io错误
            throw new ConfigException("Error processing " + path, e);
        } catch (IllegalArgumentException e) { // 不合法的传入参数
            throw new ConfigException("Error processing " + path, e);
        }
    }

    /**
     * Parse config from a Properties.
     * @param zkProp Properties to parse from.
     * @throws IOException
     * @throws ConfigException
     */
    public void parseProperties(Properties zkProp)
    throws IOException, ConfigException {
        int clientPort = 0;
        String clientPortAddress = null;
        for (Entry<Object, Object> entry : zkProp.entrySet()) { // 遍历属性源中加载的所有属性
            String key = entry.getKey().toString().trim();
            String value = entry.getValue().toString().trim();
            if (key.equals("dataDir")) { // 快照目录
                dataDir = value;
            } else if (key.equals("dataLogDir")) { // 日志目录
                dataLogDir = value;
            } else if (key.equals("clientPort")) { // 服务器使用的端口
                clientPort = Integer.parseInt(value);
            } else if (key.equals("clientPortAddress")) { // ip地址
                clientPortAddress = value.trim();
            } else if (key.equals("tickTime")) { // tick time
                tickTime = Integer.parseInt(value);
            } else if (key.equals("maxClientCnxns")) { // 最大允许多少客户端连接
                maxClientCnxns = Integer.parseInt(value);
            } else if (key.equals("minSessionTimeout")) { // 最小会话超时时间
                minSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("maxSessionTimeout")) { // 最大会话超时时间
                maxSessionTimeout = Integer.parseInt(value);
            } else if (key.equals("initLimit")) { // 限制连接到 leader 的时间，实际时间为 initLimit * tickTime
                initLimit = Integer.parseInt(value);
            } else if (key.equals("syncLimit")) { // leader 到节点同步超时限制, 实际时间为 syncLimit * tickTime
                syncLimit = Integer.parseInt(value);
            } else if (key.equals("electionAlg")) {
                // 指定选举算法，0：最原始的 udp 版本；  未来可能被舍弃
                //             1：没有验证的udp快速选举版本；  未来被舍弃
                //             2：有验证的udp快速选举版本；   未来被舍弃
                //             3：基于tcp的快速选举版本；   默认采用
                electionAlg = Integer.parseInt(value);
            } else if (key.equals("quorumListenOnAllIPs")) {
                /*
                 * 当设置为true时，ZooKeeper服务器将会在所有可用的IP地址上监听来自其对等点的连接请求，
                 * 而不仅是配置文件的服务器列表中配置的地址。
                 * 它会影响处理ZAB协议和Fast Leader Election协议的连接。默认值是false。
                 */
                quorumListenOnAllIPs = Boolean.parseBoolean(value);
            } else if (key.equals("peerType")) {
                if (value.toLowerCase().equals("observer")) {
                    /*
                     * 在zookeeper集群中使用观察者。
                     * peerType=observer
                     * 这行配置告诉zookeeper这台服务器将会成为一个Observers。
                     *
                     * 其次，在所有的服务器节点，在server定义处需要在末尾增加:observer。例如：
                     *
                     * server.1:localhost:2181:3181:observer
                     * 这会告诉其它服务server.1是一个observer，不会参与投票。
                     *
                     * 运行下面的命令即可链接到集群：
                     *
                     * bin/zkCli.sh -server localhost:2181
                     */
                    peerType = LearnerType.OBSERVER;
                } else if (value.toLowerCase().equals("participant")) {

                    /*
                     * 参与者，将参与选举，最终分为 leader 和 follower
                     */
                    peerType = LearnerType.PARTICIPANT;
                } else
                {
                    throw new ConfigException("Unrecognised peertype: " + value);
                }
            } else if (key.equals( "syncEnabled" )) {

                /*
                 * 和参与者一样，观察者现在默认将事务日志以及数据快照写到磁盘上，
                 * 这将减少观察者在服务器重启时的恢复时间。将其值设置为“false”可以禁用该特性。默认值是 “true”。
                 */
                syncEnabled = Boolean.parseBoolean(value);
            } else if (key.equals("autopurge.snapRetainCount")) {
                snapRetainCount = Integer.parseInt(value);
            } else if (key.equals("autopurge.purgeInterval")) {
                // 自动删除历史日志和快照的时间间隔
                purgeInterval = Integer.parseInt(value);
            } else if (key.startsWith("server.")) { // 开始解析集群服务器列表
                int dot = key.indexOf('.');
                long sid = Long.parseLong(key.substring(dot + 1));
                String parts[] = splitWithLeadingHostname(value);
                if ((parts.length != 2) && (parts.length != 3) && (parts.length !=4)) { // 错误的 peer 配置格式
                    LOG.error(value
                       + " does not have the form host:port or host:port:port " +
                       " or host:port:port:type");
                }
                LearnerType type = null;
                String hostname = parts[0]; // peer 主机名
                Integer port = Integer.parseInt(parts[1]); // 供客户端使用的端口
                Integer electionPort = null; // 选举使用的端口
                if (parts.length > 2){
                	electionPort=Integer.parseInt(parts[2]);
                }
                if (parts.length > 3){ // 确认是否指定了 peer 的服务类型
                    if (parts[3].toLowerCase().equals("observer")) {
                        type = LearnerType.OBSERVER;
                    } else if (parts[3].toLowerCase().equals("participant")) {
                        type = LearnerType.PARTICIPANT;
                    } else {
                        throw new ConfigException("Unrecognised peertype: " + value);
                    }
                }
                if (type == LearnerType.OBSERVER){
                    // 不参与选举的 observers
                    observers.put(Long.valueOf(sid), new QuorumServer(sid, hostname, port, electionPort, type));
                } else {
                    // 参与选举的 peer
                    servers.put(Long.valueOf(sid), new QuorumServer(sid, hostname, port, electionPort, type));
                }
            } else if (key.startsWith("group")) {
                /*
                 * 组必须是不相交的，并且所有组联合后必须是 ZooKeeper 集群。
                 * 使用方式：
                 * group.1=1:2:3
                 * group.2=4:5:6
                 * group.3=7:8:9
                 *
                 * weight.1=1
                 * weight.2=1
                 * weight.3=1
                 * weight.4=1
                 * weight.5=1
                 * weight.6=1
                 * weight.7=1
                 * weight.8=1
                 * weight.9=1
                 *
                 * 当形成集群时它给每个服务器赋权重值。这个值对应于投票时服务器的权重。
                 * ZooKeeper中只有少数部分需要投票，比如Leader选举以及原子的广播协议。
                 * 服务器权重的默认值是1。如果配置文件中定义了组，但是没有权重，那么所有服务器的权重将会赋值为1。
                 */

                int dot = key.indexOf('.');
                long gid = Long.parseLong(key.substring(dot + 1));

                numGroups++;

                String parts[] = value.split(":");
                for(String s : parts){
                    long sid = Long.parseLong(s);
                    if(serverGroup.containsKey(sid))
                        throw new ConfigException("Server " + sid + "is in multiple groups");
                    else
                        serverGroup.put(sid, gid);
                }

            } else if(key.startsWith("weight")) {
                /* 见 group 属性 */
                int dot = key.indexOf('.');
                long sid = Long.parseLong(key.substring(dot + 1));
                serverWeight.put(sid, Long.parseLong(value));

            /*
             * zk 在选举leader的时候服务器之间的通讯是没有启用安全认证的
             * zk 支持Kerberos和DIGEST-MD5验证机制
             *
             * 启用 sasl认证
             * quorum.auth.enableSasl=true
             * 是否必须要求通过安全认证，一般来说，若认证失败，zk集群将使用无认证选举，但开启必须安全认证，则必须通过安全认证才能选举
             * quorum.auth.learnerRequireSasl=true
             * 所有未认证的 learner 都会被拒绝连接
             * quorum.auth.serverRequireSasl=true
             * 配置不同的登陆环境
             * # Defaulting to QuorumLearner
             * quorum.auth.learner.loginContext=QuorumLearner
             *
             * # Defaulting to QuorumServer
             * quorum.auth.server.loginContext=QuorumServer
             *
             */
            } else if (key.equals(QuorumAuth.QUORUM_SASL_AUTH_ENABLED)) { // 是否起用 sasl 验证
                quorumEnableSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED)) { //
                quorumServerRequireSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED)) {
                quorumLearnerRequireSasl = Boolean.parseBoolean(value);
            } else if (key.equals(QuorumAuth.QUORUM_LEARNER_SASL_LOGIN_CONTEXT)) {
                quorumLearnerLoginContext = value;
            } else if (key.equals(QuorumAuth.QUORUM_SERVER_SASL_LOGIN_CONTEXT)) {
                quorumServerLoginContext = value;
            } else if (key.equals(QuorumAuth.QUORUM_KERBEROS_SERVICE_PRINCIPAL)) {
                /*
                 * 使用 java 的 jaas 平台支持 kerberos 或则会 digest-md5 验证方式
                 * 在 conf/java.env 中添加
                 * SERVER_JVMFLAGS="-Djava.security.auth.login.config=jaas/file.conf"
                 * file.conf 中指定了验证策略
                 *
                 * 至于具体如何使用，参考 https://cwiki.apache.org/confluence/display/ZOOKEEPER/Server-Server+mutual+authentication
                 */
                quorumServicePrincipal = value;
            } else if (key.equals("quorum.cnxn.threads.size")) {

                /*
                 * 用于集群间通讯的线程池大小，一般取值为 2 倍的集群数量
                 */
                quorumCnxnThreadsSize = Integer.parseInt(value);
            } else {
                System.setProperty("zookeeper." + key, value);
            }
        }

        /* 必须先启动 quorumEnableSasl 才能启动 quorumServerRequireSasl，quorumLearnerRequireSasl */
        if (!quorumEnableSasl && quorumServerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_SASL_AUTH_ENABLED
                    + " is disabled, so cannot enable "
                    + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
        }
        if (!quorumEnableSasl && quorumLearnerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_SASL_AUTH_ENABLED
                    + " is disabled, so cannot enable "
                    + QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED);
        }

        // If quorumpeer learner is not auth enabled then self won't be able to
        // join quorum. So this condition is ensuring that the quorumpeer learner
        // is also auth enabled while enabling quorum server require sasl.
        // quorumServerRequireSasl 开启了则 quorumLearnerRequireSasl 也必须开启
        if (!quorumLearnerRequireSasl && quorumServerRequireSasl) {
            throw new IllegalArgumentException(
                    QuorumAuth.QUORUM_LEARNER_SASL_AUTH_REQUIRED
                    + " is disabled, so cannot enable "
                    + QuorumAuth.QUORUM_SERVER_SASL_AUTH_REQUIRED);
        }
        // Reset to MIN_SNAP_RETAIN_COUNT if invalid (less than 3)
        // PurgeTxnLog.purge(File, File, int) will not allow to purge less
        // than 3.
        // 自动清理快照时会保留一些近期的快照，这个值保留值不能小于 3
        if (snapRetainCount < MIN_SNAP_RETAIN_COUNT) {
            LOG.warn("Invalid autopurge.snapRetainCount: " + snapRetainCount
                    + ". Defaulting to " + MIN_SNAP_RETAIN_COUNT);
            snapRetainCount = MIN_SNAP_RETAIN_COUNT;
        }

        /* 后面都是一些属性的校验 */

        if (dataDir == null) {
            throw new IllegalArgumentException("dataDir is not set");
        }
        if (dataLogDir == null) {
            dataLogDir = dataDir;
        }
        if (clientPort == 0) {
            throw new IllegalArgumentException("clientPort is not set");
        }
        if (clientPortAddress != null) {
            this.clientPortAddress = new InetSocketAddress(
                    InetAddress.getByName(clientPortAddress), clientPort);
        } else {
            this.clientPortAddress = new InetSocketAddress(clientPort);
        }

        if (tickTime == 0) {
            throw new IllegalArgumentException("tickTime is not set");
        }
        if (minSessionTimeout > maxSessionTimeout) {
            throw new IllegalArgumentException(
                    "minSessionTimeout must not be larger than maxSessionTimeout");
        }
        if (servers.size() == 0) {
            if (observers.size() > 0) {
                throw new IllegalArgumentException("Observers w/o participants is an invalid configuration");
            }
            // Not a quorum configuration so return immediately - not an error
            // case (for b/w compatibility), server will default to standalone
            // mode.
            return;
        } else if (servers.size() == 1) {
            if (observers.size() > 0) {
                throw new IllegalArgumentException("Observers w/o quorum is an invalid configuration");
            }

            // HBase currently adds a single server line to the config, for
            // b/w compatibility reasons we need to keep this here.
            LOG.error("Invalid configuration, only one server specified (ignoring)");
            servers.clear();
        } else if (servers.size() > 1) {
            if (servers.size() == 2) {
                LOG.warn("No server failure will be tolerated. " +
                    "You need at least 3 servers.");
            } else if (servers.size() % 2 == 0) {
                LOG.warn("Non-optimial configuration, consider an odd number of servers.");
            }
            if (initLimit == 0) {
                throw new IllegalArgumentException("initLimit is not set");
            }
            if (syncLimit == 0) {
                throw new IllegalArgumentException("syncLimit is not set");
            }
            /*
             * If using FLE, then every server requires a separate election
             * port.
             */
            if (electionAlg != 0) {
                for (QuorumServer s : servers.values()) {
                    if (s.electionAddr == null)
                        throw new IllegalArgumentException(
                                "Missing election port for server: " + s.id);
                }
            }

            /*
             * Default of quorum config is majority
             */
            if(serverGroup.size() > 0){
                if(servers.size() != serverGroup.size())
                    throw new ConfigException("Every server must be in exactly one group");
                /*
                 * The deafult weight of a server is 1
                 */
                for(QuorumServer s : servers.values()){
                    if(!serverWeight.containsKey(s.id))
                        serverWeight.put(s.id, (long) 1);
                }

                /*
                 * Set the quorumVerifier to be QuorumHierarchical
                 */
                quorumVerifier = new QuorumHierarchical(numGroups,
                        serverWeight, serverGroup);
            } else {
                /*
                 * The default QuorumVerifier is QuorumMaj
                 */

                LOG.info("Defaulting to majority quorums");
                quorumVerifier = new QuorumMaj(servers.size());
            }

            // Now add observers to servers, once the quorums have been
            // figured out
            servers.putAll(observers);

            File myIdFile = new File(dataDir, "myid");
            if (!myIdFile.exists()) {
                throw new IllegalArgumentException(myIdFile.toString()
                        + " file is missing");
            }
            BufferedReader br = new BufferedReader(new FileReader(myIdFile));
            String myIdString;
            try {
                myIdString = br.readLine();
            } finally {
                br.close();
            }
            try {
                serverId = Long.parseLong(myIdString);
                MDC.put("myid", myIdString);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("serverid " + myIdString
                        + " is not a number");
            }
            
            // Warn about inconsistent peer type
            LearnerType roleByServersList = observers.containsKey(serverId) ? LearnerType.OBSERVER
                    : LearnerType.PARTICIPANT;
            if (roleByServersList != peerType) {
                LOG.warn("Peer type from servers list (" + roleByServersList
                        + ") doesn't match peerType (" + peerType
                        + "). Defaulting to servers list.");
    
                peerType = roleByServersList;
            }
        }
    } // parseProperties 函数结束

    public InetSocketAddress getClientPortAddress() { return clientPortAddress; }
    public String getDataDir() { return dataDir; }
    public String getDataLogDir() { return dataLogDir; }
    public int getTickTime() { return tickTime; }
    public int getMaxClientCnxns() { return maxClientCnxns; }
    public int getMinSessionTimeout() { return minSessionTimeout; }
    public int getMaxSessionTimeout() { return maxSessionTimeout; }

    public int getInitLimit() { return initLimit; }
    public int getSyncLimit() { return syncLimit; }
    public int getElectionAlg() { return electionAlg; }
    public int getElectionPort() { return electionPort; }
    
    public int getSnapRetainCount() {
        return snapRetainCount;
    }

    public int getPurgeInterval() {
        return purgeInterval;
    }
    
    public boolean getSyncEnabled() {
        return syncEnabled;
    }

    public QuorumVerifier getQuorumVerifier() {
        return quorumVerifier;
    }

    public Map<Long,QuorumServer> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    public long getServerId() { return serverId; }

    public boolean isDistributed() { return servers.size() > 1; }

    public LearnerType getPeerType() {
        return peerType;
    }

    public Boolean getQuorumListenOnAllIPs() {
        return quorumListenOnAllIPs;
    }
}
