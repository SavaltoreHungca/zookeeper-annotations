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

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import javax.management.JMException;

import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.jmx.ManagedUtil;
import org.apache.zookeeper.server.persistence.FileTxnSnapLog;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 * This class starts and runs a standalone ZooKeeperServer.
 * 启动 ZooKeeperServer 单机运行
 */
@InterfaceAudience.Public
public class ZooKeeperServerMain {
    private static final Logger LOG =
        LoggerFactory.getLogger(ZooKeeperServerMain.class);

    private static final String USAGE =
        "Usage: ZooKeeperServerMain configfile | port datadir [ticktime] [maxcnxns]";

    private ServerCnxnFactory cnxnFactory; // 生产表示与客户端连接实例的工厂

    /*
     * Start up the ZooKeeper server.
     *
     * @param args the configfile or the port datadir [ticktime]
     *  启动参数为 配置文件 或 端口号 data目录 [ticktime]
     *  这里启动参数使用 conf/zoo_sample.cfg 可启动起来
     */
    public static void main(String[] args) {
        ZooKeeperServerMain main = new ZooKeeperServerMain(); // 新建实例，空的构造器
        try {
            main.initializeAndRun(args); // 初始化单机服务
        } catch (IllegalArgumentException e) { // 错误的参数
            LOG.error("Invalid arguments, exiting abnormally", e);
            LOG.info(USAGE);
            System.err.println(USAGE);
            System.exit(2);
        } catch (ConfigException e) { // 错误的配置
            LOG.error("Invalid config, exiting abnormally", e);
            System.err.println("Invalid config, exiting abnormally");
            System.exit(2);
        } catch (Exception e) { // 未知的异常
            LOG.error("Unexpected exception, exiting abnormally", e);
            System.exit(1);
        }
        LOG.info("Exiting normally");
        System.exit(0);
    }

    protected void initializeAndRun(String[] args)
        throws ConfigException, IOException
    {
        try {
            ManagedUtil.registerLog4jMBeans(); // 为 Log4j 注册 jmx
        } catch (JMException e) {
            LOG.warn("Unable to register log4j JMX control", e);
        }

        ServerConfig config = new ServerConfig();
        if (args.length == 1) {
            config.parse(args[0]); // 此时指定的是配置文件
        } else {
            config.parse(args); // 此时指定的是 port datadir [ticktime]
        }

        runFromConfig(config); // 准备启动服务
    }

    /**
     * 解析好配置后，开始读取配置启动服务了，记住，这里是单机服务
     * Run from a ServerConfig.
     * @param config ServerConfig to use.
     * @throws IOException
     */
    public void runFromConfig(ServerConfig config) throws IOException {
        LOG.info("Starting server");
        FileTxnSnapLog txnLog = null; // 日志和快照操作类
        try {
            // Note that this thread isn't going to be doing anything else,
            // so rather than spawning another thread, we will just call
            // run() in this thread.
            // create a file logger url from the command line args
            // 实例化单机服务的 zk 服务器
            //  该构造器将设置一个用于统计数据的 serverStats 对象
            //  和一个用于监听 zk 服务是否异常的监听器
            final ZooKeeperServer zkServer = new ZooKeeperServer();
            // Registers shutdown handler which will be used to know the
            // server error or shutdown state changes.
            // shutdownLatch 是一个计数器，当发生错误或者关闭事件，则其值减一，当 shutdownLatch 为零时，服务将关闭
            final CountDownLatch shutdownLatch = new CountDownLatch(1);
            zkServer.registerServerShutdownHandler(
                    new ZooKeeperServerShutdownHandler(shutdownLatch)); // 注册 zk 的异常关闭处理器

            txnLog = new FileTxnSnapLog(new File(config.dataLogDir), new File(
                    config.dataDir)); // 初始化日志和快照类

            // 为单机服务添加必要的配置和组件
            zkServer.setTxnLogFactory(txnLog); // 简单的 Setter 方法
            zkServer.setTickTime(config.tickTime);
            zkServer.setMinSessionTimeout(config.minSessionTimeout);
            zkServer.setMaxSessionTimeout(config.maxSessionTimeout);

            // serverCnxnFactory 用于创建和客户端的连接
            // createFactory() 是为了检查是否有指定其他实现类
            // ServerCnxnFactory 默认采用的实现类是 NIOServerCnxnFactory
            cnxnFactory = ServerCnxnFactory.createFactory();

            // 传入该服务器的地址和最大允许客户端的连接数，
            // 默认使用的
            cnxnFactory.configure(config.getClientPortAddress(),
                    config.getMaxClientCnxns());

            cnxnFactory.startup(zkServer); // 启动守护进程 serverCnxnFactory
            // Watch status of ZooKeeper server. It will do a graceful shutdown
            // if the server is not running or hits an internal error.
            shutdownLatch.await(); // 挂起主线程，当shutdownLatch为零时执行后面的代码
            shutdown(); // 关闭服务

            cnxnFactory.join(); // 等待 cnxnFactory 执行完毕，其处理关闭和客户端的连接
            if (zkServer.canShutdown()) { // 如果服务器状态处于 RUNNING 或者 ERROR 时即允许关闭；这是因为服务器可能已经处于关闭状态就不必再关闭了
                zkServer.shutdown(true);
            }
        } catch (InterruptedException e) { // 意外的线程中断，但一般情况下这是允许发生的
            // warn, but generally this is ok
            LOG.warn("Server interrupted", e);
        } finally {
            if (txnLog != null) {
                txnLog.close(); // 快照和日志做关闭处理
            }
        }
    }

    /**
     * Shutdown the serving instance
     */
    protected void shutdown() {
        if (cnxnFactory != null) {
            cnxnFactory.shutdown();
        }
    }

    // VisibleForTesting
    ServerCnxnFactory getCnxnFactory() {
        return cnxnFactory;
    }
}
