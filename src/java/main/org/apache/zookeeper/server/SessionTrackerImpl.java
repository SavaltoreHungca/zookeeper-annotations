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
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.SessionExpiredException;
import org.apache.zookeeper.common.Time;

/**
 * This is a full featured SessionTracker. It tracks session in grouped by tick
 * interval. It always rounds up the tick interval to provide a sort of grace
 * period. Sessions are thus expired in batches made up of sessions that expire
 * in a given interval.
 *
 * SessionTracker 的实现类.
 * 按照 tick interval 将会话分组，所以会话会按组失效
 *
 */
public class SessionTrackerImpl extends ZooKeeperCriticalThread implements SessionTracker {
    private static final Logger LOG = LoggerFactory.getLogger(SessionTrackerImpl.class);

    /**
     * 按 会话id 和会话映射
     *
     * 该集合里存在的会话可能已经超时了或者未超时
     */
    HashMap<Long, SessionImpl> sessionsById = new HashMap<Long, SessionImpl>();

    /**
     * 按下次超时时间将会话分组
     *
     * 如果有某个分组的会话到了超时时间，那么它会被立即移除，具体实现看 run 方法
     */
    HashMap<Long, SessionSet> sessionSets = new HashMap<Long, SessionSet>();

    ConcurrentHashMap<Long, Integer> sessionsWithTimeout; // 记录每个会话的超时时间
    long nextSessionId = 0;
    long nextExpirationTime; // 下一次超时时间，currentTime + expirationInterval 就是下次超时时间

    int expirationInterval; // 超时间隔

    /**
     * 代表 会话 的数据结构
     */
    public static class SessionImpl implements Session {
        SessionImpl(long sessionId, int timeout, long expireTime) {
            this.sessionId = sessionId;
            this.timeout = timeout;
            this.tickTime = expireTime;
            isClosing = false;
        }

        final long sessionId;
        final int timeout;
        long tickTime;
        boolean isClosing;

        Object owner;

        public long getSessionId() { return sessionId; }
        public int getTimeout() { return timeout; }
        public boolean isClosing() { return isClosing; }
    }

    /**
     * 根据本机的 sid 初始化一个 sessionid
     */
    public static long initializeNextSession(long id) {
        long nextSid = 0;
        nextSid = (Time.currentElapsedTime() << 24) >>> 8;
        nextSid =  nextSid | (id <<56);
        return nextSid;
    }

    static class SessionSet {
        HashSet<SessionImpl> sessions = new HashSet<SessionImpl>();
    }

    SessionExpirer expirer;

    private long roundToInterval(long time) {
        // We give a one interval grace period
        return (time / expirationInterval + 1) * expirationInterval;
    }

    public SessionTrackerImpl(SessionExpirer expirer,
            ConcurrentHashMap<Long, Integer> sessionsWithTimeout, int tickTime,
            long sid, ZooKeeperServerListener listener)
    {
        super("SessionTracker", listener);
        this.expirer = expirer;
        this.expirationInterval = tickTime;
        this.sessionsWithTimeout = sessionsWithTimeout;
        nextExpirationTime = roundToInterval(Time.currentElapsedTime());
        this.nextSessionId = initializeNextSession(sid);
        for (Entry<Long, Integer> e : sessionsWithTimeout.entrySet()) {
            addSession(e.getKey(), e.getValue());
        }
    }

    volatile boolean running = true;

    volatile long currentTime;

    /**
     * 将当前所有会话的信息导出
     * @param pwriter the output writer
     */
    synchronized public void dumpSessions(PrintWriter pwriter) {
        pwriter.print("Session Sets (");
        pwriter.print(sessionSets.size());
        pwriter.println("):");
        ArrayList<Long> keys = new ArrayList<Long>(sessionSets.keySet());
        Collections.sort(keys);
        for (long time : keys) {
            pwriter.print(sessionSets.get(time).sessions.size());
            pwriter.print(" expire at ");
            pwriter.print(new Date(time));
            pwriter.println(":");
            for (SessionImpl s : sessionSets.get(time).sessions) {
                pwriter.print("\t0x");
                pwriter.println(Long.toHexString(s.sessionId));
            }
        }
    }

    @Override
    synchronized public String toString() {
        StringWriter sw = new StringWriter();
        PrintWriter pwriter = new PrintWriter(sw);
        dumpSessions(pwriter);
        pwriter.flush();
        pwriter.close();
        return sw.toString();
    }

    /**
     * 只要处于 running 状态就不停检查是否有超时的会话，有则将其清理
     */
    @Override
    synchronized public void run() {
        try {
            while (running) { // 是否运行中？
                currentTime = Time.currentElapsedTime(); // 当前时间
                if (nextExpirationTime > currentTime) { // 还没到超时时间
                    this.wait(nextExpirationTime - currentTime); // 等待，将线程阻塞
                    continue;
                }
                SessionSet set;
                set = sessionSets.remove(nextExpirationTime); // 将超时的所有会话移除
                if (set != null) {
                    for (SessionImpl s : set.sessions) {
                        setSessionClosing(s.sessionId); // 将超时的会话状态设置为 关闭中
                        expirer.expire(s); // 交给专门处理超时会话的类处理超时会话
                    }
                }
                nextExpirationTime += expirationInterval; // 更新下次超时时间
            }
        } catch (InterruptedException e) {
            handleException(this.getName(), e);
        }
        LOG.info("SessionTrackerImpl exited loop!");
    }

