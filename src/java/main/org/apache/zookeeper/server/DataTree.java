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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.Code;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.Quotas;
import org.apache.zookeeper.StatsTrack;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZooDefs.OpCode;
import org.apache.zookeeper.common.PathTrie;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;
import org.apache.zookeeper.data.StatPersisted;
import org.apache.zookeeper.server.upgrade.DataNodeV1;
import org.apache.zookeeper.txn.CheckVersionTxn;
import org.apache.zookeeper.txn.CreateTxn;
import org.apache.zookeeper.txn.DeleteTxn;
import org.apache.zookeeper.txn.ErrorTxn;
import org.apache.zookeeper.txn.MultiTxn;
import org.apache.zookeeper.txn.SetACLTxn;
import org.apache.zookeeper.txn.SetDataTxn;
import org.apache.zookeeper.txn.Txn;
import org.apache.zookeeper.txn.TxnHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这个类就是咋们 zookeeper 的节点目录树啦
 *
 * This class maintains the tree data structure. It doesn't have any networking
 * or client connection code in it so that it can be tested in a stand alone
 * way.
 *
 * 该类维护了一个树型的数据结构
 * 不需要任何网络连接，可以单机测试
 *
 * <p>
 * The tree maintains two parallel data structures: a hashtable that maps from
 * full paths to DataNodes and a tree of DataNodes. All accesses to a path is
 * through the hashtable. The tree is traversed only when serializing to disk.
 *
 * 维护了一个hash表， 映射了节点的路径
 *
 * 该类是zookeeper内存数据库存储数据的数据结构
 *
 */
public class DataTree {
    private static final Logger LOG = LoggerFactory.getLogger(DataTree.class);

    /**
     * This hashtable provides a fast lookup to the datanodes. The tree is the
     * source of truth and is where all the locking occurs
     *
     * 提供快速查找节点，完整路径 和 节点的映射，如 /zookeeper/xixi -> DataNode
     */
    private final ConcurrentHashMap<String, DataNode> nodes =
        new ConcurrentHashMap<String, DataNode>();

    private final WatchManager dataWatches = new WatchManager(); // 节点的监控者

    private final WatchManager childWatches = new WatchManager();

    /** the root of zookeeper tree */
    private static final String rootZookeeper = "/"; // 根节点

    /** the zookeeper nodes that acts as the management and status node
     * 该节点扮演管理员的角色
     */
    private static final String procZookeeper = Quotas.procZookeeper; // /zookeeper

    /** this will be the string thats stored as a child of root
     *
     */
    private static final String procChildZookeeper = procZookeeper.substring(1); // zookeeper

    /**
     * the zookeeper quota node that acts as the quota management node for
     * zookeeper
     *
     * 该节点负责管理各个节点的配额信息
     *
     */
    private static final String quotaZookeeper = Quotas.quotaZookeeper; // /zookeeper/quota

    /** this will be the string thats stored as a child of /zookeeper */
    private static final String quotaChildZookeeper = quotaZookeeper
            .substring(procZookeeper.length() + 1);  // quota

    /**
     * the path trie that keeps track fo the quota nodes in this datatree
     *
     * 路径字典树，一种数据结构，根据路径检索时很快
     *
     */
    private final PathTrie pTrie = new PathTrie();

    /**
     * This hashtable lists the paths of the ephemeral nodes of a session.
     *
     * sessionId 和 临时节点的映射
     */
    private final Map<Long, HashSet<String>> ephemerals =
        new ConcurrentHashMap<Long, HashSet<String>>();

    // access control list
    // 访问权限列表
    private final ReferenceCountedACLCache aclCache = new ReferenceCountedACLCache();


    /**
     * 获取指定会话的所有临时节点
     *
     * @param sessionId
     * @return
     */
    @SuppressWarnings("unchecked")
    public HashSet<String> getEphemerals(long sessionId) {
        HashSet<String> retv = ephemerals.get(sessionId);
        if (retv == null) {
            return new HashSet<String>();
        }
        HashSet<String> cloned = null;
        synchronized (retv) {
            cloned = (HashSet<String>) retv.clone(); // 搞了个浅复制， 和 new HashSet<>(retv) 是一样的效果
        }
        return cloned;
    }

    public Map<Long, HashSet<String>> getEphemeralsMap() {
        return ephemerals;
    }


    /**
     * 获取会话集合
     * @return
     */
    public Collection<Long> getSessions() {
        return ephemerals.keySet();
    }

    /**
     * just an accessor method to allow raw creation of datatree's from a bunch
     * of datanodes
     *
     * @param path
     *            the path of the datanode
     * @param node
     *            the datanode corresponding to this path
     */
    public void addDataNode(String path, DataNode node) {
        nodes.put(path, node);
    }

    public DataNode getNode(String path) {
        return nodes.get(path);
    }

