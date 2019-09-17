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
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginException;
import javax.security.auth.login.AppConfigurationEntry;

import javax.management.JMException;

import org.apache.zookeeper.Login;
import org.apache.zookeeper.Environment;
import org.apache.zookeeper.jmx.MBeanRegistry;
import org.apache.zookeeper.server.auth.SaslServerCallbackHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用于生产表示客户端连接的工厂类，产品是 ServerCnxn
 * 其实现类有两个：
 *      1。 NIOServerCnxnFactory  默认使用
 *      2。 NettyServerCnxnFactory
 *
 */
public abstract class ServerCnxnFactory {

    public static final String ZOOKEEPER_SERVER_CNXN_FACTORY = "zookeeper.serverCnxnFactory";

    /**
     * 接收到 packet 后所使用的处理器
     */
    public interface PacketProcessor {
        public void processPacket(ByteBuffer packet, ServerCnxn src);
    }
    
    private static final Logger LOG = LoggerFactory.getLogger(ServerCnxnFactory.class);

    // sessionMap is used to speed up closeSession()
    // 用来提升关闭会话的速度
    // 映射关系是 sessionId 和 ServerCnxn
    protected final ConcurrentMap<Long, ServerCnxn> sessionMap =
            new ConcurrentHashMap<Long, ServerCnxn>();

    /**
     * The buffer will cause the connection to be close when we do a send.
     *
     * 这个用来通知客户端会话关闭，用于ServerCnxn
     */
    static final ByteBuffer closeConn = ByteBuffer.allocate(0);

    public abstract int getLocalPort(); // 由子类实现
    
    public abstract Iterable<ServerCnxn> getConnections(); // 由子类实现

    /**
     * 获取存活的连接
     * @return
     */
    public int getNumAliveConnections() {
        synchronized(cnxns) {
            return cnxns.size();
        }
    }

    ZooKeeperServer getZooKeeperServer() {
        return zkServer;
    }

    public abstract void closeSession(long sessionId); // 由子类实现

    // 由子类实现
    public abstract void configure(InetSocketAddress addr,
                                   int maxClientCnxns) throws IOException;

    // jaas 框架所使用的回调处理器
    protected SaslServerCallbackHandler saslServerCallbackHandler;
    public Login login; // jaas 框架所使用

    /** Maximum number of connections allowed from particular host (ip) */
    public abstract int getMaxClientCnxnsPerHost();

    /** Maximum number of connections allowed from particular host (ip) */
    public abstract void setMaxClientCnxnsPerHost(int max);

    public abstract void startup(ZooKeeperServer zkServer)
        throws IOException, InterruptedException;

    public abstract void join() throws InterruptedException;

    public abstract void shutdown();

    public abstract void start();

    protected ZooKeeperServer zkServer;
    final public void setZooKeeperServer(ZooKeeperServer zk) {
        this.zkServer = zk;
        if (zk != null) {
            zk.setServerCnxnFactory(this);
        }
    }

    public abstract void closeAll(); // 关闭所有与客户端的连接

    /**
     * 创建生产客户端连接的工厂实例
     * @return
     * @throws IOException
     */
    static public ServerCnxnFactory createFactory() throws IOException {
        String serverCnxnFactoryName =
            System.getProperty(ZOOKEEPER_SERVER_CNXN_FACTORY); // 检查是否有指定该抽象类的其他实现
        if (serverCnxnFactoryName == null) {
            serverCnxnFactoryName = NIOServerCnxnFactory.class.getName();
        }
        try {
            ServerCnxnFactory serverCnxnFactory = (ServerCnxnFactory) Class.forName(serverCnxnFactoryName)
                    .getDeclaredConstructor().newInstance();
            LOG.info("Using {} as server connection factory", serverCnxnFactoryName);
            return serverCnxnFactory; // 通过反射创建实例
        } catch (Exception e) {
            IOException ioe = new IOException("Couldn't instantiate "
                    + serverCnxnFactoryName);
            ioe.initCause(e);
            throw ioe;
        }
    }
    
    static public ServerCnxnFactory createFactory(int clientPort,
            int maxClientCnxns) throws IOException
    {
        return createFactory(new InetSocketAddress(clientPort), maxClientCnxns);
    }

