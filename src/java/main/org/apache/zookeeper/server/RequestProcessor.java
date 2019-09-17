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

/**
 * RequestProcessors are chained together to process transactions. Requests are
 * always processed in order. The standalone server, follower, and leader all
 * have slightly different RequestProcessors chained together.
 *
 * 请求处理以链的方式在一个事务中，他们总是按顺序执行。
 * 单机模式，leader，follower 它们在请求链上有些许不同
 * 
 * Requests always move forward through the chain of RequestProcessors. Requests
 * are passed to a RequestProcessor through processRequest(). Generally method
 * will always be invoked by a single thread.
 *
 * processRequest 方法一个一个调用，一般是在单个线程
 * 
 * When shutdown is called, the request RequestProcessor should also shutdown
 * any RequestProcessors that it is connected to.
 *
 * 如果 shutdown 被调用，那么所有都需要被调用关闭
 *
 * 继承该接口的类主要有：
 *
 *     AckRequestProcessor，将前一阶段的请求作为ACK转发给Leader。
 *
 * 　　CommitProcessor，将到来的请求与本地提交的请求进行匹配，这是因为改变系统状态的本地请求的返回结果是到来的请求。
 *
 * 　　FinalRequestProcessor，通常是请求处理链的最后一个处理器。
 *
 * 　　FollowerRequestProcessor，将修改了系统状态的请求转发给Leader。
 *
 * 　　ObserverRequestProcessor，同FollowerRequestProcessor一样，将修改了系统状态的请求转发给Leader。
 *
 * 　　PrepRequestProcessor，通常是请求处理链的第一个处理器。
 *
 * 　　ProposalRequestProcessor，将请求转发给AckRequestProcessor和SyncRequestProcessor。
 *
 * 　　ReadOnlyRequestProcessor，是ReadOnlyZooKeeperServer请求处理链的第一个处理器，将只读请求传递给下个处理器，抛弃改变状态的请求。
 *
 * 　　SendAckRequestProcessor，发送ACK请求的处理器。
 *
 * 　　SyncRequestProcessor，发送Sync请求的处理器。
 *
 * 　　ToBeAppliedRequestProcessor，维护toBeApplied列表，下个处理器必须是FinalRequestProcessor并且FinalRequestProcessor必须同步处理请求。
 *
 * 　　UnimplementedRequestProcessor，用于管理未知请求。
 *
 */
public interface RequestProcessor {
    @SuppressWarnings("serial")
    public static class RequestProcessorException extends Exception {
        public RequestProcessorException(String msg, Throwable t) {
            super(msg, t);
        }
    }

    void processRequest(Request request) throws RequestProcessorException;

    void shutdown();
}