    /**
     * 检查会话状态
     * 而 checkSession 是用于检查会话是否绑定到某个对象
     * @param sessionId
     * @param timeout
     * @return false 如果会话不存在或已关闭
     *          true 会话健康的
     */
    synchronized public boolean touchSession(long sessionId, int timeout) {
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG,
                                     ZooTrace.CLIENT_PING_TRACE_MASK,
                                     "SessionTrackerImpl --- Touch session: 0x"
                    + Long.toHexString(sessionId) + " with timeout " + timeout);
        }
        SessionImpl s = sessionsById.get(sessionId); // 根据 sessionId 获取已存储的会话
        // Return false, if the session doesn't exists or marked as closing
        // 如果会话已经关闭或者不存在则直接返回 false
        if (s == null || s.isClosing()) {
            return false;
        }

        long expireTime = roundToInterval(Time.currentElapsedTime() + timeout);
        if (s.tickTime >= expireTime) {
            // Nothing needs to be done
            // 会话未超时
            return true;
        }

        /* s.tickTime < expireTime 会话已经超时*/

        SessionSet set = sessionSets.get(s.tickTime);
        if (set != null) {
            set.sessions.remove(s); // 将改组超时的会话全移除
        }
        s.tickTime = expireTime;
        set = sessionSets.get(s.tickTime);
        if (set == null) {
            set = new SessionSet();
            sessionSets.put(expireTime, set);
        }
        set.sessions.add(s); // 将会话存入该超时组
        return true;
    }

    /**
     * 将指定的会话设置为关闭中
     * @param sessionId
     */
    synchronized public void setSessionClosing(long sessionId) {
        if (LOG.isTraceEnabled()) {
            LOG.info("Session closing: 0x" + Long.toHexString(sessionId));
        }
        SessionImpl s = sessionsById.get(sessionId);
        if (s == null) {
            return;
        }
        s.isClosing = true;
    }

    /**
     * 从 sessionsById， sessionsWithTimeout， sessionSets 中移除会话
     * @param sessionId
     */
    synchronized public void removeSession(long sessionId) {
        SessionImpl s = sessionsById.remove(sessionId);
        sessionsWithTimeout.remove(sessionId);
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                    "SessionTrackerImpl --- Removing session 0x"
                    + Long.toHexString(sessionId));
        }
        if (s != null) {
            SessionSet set = sessionSets.get(s.tickTime);
            // Session expiration has been removing the sessions   
            if(set != null){
                set.sessions.remove(s);
            }
        }
    }

    /**
     * 将运行状态设置为 false
     */
    public void shutdown() {
        LOG.info("Shutting down");

        running = false;
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.getTextTraceLevel(),
                                     "Shutdown SessionTrackerImpl!");
        }
    }


    /**
     * 调用 addSession 函数添加会话
     * 并自增 nextSessionId
     *
     * @param sessionTimeout
     * @return
     */
    synchronized public long createSession(int sessionTimeout) {
        addSession(nextSessionId, sessionTimeout);
        return nextSessionId++;
    }

    /**
     * 如果是之前不存在的新会话，则添加
     *
     * 最后调用 touchSession
     *
     * @param id
     * @param sessionTimeout
     */
    synchronized public void addSession(long id, int sessionTimeout) {
        sessionsWithTimeout.put(id, sessionTimeout); // 记录会话超时时间
        if (sessionsById.get(id) == null) { // 是新的会话
            SessionImpl s = new SessionImpl(id, sessionTimeout, 0); // 创建会话
            sessionsById.put(id, s); // 存储会话
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Adding session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        } else {
            if (LOG.isTraceEnabled()) {
                ZooTrace.logTraceMessage(LOG, ZooTrace.SESSION_TRACE_MASK,
                        "SessionTrackerImpl --- Existing session 0x"
                        + Long.toHexString(id) + " " + sessionTimeout);
            }
        }
        touchSession(id, sessionTimeout); // 检查按超时时间分组的会话集合
    }

    /**
     * 检查会话是否绑定到 某个 owner
     * @param sessionId
     * @param owner
     * @throws KeeperException.SessionExpiredException
     * @throws KeeperException.SessionMovedException
     */
    synchronized public void checkSession(long sessionId, Object owner) throws KeeperException.SessionExpiredException, KeeperException.SessionMovedException {
        SessionImpl session = sessionsById.get(sessionId);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        if (session.owner == null) {
            session.owner = owner;
        } else if (session.owner != owner) {
            throw new KeeperException.SessionMovedException();
        }
    }

    /**
     * 将该会话绑定到 owner 上
     * @param id
     * @param owner
     * @throws SessionExpiredException
     */
    synchronized public void setOwner(long id, Object owner) throws SessionExpiredException {
        SessionImpl session = sessionsById.get(id);
        if (session == null || session.isClosing()) {
            throw new KeeperException.SessionExpiredException();
        }
        session.owner = owner;
    }
}