    /**
     * @return 节点数
     */
    public int getNodeCount() {
        return nodes.size();
    }


    public int getWatchCount() {
        return dataWatches.size() + childWatches.size();
    }

    /**
     *
     * @return 所有会话的临时节点数
     */
    public int getEphemeralsCount() {
        Map<Long, HashSet<String>> map = this.getEphemeralsMap();
        int result = 0;
        for (HashSet<String> set : map.values()) {
            result += set.size();
        }
        return result;
    }

    /**
     * Get the size of the nodes based on path and data length.
     *
     * 获取目前所有节点已存储的数据量大小
     *
     * @return size of the data
     */
    public long approximateDataSize() {
        long result = 0;
        for (Map.Entry<String, DataNode> entry : nodes.entrySet()) {
            DataNode value = entry.getValue();
            synchronized (value) {
                result += entry.getKey().length();
                result += (value.data == null ? 0
                        : value.data.length);
            }
        }
        return result;
    }

    /**
     * This is a pointer to the root of the DataTree. It is the source of truth,
     * but we usually use the nodes hashmap to find nodes in the tree.
     *
     * 该节点代表路径树的根节点。
     * 不过通常还是通过 hashmap 检索对应路径的节点
     *
     */
    private DataNode root = new DataNode(null, new byte[0], -1L,
            new StatPersisted());

    /**
     * create a /zookeeper filesystem that is the proc filesystem of zookeeper
     *
     * 创建 /zookeeper 节点
     */
    private DataNode procDataNode = new DataNode(root, new byte[0], -1L,
            new StatPersisted());

    /**
     * create a /zookeeper/quota node for maintaining quota properties for
     * zookeeper
     *
     * 创建 /zookeeper/quota 节点用于维护配额
     */
    private DataNode quotaDataNode = new DataNode(procDataNode, new byte[0],
            -1L, new StatPersisted());

    public DataTree() {
        /* Rather than fight it, let root have an alias */
        nodes.put("", root);
        nodes.put(rootZookeeper, root); // "" 和 / 都代表根节点的路径

        /** add the proc node and quota node */
        root.addChild(procChildZookeeper); // 将 /zookeepr 节点作为root的子节点
        nodes.put(procZookeeper, procDataNode); // 将路径和节点的映射添加进 hashmap

        procDataNode.addChild(quotaChildZookeeper); // 同上的方式添加 /zookeeper/quota 节点
        nodes.put(quotaZookeeper, quotaDataNode);
    }

    /**
     * is the path one of the special paths owned by zookeeper.
     *
     * 特殊节点指 /zookeeper
     *          /zookeeper/quota
     *          /
     * 这三个节点
     *
     * @param path
     *            the path to be checked
     * @return true if a special path. false if not.
     */
    boolean isSpecialPath(String path) {
        if (rootZookeeper.equals(path) || procZookeeper.equals(path)
                || quotaZookeeper.equals(path)) {
            return true;
        }
        return false;
    }

    static public void copyStatPersisted(StatPersisted from, StatPersisted to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
    }

    static public void copyStat(Stat from, Stat to) {
        to.setAversion(from.getAversion());
        to.setCtime(from.getCtime());
        to.setCversion(from.getCversion());
        to.setCzxid(from.getCzxid());
        to.setMtime(from.getMtime());
        to.setMzxid(from.getMzxid());
        to.setPzxid(from.getPzxid());
        to.setVersion(from.getVersion());
        to.setEphemeralOwner(from.getEphemeralOwner());
        to.setDataLength(from.getDataLength());
        to.setNumChildren(from.getNumChildren());
    }

    /**
     * update the count of this stat datanode
     *
     * 更新该节点使用的节点数量
     *
     * @param lastPrefix
     *            the path of the node that is quotaed.
     *            被分配配额的路径
     * @param diff
     *            the diff to be added to the count
     *            要添加的节点数
     */
    public void updateCount(String lastPrefix, int diff) {
        String statNode = Quotas.statPath(lastPrefix); // 获取表示节点配额信息的节点路径
        DataNode node = nodes.get(statNode); // 获取当前节点的配额节点
        StatsTrack updatedStat = null;
        if (node == null) { // 一般来说不会出现某个节点没有其相关的配额信息
            // should not happen
            LOG.error("Missing count node for stat " + statNode);
            return;
        }
        synchronized (node) {
            updatedStat = new StatsTrack(new String(node.data));
            updatedStat.setCount(updatedStat.getCount() + diff); // 将该节点使用的节点数 + diff 个
            node.data = updatedStat.toString().getBytes(); // 更新 配额 信息
        }
        // now check if the counts match the quota
        String quotaNode = Quotas.quotaPath(lastPrefix); // 获取配额限制节点
        node = nodes.get(quotaNode);
        StatsTrack thisStats = null;
        if (node == null) { // 同上，一般不可能发生
            // should not happen
            LOG.error("Missing count node for quota " + quotaNode);
            return;
        }
        synchronized (node) {
            thisStats = new StatsTrack(new String(node.data));
        }
        /**
         * thisStats 表示用户对节点做的配额限制，如限制节点数只能 5 个等
         * updatedStat 表示当前节点的配额状态
         *
         * 从这里可以看到，即使我们的节点超出了实际配额，也仅仅只是服务器警告一下。
         */
        if (thisStats.getCount() > -1 && (thisStats.getCount() < updatedStat.getCount())) {
            LOG
            .warn("Quota exceeded: " + lastPrefix + " count="
                    + updatedStat.getCount() + " limit="
                    + thisStats.getCount());
        }
    }