    static public ServerCnxnFactory createFactory(InetSocketAddress addr,
            int maxClientCnxns) throws IOException
    {
        ServerCnxnFactory factory = createFactory();
        factory.configure(addr, maxClientCnxns); // 调用子类实现的 configure 函数
        return factory;
    }

    public abstract InetSocketAddress getLocalAddress();

    // jmx 框架所使用
    private final Map<ServerCnxn, ConnectionBean> connectionBeans
        = new ConcurrentHashMap<ServerCnxn, ConnectionBean>();

    protected final HashSet<ServerCnxn> cnxns = new HashSet<ServerCnxn>();
    public void unregisterConnection(ServerCnxn serverCnxn) {
        ConnectionBean jmxConnectionBean = connectionBeans.remove(serverCnxn);
        if (jmxConnectionBean != null){
            MBeanRegistry.getInstance().unregister(jmxConnectionBean);
        }
    }
    
    public void registerConnection(ServerCnxn serverCnxn) {
        if (zkServer != null) {
            ConnectionBean jmxConnectionBean = new ConnectionBean(serverCnxn, zkServer);
            try {
                MBeanRegistry.getInstance().register(jmxConnectionBean, zkServer.jmxServerBean);
                connectionBeans.put(serverCnxn, jmxConnectionBean);
            } catch (JMException e) {
                LOG.warn("Could not register connection", e);
            }
        }

    }

    public void addSession(long sessionId, ServerCnxn cnxn) {
        sessionMap.put(sessionId, cnxn);
    }

    /**
     * Initialize the server SASL if specified.
     *
     * 初始化 SASL 安全认证机制
     *
     * If the user has specified a "ZooKeeperServer.LOGIN_CONTEXT_NAME_KEY"
     * or a jaas.conf using "java.security.auth.login.config"
     * the authentication is required and an exception is raised.
     * Otherwise no authentication is configured and no exception is raised.
     *
     * 如果用户有指定 ZooKeeperServer.LOGIN_CONTEXT_NAME_KEY 或 java.security.auth.login.config 那么将启用安全认证
     *
     * @throws IOException if jaas.conf is missing or there's an error in it.
     */
    protected void configureSaslLogin() throws IOException {
        String serverSection = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY,
                                                  ZooKeeperSaslServer.DEFAULT_LOGIN_CONTEXT_NAME);

        // Note that 'Configuration' here refers to javax.security.auth.login.Configuration.
        AppConfigurationEntry entries[] = null;
        SecurityException securityException = null;
        try {
            entries = Configuration.getConfiguration().getAppConfigurationEntry(serverSection);
        } catch (SecurityException e) {
            // handle below: might be harmless if the user doesn't intend to use JAAS authentication.
            securityException = e;
        }

        // No entries in jaas.conf
        // If there's a configuration exception fetching the jaas section and
        // the user has required sasl by specifying a LOGIN_CONTEXT_NAME_KEY or a jaas file
        // we throw an exception otherwise we continue without authentication.
        if (entries == null) {
            String jaasFile = System.getProperty(Environment.JAAS_CONF_KEY);
            String loginContextName = System.getProperty(ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY);
            if (securityException != null && (loginContextName != null || jaasFile != null)) {
                String errorMessage = "No JAAS configuration section named '" + serverSection +  "' was found";
                if (jaasFile != null) {
                    errorMessage += "in '" + jaasFile + "'.";
                }
                if (loginContextName != null) {
                    errorMessage += " But " + ZooKeeperSaslServer.LOGIN_CONTEXT_NAME_KEY + " was set.";
                }
                LOG.error(errorMessage);
                throw new IOException(errorMessage);
            }
            return;
        }

        // jaas.conf entry available
        try {
            saslServerCallbackHandler = new SaslServerCallbackHandler(Configuration.getConfiguration());
            login = new Login(serverSection, saslServerCallbackHandler);
            login.startThreadIfNeeded();
        } catch (LoginException e) {
            throw new IOException("Could not configure server because SASL configuration did not allow the "
              + " ZooKeeper server to authenticate itself properly: " + e);
        }
    }
}
