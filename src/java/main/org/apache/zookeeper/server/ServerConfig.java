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

import java.net.InetSocketAddress;
import java.util.Arrays;

import org.apache.yetus.audience.InterfaceAudience;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig;
import org.apache.zookeeper.server.quorum.QuorumPeerConfig.ConfigException;

/**
 * 该类只用于单机服务
 * 用来解析和存储服务器端的配置
 *
 * Server configuration storage.
 * We use this instead of Properties as it's typed.
 *
 */
@InterfaceAudience.Public
public class ServerConfig {
    ////
    //// If you update the configuration parameters be sure
    //// to update the "conf" 4letter word
    ////
    protected InetSocketAddress clientPortAddress; // 表示服务器地址，不是客户端地址哈
    protected String dataDir;
    protected String dataLogDir;
    protected int tickTime = ZooKeeperServer.DEFAULT_TICK_TIME;
    protected int maxClientCnxns;
    /** defaults to -1 if not set explicitly */
    protected int minSessionTimeout = -1; // 会话超时时间限制，最小
    /** defaults to -1 if not set explicitly */
    protected int maxSessionTimeout = -1;  // 会话超时时间限制，最大

    /**
     * 当未指定配置文件时，使用该方法
     * Parse arguments for server configuration
     * 解析客户端的配置
     * @param args clientPort dataDir and optional tickTime and
     *             客户端端口号，data目录 和 可供选择的 tickTime
     * @return ServerConfig configured wrt arguments
     * @throws IllegalArgumentException on invalid usage
     */
    public void parse(String[] args) {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException("Invalid number of arguments:" + Arrays.toString(args));
        }

        clientPortAddress = new InetSocketAddress(Integer.parseInt(args[0]));
        dataDir = args[1];
        dataLogDir = dataDir;
        if (args.length >= 3) {
            tickTime = Integer.parseInt(args[2]);
        }
        if (args.length == 4) {
            maxClientCnxns = Integer.parseInt(args[3]);
        }
    }

    /**
     * Parse a ZooKeeper configuration file
     * 解析单机服务器端的配置
     * @param path the patch of the configuration file
     *             配置文件的路径
     * @return ServerConfig configured wrt arguments
     * @throws ConfigException error processing configuration
     */
    public void parse(String path) throws ConfigException {
        // 使用集群的配置文件解析器解析，然后再从中读需要的部分
        QuorumPeerConfig config = new QuorumPeerConfig();
        config.parse(path);

        // let qpconfig parse the file and then pull the stuff we are
        // interested in
        // 将解析好的内容读取到单机服务器的配置存储类
        readFrom(config);
    }

    /**
     * Read attributes from a QuorumPeerConfig.
     * 从集群配置中读取单机服务需要的配置
     * @param config
     */
    public void readFrom(QuorumPeerConfig config) {
      clientPortAddress = config.getClientPortAddress();
      dataDir = config.getDataDir();
      dataLogDir = config.getDataLogDir();
      tickTime = config.getTickTime();
      maxClientCnxns = config.getMaxClientCnxns();
      minSessionTimeout = config.getMinSessionTimeout();
      maxSessionTimeout = config.getMaxSessionTimeout();
    }

    public InetSocketAddress getClientPortAddress() {
        return clientPortAddress;
    }
    public String getDataDir() { return dataDir; }
    public String getDataLogDir() { return dataLogDir; }
    public int getTickTime() { return tickTime; }
    public int getMaxClientCnxns() { return maxClientCnxns; }
    /** minimum session timeout in milliseconds, -1 if unset */
    public int getMinSessionTimeout() { return minSessionTimeout; }
    /** maximum session timeout in milliseconds, -1 if unset */
    public int getMaxSessionTimeout() { return maxSessionTimeout; }
}