    /**
     * update the count of bytes of this stat datanode
     *
     * 更新节点使用的资源大小，方式同 updateCount 一样，这里不再赘述
     *
     * @param lastPrefix
     *            the path of the node that is quotaed
     * @param diff
     *            the diff to added to number of bytes
     * @throws IOException
     *             if path is not found
     */
    public void updateBytes(String lastPrefix, long diff) {
        String statNode = Quotas.statPath(lastPrefix);
        DataNode node = nodes.get(statNode);
        if (node == null) {
            // should never be null but just to make
            // findbugs happy
            LOG.error("Missing stat node for bytes " + statNode);
            return;
        }
        StatsTrack updatedStat = null;
        synchronized (node) {
            updatedStat = new StatsTrack(new String(node.data));
            updatedStat.setBytes(updatedStat.getBytes() + diff);
            node.data = updatedStat.toString().getBytes();
        }
        // now check if the bytes match the quota
        String quotaNode = Quotas.quotaPath(lastPrefix);
        node = nodes.get(quotaNode);
        if (node == null) {
            // should never be null but just to make
            // findbugs happy
            LOG.error("Missing quota node for bytes " + quotaNode);
            return;
        }
        StatsTrack thisStats = null;
        synchronized (node) {
            thisStats = new StatsTrack(new String(node.data));
        }
        if (thisStats.getBytes() > -1 && (thisStats.getBytes() < updatedStat.getBytes())) {
            LOG
            .warn("Quota exceeded: " + lastPrefix + " bytes="
                    + updatedStat.getBytes() + " limit="
                    + thisStats.getBytes());
        }
    }

