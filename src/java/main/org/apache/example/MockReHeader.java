package org.apache.example;

import org.apache.jute.InputArchive;
import org.apache.jute.OutputArchive;
import org.apache.jute.Record;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.apache.jute.BinaryInputArchive;
import org.apache.jute.BinaryOutputArchive;
import org.apache.zookeeper.server.ByteBufferInputStream;

/**
 * 源代码出自 https://www.cnblogs.com/leesf456/p/6091208.html
 *
 */
public class MockReHeader implements Record {
    private long sessionId;
    private String type;
    
    public MockReHeader() {
        
    }
    
    public MockReHeader(long sessionId, String type) {
        this.sessionId = sessionId;
        this.type = type;
    }
    
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public long getSessionId() {
        return sessionId;
    }
    
    public String getType() {
        return type;
    }
    
    public void serialize(OutputArchive oa, String tag) throws java.io.IOException {
        oa.startRecord(this, tag);
        oa.writeLong(sessionId, "sessionId");
        oa.writeString(type, "type");
        oa.endRecord(this, tag);
    }
    
    public void deserialize(InputArchive ia, String tag) throws java.io.IOException {
        ia.startRecord(tag);
        this.sessionId = ia.readLong("sessionId");
        this.type = ia.readString("type");
        ia.endRecord(tag);
    }
    
    @Override
    public String toString() {
        return "sessionId = " + sessionId + ", type = " + type;
    }

    public static void main(String[] args) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BinaryOutputArchive boa = BinaryOutputArchive.getArchive(baos); // output 序列化器
        new MockReHeader(0x3421eccb92a34el, "ping").serialize(boa, "header"); // 通过 output 序列化器序列化

        ByteBuffer bb = ByteBuffer.wrap(baos.toByteArray());

        ByteBufferInputStream bbis = new ByteBufferInputStream(bb);
        BinaryInputArchive bia = BinaryInputArchive.getArchive(bbis); // input 序列化器

        MockReHeader header2 = new MockReHeader();
        System.out.println(header2);
        header2.deserialize(bia, "header"); // 通过 input 序列化器
        System.out.println(header2);
        bbis.close();
        baos.close();
    }
}