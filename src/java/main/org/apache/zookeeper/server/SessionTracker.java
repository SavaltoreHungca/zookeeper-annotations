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

import java.io.PrintWriter;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.KeeperException.SessionMovedException;

/**
 * This is the basic interface that ZooKeeperServer uses to track sessions. The
 * standalone and leader ZooKeeperServer use the same SessionTracker. The
 * FollowerZooKeeperServer uses a SessionTracker which is basically a simple
 * shell to track information to be forwarded to the leader.
 *
 * 追踪 Session 和管理 Session 的存活周期
 *
 */
public interface SessionTracker {
    public static interface Session {
        long getSessionId();
        int getTimeout();
        boolean isClosing();
    }
    public static interface SessionExpirer {
        void expire(Session session);

        long getServerId();
    }

    /**
     * 根据超时事件创建一个 session
     */
    long createSession(int sessionTimeout);

    /**
     * 添加会话
     * @param id
     * @param to
     */
    void addSession(long id, int to);

    /**
     * @param sessionId
     * @param sessionTimeout
     * @return false if session is no longer active
     */
    boolean touchSession(long sessionId, int sessionTimeout);

    /**
     * Mark that the session is in the process of closing.
     *
     * 标记 会话 关闭中
     * @param sessionId
     */
    void setSessionClosing(long sessionId);

    /**
     * 关闭会话管理
     */
    void shutdown();

    /**
     * 移除会话
     *
     * @param sessionId
     */
    void removeSession(long sessionId);

    /**
     * 检查该 会话 是否有拥有者，如果没有则抛出异常
     * @param sessionId
     * @param owner
     * @throws KeeperException.SessionExpiredException
     * @throws SessionMovedException
     */
    void checkSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, SessionMovedException;

    void setOwner(long id, Object owner) throws SessionExpiredException;

    /**
     * Text dump of session information, suitable for debugging.
     *
     * 导出现存的会话用于查看
     *
     * @param pwriter the output writer
     */
    void dumpSessions(PrintWriter pwriter);
}