    /**
     *
     * 新建节点
     *
     * @param path
     * @param data
     * @param acl
     * @param ephemeralOwner
     *            the session id that owns this node. -1 indicates this is not
     *            an ephemeral node.
     *
     *            如果该值为 -1 表示新增的节点不是临时节点
     *            该值为会话 id 表示新增节点是临时节点
     *
     * @param zxid
     * @param time
     * @return the patch of the created node
     *          返回新建节点的路径
     *
     * @throws KeeperException
     */
    public String createNode(String path, byte data[], List<ACL> acl,
            long ephemeralOwner, int parentCVersion, long zxid, long time)
            throws KeeperException.NoNodeException,
            KeeperException.NodeExistsException {

        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash); // 父节点路径
        String childName = path.substring(lastSlash + 1); // 获取当前节点路径名
        StatPersisted stat = new StatPersisted(); // 存储当前的节点信息，主要用于序列化
        stat.setCtime(time);
        stat.setMtime(time);
        stat.setCzxid(zxid);
        stat.setMzxid(zxid);
        stat.setPzxid(zxid);
        stat.setVersion(0);
        stat.setAversion(0);
        stat.setEphemeralOwner(ephemeralOwner);
        DataNode parent = nodes.get(parentName); // 获取父节点
        if (parent == null) { // 父节点必须存在
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            Set<String> children = parent.getChildren(); // 获取父节点的子节点集合
            if (children.contains(childName)) { // 新建的节点必须是不存在的
                throw new KeeperException.NodeExistsException();
            }

            /* 设置版本信息 */
            if (parentCVersion == -1) {
                parentCVersion = parent.stat.getCversion();
                parentCVersion++;
            }    
            parent.stat.setCversion(parentCVersion);
            parent.stat.setPzxid(zxid); // 设置 事务id

            Long longval = aclCache.convertAcls(acl);

            // child 便是我们此次新建的接待你
            DataNode child = new DataNode(parent, data, longval, stat);

            parent.addChild(childName); // 将 child 加进parent的子节点集合
            nodes.put(path, child); // 将 child 添加进路径映射集合

            if (ephemeralOwner != 0) { // ephemeralOwner 表示的是 sessionid
                HashSet<String> list = ephemerals.get(ephemeralOwner); // 获取临时节点集合
                if (list == null) {
                    list = new HashSet<String>();
                    ephemerals.put(ephemeralOwner, list);
                }
                synchronized (list) {
                    list.add(path); // 间临时节点路径添加进集合
                }
            }
        }
        // now check if its one of the zookeeper node child
        if (parentName.startsWith(quotaZookeeper)) { // 检查创建的是否是配额节点
            // now check if its the limit node
            if (Quotas.limitNode.equals(childName)) {
                // this is the limit node
                // get the parent and add it to the trie
                pTrie.addPath(parentName.substring(quotaZookeeper.length()));
            }
            if (Quotas.statNode.equals(childName)) {
                updateQuotaForPath(parentName
                        .substring(quotaZookeeper.length()));
            }
        }
        // also check to update the quotas for this node
        //
        String lastPrefix;
        if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
            // ok we have some match and need to update
            updateCount(lastPrefix, 1);
            updateBytes(lastPrefix, data == null ? 0 : data.length);
        }
        dataWatches.triggerWatch(path, Event.EventType.NodeCreated);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
                Event.EventType.NodeChildrenChanged);
        return path;
    }

    /**
     * remove the path from the datatree
     *
     * @param path
     *            the path to of the node to be deleted
     * @param zxid
     *            the current zxid
     * @throws KeeperException.NoNodeException
     */
    public void deleteNode(String path, long zxid)
            throws KeeperException.NoNodeException {
        int lastSlash = path.lastIndexOf('/');
        String parentName = path.substring(0, lastSlash);
        String childName = path.substring(lastSlash + 1);
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException();
        }
        nodes.remove(path);
        synchronized (node) {
            aclCache.removeUsage(node.acl);
        }
        DataNode parent = nodes.get(parentName);
        if (parent == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (parent) {
            parent.removeChild(childName);
            parent.stat.setPzxid(zxid);
            long eowner = node.stat.getEphemeralOwner();
            if (eowner != 0) {
                HashSet<String> nodes = ephemerals.get(eowner);
                if (nodes != null) {
                    synchronized (nodes) {
                        nodes.remove(path);
                    }
                }
            }
            node.parent = null;
        }
        if (parentName.startsWith(procZookeeper)) {
            // delete the node in the trie.
            if (Quotas.limitNode.equals(childName)) {
                // we need to update the trie
                // as well
                pTrie.deletePath(parentName.substring(quotaZookeeper.length()));
            }
        }

        // also check to update the quotas for this node
        String lastPrefix;
        if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
            // ok we have some match and need to update
            updateCount(lastPrefix, -1);
            int bytes = 0;
            synchronized (node) {
                bytes = (node.data == null ? 0 : -(node.data.length));
            }
            updateBytes(lastPrefix, bytes);
        }
        if (LOG.isTraceEnabled()) {
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                    "dataWatches.triggerWatch " + path);
            ZooTrace.logTraceMessage(LOG, ZooTrace.EVENT_DELIVERY_TRACE_MASK,
                    "childWatches.triggerWatch " + parentName);
        }
        Set<Watcher> processed = dataWatches.triggerWatch(path,
                EventType.NodeDeleted);
        childWatches.triggerWatch(path, EventType.NodeDeleted, processed);
        childWatches.triggerWatch(parentName.equals("") ? "/" : parentName,
                EventType.NodeChildrenChanged);
    }

    public Stat setData(String path, byte data[], int version, long zxid,
            long time) throws KeeperException.NoNodeException {
        Stat s = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        byte lastdata[] = null;
        synchronized (n) {
            lastdata = n.data;
            n.data = data;
            n.stat.setMtime(time);
            n.stat.setMzxid(zxid);
            n.stat.setVersion(version);
            n.copyStat(s);
        }
        // now update if the path is in a quota subtree.
        String lastPrefix;
        if((lastPrefix = getMaxPrefixWithQuota(path)) != null) {
          this.updateBytes(lastPrefix, (data == null ? 0 : data.length)
              - (lastdata == null ? 0 : lastdata.length));
        }
        dataWatches.triggerWatch(path, EventType.NodeDataChanged);
        return s;
    }

    /**
     * If there is a quota set, return the appropriate prefix for that quota
     * Else return null
     * @param path The ZK path to check for quota
     * @return Max quota prefix, or null if none
     */
    public String getMaxPrefixWithQuota(String path) {
        // do nothing for the root.
        // we are not keeping a quota on the zookeeper
        // root node for now.
        String lastPrefix = pTrie.findMaxPrefix(path);

        if (!rootZookeeper.equals(lastPrefix) && !("".equals(lastPrefix))) {
            return lastPrefix;
        }
        else {
            return null;
        }
    }

    public byte[] getData(String path, Stat stat, Watcher watcher)
            throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            if (watcher != null) {
                dataWatches.addWatch(path, watcher);
            }
            return n.data;
        }
    }

    public Stat statNode(String path, Watcher watcher)
            throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (watcher != null) {
            dataWatches.addWatch(path, watcher);
        }
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return stat;
        }
    }

    public List<String> getChildren(String path, Stat stat, Watcher watcher)
            throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            if (stat != null) {
                n.copyStat(stat);
            }
            List<String> children = new ArrayList<String>(n.getChildren());

            if (watcher != null) {
                childWatches.addWatch(path, watcher);
            }
            return children;
        }
    }

    public Stat setACL(String path, List<ACL> acl, int version)
            throws KeeperException.NoNodeException {
        Stat stat = new Stat();
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            aclCache.removeUsage(n.acl);
            n.stat.setAversion(version);
            n.acl = aclCache.convertAcls(acl);
            n.copyStat(stat);
            return stat;
        }
    }

    @SuppressWarnings("unchecked")
    public List<ACL> getACL(String path, Stat stat)
            throws KeeperException.NoNodeException {
        DataNode n = nodes.get(path);
        if (n == null) {
            throw new KeeperException.NoNodeException();
        }
        synchronized (n) {
            n.copyStat(stat);
            return new ArrayList<ACL>(aclCache.convertLong(n.acl));
        }
    }

    public List<ACL> getACL(DataNode node) {
        synchronized (node) {
            return aclCache.convertLong(node.acl);
        }
    }

    public Long getACL(DataNodeV1 oldDataNode) {
        synchronized (oldDataNode) {
            return aclCache.convertAcls(oldDataNode.acl);
        }
    }

    public int aclCacheSize() {
        return aclCache.size();
    }

    static public class ProcessTxnResult {
        public long clientId;

        public int cxid;

        public long zxid;

        public int err;

        public int type;

        public String path;

        public Stat stat;

        public List<ProcessTxnResult> multiResult;
        
        /**
         * Equality is defined as the clientId and the cxid being the same. This
         * allows us to use hash tables to track completion of transactions.
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object o) {
            if (o instanceof ProcessTxnResult) {
                ProcessTxnResult other = (ProcessTxnResult) o;
                return other.clientId == clientId && other.cxid == cxid;
            }
            return false;
        }

        /**
         * See equals() to find the rational for how this hashcode is generated.
         *
         * @see ProcessTxnResult#equals(Object)
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return (int) ((clientId ^ cxid) % Integer.MAX_VALUE);
        }

    }

    public volatile long lastProcessedZxid = 0;

    /**
     * processTxn用于处理事务，即将事务操作应用到DataTree内存数据库中，以恢复成最新的数据
     *
     * @param header
     * @param txn
     * @return
     */
    public ProcessTxnResult processTxn(TxnHeader header, Record txn)
    {
        ProcessTxnResult rc = new ProcessTxnResult();

        try {
            rc.clientId = header.getClientId();
            rc.cxid = header.getCxid();
            rc.zxid = header.getZxid();
            rc.type = header.getType();
            rc.err = 0;
            rc.multiResult = null;
            switch (header.getType()) { // 事务类型
                case OpCode.create: // 创建节点
                    CreateTxn createTxn = (CreateTxn) txn;
                    rc.path = createTxn.getPath();
                    createNode(
                            createTxn.getPath(),
                            createTxn.getData(),
                            createTxn.getAcl(),
                            createTxn.getEphemeral() ? header.getClientId() : 0,
                            createTxn.getParentCVersion(),
                            header.getZxid(), header.getTime()); // 在 dataTree 中创建节点
                    break;
                case OpCode.delete: // 删除节点
                    DeleteTxn deleteTxn = (DeleteTxn) txn;
                    rc.path = deleteTxn.getPath();
                    deleteNode(deleteTxn.getPath(), header.getZxid());
                    break;
                case OpCode.setData: // 为节点设置值
                    SetDataTxn setDataTxn = (SetDataTxn) txn;
                    rc.path = setDataTxn.getPath();
                    rc.stat = setData(setDataTxn.getPath(), setDataTxn
                            .getData(), setDataTxn.getVersion(), header
                            .getZxid(), header.getTime());
                    break;
                case OpCode.setACL: // 设置节点访问权限
                    SetACLTxn setACLTxn = (SetACLTxn) txn;
                    rc.path = setACLTxn.getPath();
                    rc.stat = setACL(setACLTxn.getPath(), setACLTxn.getAcl(),
                            setACLTxn.getVersion());
                    break;
                case OpCode.closeSession: // 关闭会话
                    killSession(header.getClientId(), header.getZxid());
                    break;
                case OpCode.error: // 错误的事务，将事务错误信息塞入rc
                    ErrorTxn errTxn = (ErrorTxn) txn;
                    rc.err = errTxn.getErr();
                    break;
                case OpCode.check:
                    CheckVersionTxn checkTxn = (CheckVersionTxn) txn;
                    rc.path = checkTxn.getPath();
                    break;
                case OpCode.multi: // 多事务
                    MultiTxn multiTxn = (MultiTxn) txn ;
                    List<Txn> txns = multiTxn.getTxns();
                    rc.multiResult = new ArrayList<ProcessTxnResult>();
                    boolean failed = false;
                    for (Txn subtxn : txns) {   // 有一个事务失败，则算全都失败
                        if (subtxn.getType() == OpCode.error) {
                            failed = true;
                            break;
                        }
                    }

                    boolean post_failed = false; // 提交是否失败
                    for (Txn subtxn : txns) { // 事务提交类型
                        ByteBuffer bb = ByteBuffer.wrap(subtxn.getData()); // 获取提交事务的数据
                        Record record = null;
                        switch (subtxn.getType()) {
                            case OpCode.create:
                                record = new CreateTxn();
                                break;
                            case OpCode.delete:
                                record = new DeleteTxn();
                                break;
                            case OpCode.setData:
                                record = new SetDataTxn();
                                break;
                            case OpCode.error:
                                record = new ErrorTxn();
                                post_failed = true;
                                break;
                            case OpCode.check:
                                record = new CheckVersionTxn();
                                break;
                            default:
                                throw new IOException("Invalid type of op: " + subtxn.getType());
                        }
                        assert(record != null);

                        // 将提交的事务数据转换为实体
                        ByteBufferInputStream.byteBuffer2Record(bb, record);
                       
                        if (failed && subtxn.getType() != OpCode.error){ // 失败并且不为error类型
                            int ec = post_failed ? Code.RUNTIMEINCONSISTENCY.intValue() 
                                                 : Code.OK.intValue();

                            subtxn.setType(OpCode.error);
                            record = new ErrorTxn(ec);
                        }

                        if (failed) {
                            assert(subtxn.getType() == OpCode.error) ;
                        }

                        TxnHeader subHdr = new TxnHeader(header.getClientId(), header.getCxid(),
                                                         header.getZxid(), header.getTime(), 
                                                         subtxn.getType());
                        ProcessTxnResult subRc = processTxn(subHdr, record); // 为每个事务再次单独处理
                        rc.multiResult.add(subRc);
                        if (subRc.err != 0 && rc.err == 0) {
                            rc.err = subRc.err ;
                        }
                    }
                    break;
            }  // 多事务结束------
        } catch (KeeperException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed: " + header + ":" + txn, e);
            }
            rc.err = e.code().intValue();
        } catch (IOException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Failed: " + header + ":" + txn, e);
            }
        }
        /*
         * A snapshot might be in progress while we are modifying the data
         * 一个 snapshot 的恢复进程可能在我们修改 dataTree 时进行
         * tree. If we set lastProcessedZxid prior to making corresponding
         * 如果我们在操作 dataTree 之前设置 lastProcessedZxid，那么 snapshot 的zxid
         * change to the tree, then the zxid associated with the snapshot
         * 会超前他本身的内容。
         * file will be ahead of its contents. Thus, while restoring from
         * the snapshot, the restore method will not apply the transaction
         * 因此在恢复快照的过程中，restore 函数不会
         * for zxid associated with the snapshot file, since the restore
         * method assumes that transaction to be present in the snapshot.
         *
         * To avoid this, we first apply the transaction and then modify
         * lastProcessedZxid.  During restore, we correctly handle the
         * case where the snapshot contains data ahead of the zxid associated
         * with the file.
         */
        if (rc.zxid > lastProcessedZxid) {
        	lastProcessedZxid = rc.zxid;
        }

        /*
         * Snapshots are taken lazily. It can happen that the child
         * znodes of a parent are created after the parent
         * is serialized. Therefore, while replaying logs during restore, a
         * create might fail because the node was already
         * created.
         *
         * After seeing this failure, we should increment
         * the cversion of the parent znode since the parent was serialized
         * before its children.
         *
         * Note, such failures on DT should be seen only during
         * restore.
         */
        if (header.getType() == OpCode.create &&
                rc.err == Code.NODEEXISTS.intValue()) {
            LOG.debug("Adjusting parent cversion for Txn: " + header.getType() +
                    " path:" + rc.path + " err: " + rc.err);
            int lastSlash = rc.path.lastIndexOf('/');
            String parentName = rc.path.substring(0, lastSlash);
            CreateTxn cTxn = (CreateTxn)txn;
            try {
                setCversionPzxid(parentName, cTxn.getParentCVersion(),
                        header.getZxid());
            } catch (KeeperException.NoNodeException e) {
                LOG.error("Failed to set parent cversion for: " +
                      parentName, e);
                rc.err = e.code().intValue();
            }
        } else if (rc.err != Code.OK.intValue()) {
            LOG.debug("Ignoring processTxn failure hdr: " + header.getType() +
                  " : error: " + rc.err);
        }
        return rc;
    }

    void killSession(long session, long zxid) {
        // the list is already removed from the ephemerals
        // so we do not have to worry about synchronizing on
        // the list. This is only called from FinalRequestProcessor
        // so there is no need for synchronization. The list is not
        // changed here. Only create and delete change the list which
        // are again called from FinalRequestProcessor in sequence.
        HashSet<String> list = ephemerals.remove(session);
        if (list != null) {
            for (String path : list) {
                try {
                    deleteNode(path, zxid);
                    if (LOG.isDebugEnabled()) {
                        LOG
                                .debug("Deleting ephemeral node " + path
                                        + " for session 0x"
                                        + Long.toHexString(session));
                    }
                } catch (NoNodeException e) {
                    LOG.warn("Ignoring NoNodeException for path " + path
                            + " while removing ephemeral for dead session 0x"
                            + Long.toHexString(session));
                }
            }
        }
    }

    /**
     * a encapsultaing class for return value
     */
    private static class Counts {
        long bytes;
        int count;
    }

    /**
     * this method gets the count of nodes and the bytes under a subtree
     *
     * @param path
     *            the path to be used
     * @param counts
     *            the int count
     */
    private void getCounts(String path, Counts counts) {
        DataNode node = getNode(path);
        if (node == null) {
            return;
        }
        String[] children = null;
        int len = 0;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
            len = (node.data == null ? 0 : node.data.length);
        }
        // add itself
        counts.count += 1;
        counts.bytes += len;
        for (String child : children) {
            getCounts(path + "/" + child, counts);
        }
    }

    /**
     * update the quota for the given path
     *
     * @param path
     *            the path to be used
     */
    private void updateQuotaForPath(String path) {
        Counts c = new Counts();
        getCounts(path, c);
        StatsTrack strack = new StatsTrack();
        strack.setBytes(c.bytes);
        strack.setCount(c.count);
        String statPath = Quotas.quotaZookeeper + path + "/" + Quotas.statNode;
        DataNode node = getNode(statPath);
        // it should exist
        if (node == null) {
            LOG.warn("Missing quota stat node " + statPath);
            return;
        }
        synchronized (node) {
            node.data = strack.toString().getBytes();
        }
    }

    /**
     * this method traverses the quota path and update the path trie and sets
     *
     * @param path
     */
    private void traverseNode(String path) {
        DataNode node = getNode(path);
        String children[] = null;
        synchronized (node) {
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        if (children.length == 0) {
            // this node does not have a child
            // is the leaf node
            // check if its the leaf node
            String endString = "/" + Quotas.limitNode;
            if (path.endsWith(endString)) {
                // ok this is the limit node
                // get the real node and update
                // the count and the bytes
                String realPath = path.substring(Quotas.quotaZookeeper
                        .length(), path.indexOf(endString));
                updateQuotaForPath(realPath);
                this.pTrie.addPath(realPath);
            }
            return;
        }
        for (String child : children) {
            traverseNode(path + "/" + child);
        }
    }

    /**
     * this method sets up the path trie and sets up stats for quota nodes
     */
    private void setupQuota() {
        String quotaPath = Quotas.quotaZookeeper;
        DataNode node = getNode(quotaPath);
        if (node == null) {
            return;
        }
        traverseNode(quotaPath);
    }

    /**
     * this method uses a stringbuilder to create a new path for children. This
     * is faster than string appends ( str1 + str2).
     *
     * @param oa
     *            OutputArchive to write to.
     * @param path
     *            a string builder.
     * @throws IOException
     * @throws InterruptedException
     */
    void serializeNode(OutputArchive oa, StringBuilder path) throws IOException {
        String pathString = path.toString();
        DataNode node = getNode(pathString);
        if (node == null) {
            return;
        }
        String children[] = null;
        DataNode nodeCopy;
        synchronized (node) {
            scount++;
            StatPersisted statCopy = new StatPersisted();
            copyStatPersisted(node.stat, statCopy);
            //we do not need to make a copy of node.data because the contents
            //are never changed
            nodeCopy = new DataNode(node.parent, node.data, node.acl, statCopy);
            Set<String> childs = node.getChildren();
            children = childs.toArray(new String[childs.size()]);
        }
        oa.writeString(pathString, "path");
        oa.writeRecord(nodeCopy, "node");
        path.append('/');
        int off = path.length();
        for (String child : children) {
            // since this is single buffer being resused
            // we need
            // to truncate the previous bytes of string.
            path.delete(off, Integer.MAX_VALUE);
            path.append(child);
            serializeNode(oa, path);
        }
    }

    int scount;

    public boolean initialized = false;

    public void serialize(OutputArchive oa, String tag) throws IOException {
        scount = 0;
        aclCache.serialize(oa);
        serializeNode(oa, new StringBuilder(""));
        // / marks end of stream
        // we need to check if clear had been called in between the snapshot.
        if (root != null) {
            oa.writeString("/", "path");
        }
    }

    public void deserialize(InputArchive ia, String tag) throws IOException {
        aclCache.deserialize(ia);
        nodes.clear();
        pTrie.clear();
        String path = ia.readString("path");
        while (!path.equals("/")) {
            DataNode node = new DataNode();
            ia.readRecord(node, "node");
            nodes.put(path, node);
            synchronized (node) {
                aclCache.addUsage(node.acl);
            }
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash == -1) {
                root = node;
            } else {
                String parentPath = path.substring(0, lastSlash);
                node.parent = nodes.get(parentPath);
                if (node.parent == null) {
                    throw new IOException("Invalid Datatree, unable to find " +
                            "parent " + parentPath + " of path " + path);
                }
                node.parent.addChild(path.substring(lastSlash + 1));
                long eowner = node.stat.getEphemeralOwner();
                if (eowner != 0) {
                    HashSet<String> list = ephemerals.get(eowner);
                    if (list == null) {
                        list = new HashSet<String>();
                        ephemerals.put(eowner, list);
                    }
                    list.add(path);
                }
            }
            path = ia.readString("path");
        }
        nodes.put("/", root);
        // we are done with deserializing the
        // the datatree
        // update the quotas - create path trie
        // and also update the stat nodes
        setupQuota();

        aclCache.purgeUnused();
    }

    /**
     * Summary of the watches on the datatree.
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatchesSummary(PrintWriter pwriter) {
        pwriter.print(dataWatches.toString());
    }

    /**
     * Write a text dump of all the watches on the datatree.
     * Warning, this is expensive, use sparingly!
     * @param pwriter the output to write to
     */
    public synchronized void dumpWatches(PrintWriter pwriter, boolean byPath) {
        dataWatches.dumpWatches(pwriter, byPath);
    }

    /**
     * Write a text dump of all the ephemerals in the datatree.
     * @param pwriter the output to write to
     */
    public void dumpEphemerals(PrintWriter pwriter) {
        Set<Map.Entry<Long, HashSet<String>>> entrySet = ephemerals.entrySet();
        pwriter.println("Sessions with Ephemerals ("
                + entrySet.size() + "):");
        for (Map.Entry<Long, HashSet<String>> entry : entrySet) {
            pwriter.print("0x" + Long.toHexString(entry.getKey()));
            pwriter.println(":");
            HashSet<String> tmp = entry.getValue();
            if (tmp != null) {
                synchronized (tmp) {
                    for (String path : tmp) {
                        pwriter.println("\t" + path);
                    }
                }
            }
        }
    }

    public void removeCnxn(Watcher watcher) {
        dataWatches.removeWatcher(watcher);
        childWatches.removeWatcher(watcher);
    }

    public void clear() {
        root = null;
        nodes.clear();
        ephemerals.clear();
    }

    public void setWatches(long relativeZxid, List<String> dataWatches,
            List<String> existWatches, List<String> childWatches,
            Watcher watcher) {
        for (String path : dataWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted,
                            KeeperState.SyncConnected, path));
            } else if (node.stat.getMzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeDataChanged,
                            KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : existWatches) {
            DataNode node = getNode(path);
            if (node != null) {
                watcher.process(new WatchedEvent(EventType.NodeCreated,
                            KeeperState.SyncConnected, path));
            } else {
                this.dataWatches.addWatch(path, watcher);
            }
        }
        for (String path : childWatches) {
            DataNode node = getNode(path);
            if (node == null) {
                watcher.process(new WatchedEvent(EventType.NodeDeleted,
                            KeeperState.SyncConnected, path));
            } else if (node.stat.getPzxid() > relativeZxid) {
                watcher.process(new WatchedEvent(EventType.NodeChildrenChanged,
                            KeeperState.SyncConnected, path));
            } else {
                this.childWatches.addWatch(path, watcher);
            }
        }
    }

     /**
      * This method sets the Cversion and Pzxid for the specified node to the
      * values passed as arguments. The values are modified only if newCversion
      * is greater than the current Cversion. A NoNodeException is thrown if
      * a znode for the specified path is not found.
      *
      * @param path
      *     Full path to the znode whose Cversion needs to be modified.
      *     A "/" at the end of the path is ignored.
      * @param newCversion
      *     Value to be assigned to Cversion
      * @param zxid
      *     Value to be assigned to Pzxid
      * @throws KeeperException.NoNodeException
      *     If znode not found.
      **/
    public void setCversionPzxid(String path, int newCversion, long zxid)
        throws KeeperException.NoNodeException {
        if (path.endsWith("/")) {
           path = path.substring(0, path.length() - 1);
        }
        DataNode node = nodes.get(path);
        if (node == null) {
            throw new KeeperException.NoNodeException(path);
        }
        synchronized (node) {
            if(newCversion == -1) {
                newCversion = node.stat.getCversion() + 1;
            }
            if (newCversion > node.stat.getCversion()) {
                node.stat.setCversion(newCversion);
                node.stat.setPzxid(zxid);
            }
        }
    }
}
