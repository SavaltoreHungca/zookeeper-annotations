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

package org.apache.zookeeper.server.persistence;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.zookeeper.server.DataTree;
import org.apache.zookeeper.server.util.SerializeUtils;

/**
 * This class implements the snapshot interface.
 * it is responsible for storing, serializing
 * and deserializing the right snapshot.
 * and provides access to the snapshots.
 */
public class FileSnap implements SnapShot {
    File snapDir;
    private volatile boolean close = false;
    private static final int VERSION=2;
    private static final long dbId=-1;
    private static final Logger LOG = LoggerFactory.getLogger(FileSnap.class);
    public final static int SNAP_MAGIC
        = ByteBuffer.wrap("ZKSN".getBytes()).getInt(); // 值为 1514885966
    public FileSnap(File snapDir) {
        this.snapDir = snapDir;
    }

    /**
     * 反序列化 快照到 DataTree，并返回找到的最大的 zxid
     * deserialize a data tree from the most recent snapshot
     * @return the zxid of the snapshot
     */ 
    public long deserialize(DataTree dt, Map<Long, Integer> sessions)
            throws IOException {
        // we run through 100 snapshots (not all of them)
        // if we cannot get it running within 100 snapshots
        // we should  give up
        List<File> snapList = findNValidSnapshots(100);
        if (snapList.size() == 0) {
            return -1L;
        }
        File snap = null;
        boolean foundValid = false;
        for (int i = 0; i < snapList.size(); i++) {
            snap = snapList.get(i);
            InputStream snapIS = null;
            CheckedInputStream crcIn = null; // 校验文件完整性的流
            try {
                LOG.info("Reading snapshot " + snap);
                snapIS = new BufferedInputStream(new FileInputStream(snap));
                crcIn = new CheckedInputStream(snapIS, new Adler32());
                InputArchive ia = BinaryInputArchive.getArchive(crcIn); // 得到一个具有缓冲，校验crc，的文件输入流
                deserialize(dt,sessions, ia); // 将快照存储的数据发序列化到 dataTree
                long checkSum = crcIn.getChecksum().getValue();
                long val = ia.readLong("val");
                if (val != checkSum) { // 检查完整性
                    throw new IOException("CRC corruption in snapshot :  " + snap);
                }
                foundValid = true;
                break; // 找到合法的了， 直接退出循环
            } catch(IOException e) {
                LOG.warn("problem reading snap file " + snap, e);
            } finally {
                if (snapIS != null) 
                    snapIS.close();
                if (crcIn != null) 
                    crcIn.close();
            } 
        }
        if (!foundValid) {
            throw new IOException("Not able to find valid snapshots in " + snapDir);
        }
        dt.lastProcessedZxid = Util.getZxidFromName(snap.getName(), "snapshot"); // 从快照文件名中获取最大的 zxid
        return dt.lastProcessedZxid;
    }

    /**
     * deserialize the datatree from an inputarchive
     * @param dt the datatree to be serialized into
     * @param sessions the sessions to be filled up
     * @param ia the input archive to restore from
     * @throws IOException
     */
    public void deserialize(DataTree dt, Map<Long, Integer> sessions,
            InputArchive ia) throws IOException {
        FileHeader header = new FileHeader();
        header.deserialize(ia, "fileheader");
        if (header.getMagic() != SNAP_MAGIC) {
            throw new IOException("mismatching magic headers "
                    + header.getMagic() + 
                    " !=  " + FileSnap.SNAP_MAGIC);
        }
        SerializeUtils.deserializeSnapshot(dt,ia,sessions); // 反序列化 dataTree, 并恢复 sessions
    }

    /**
     * 见 findNValidSnapshots
     * find the most recent snapshot in the database.
     * @return the file containing the most recent snapshot
     */
    public File findMostRecentSnapshot() throws IOException {
        List<File> files = findNValidSnapshots(1);
        if (files.size() == 0) {
            return null;
        }
        return files.get(0);
    }
    
    /**
     * 返回合法的快照，但是校验方式比较简单，如果文件内容是以 / 结尾就是合法文件
     * find the last (maybe) valid n snapshots. this does some 
     * minor checks on the validity of the snapshots. It just
     * checks for / at the end of the snapshot. This does
     * not mean that the snapshot is truly valid but is
     * valid with a high probability. also, the most recent 
     * will be first on the list. 
     * @param n the number of most recent snapshots
     * @return the last n snapshots (the number might be
     * less than n in case enough snapshots are not available).
     * @throws IOException
     */
    private List<File> findNValidSnapshots(int n) throws IOException {
        // 按文件名后的 id 倒序排序
        List<File> files = Util.sortDataDir(snapDir.listFiles(),"snapshot", false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f : files) {
            // 不完整的快照去掉，突然宕机等操作可能会造成快照不完整，所以得去掉
            try {
                if (Util.isValidSnapshot(f)) {
                    list.add(f);
                    count++;
                    if (count == n) {
                        break;
                    }
                }
            } catch (IOException e) {
                LOG.info("invalid snapshot " + f, e);
            }
        }
        return list;
    }

    /**
     * 返回最近的快照，并且一点校验也不做
     * find the last n snapshots. this does not have
     * any checks if the snapshot might be valid or not
     * @param the number of most recent snapshots
     * @return the last n snapshots
     * @throws IOException
     */
    public List<File> findNRecentSnapshots(int n) throws IOException {
        List<File> files = Util.sortDataDir(snapDir.listFiles(), "snapshot", false);
        int count = 0;
        List<File> list = new ArrayList<File>();
        for (File f: files) {
            if (count == n)
                break;
            if (Util.getZxidFromName(f.getName(), "snapshot") != -1) {
                count++;
                list.add(f);
            }
        }
        return list;
    }

    /**
     * serialize the datatree and sessions
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param oa the output archive to serialize into
     * @param header the header of this snapshot
     * @throws IOException
     */
    protected void serialize(DataTree dt,Map<Long, Integer> sessions,
            OutputArchive oa, FileHeader header) throws IOException {
        // this is really a programmatic error and not something that can
        // happen at runtime
        if(header==null)
            throw new IllegalStateException(
                    "Snapshot's not open for writing: uninitialized header");
        header.serialize(oa, "fileheader");
        SerializeUtils.serializeSnapshot(dt,oa,sessions);
    }

    /**
     * 将会话和 dataTree 序列化到文件，在没有关闭的前提下
     * serialize the datatree and session into the file snapshot
     * @param dt the datatree to be serialized
     * @param sessions the sessions to be serialized
     * @param snapShot the file to store snapshot into
     */
    public synchronized void serialize(DataTree dt, Map<Long, Integer> sessions, File snapShot)
            throws IOException {
        if (!close) {
            OutputStream sessOS = new BufferedOutputStream(new FileOutputStream(snapShot));
            CheckedOutputStream crcOut = new CheckedOutputStream(sessOS, new Adler32());
            //CheckedOutputStream cout = new CheckedOutputStream()
            OutputArchive oa = BinaryOutputArchive.getArchive(crcOut);
            FileHeader header = new FileHeader(SNAP_MAGIC, VERSION, dbId);  // 文件头信息
            serialize(dt,sessions,oa, header);  // 序列化
            long val = crcOut.getChecksum().getValue();
            oa.writeLong(val, "val"); // 将校验值写入
            oa.writeString("/", "path");
            sessOS.flush();
            crcOut.close();
            sessOS.close();
        }
    }

    /**
     * synchronized close just so that if serialize is in place
     * the close operation will block and will wait till serialize
     * is done and will set the close flag
     */
    @Override
    public synchronized void close() throws IOException {
        close = true;
    }

 }
