/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.ipc;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Abortable;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;

import com.google.common.annotations.VisibleForTesting;

/**
 * {@link RpcScheduler} that first checks to see if this is an index or metedata update before passing off the
 * call to the delegate {@link RpcScheduler}.
 */
public class PhoenixRpcScheduler extends RpcScheduler {

    // copied from org.apache.hadoop.hbase.ipc.SimpleRpcScheduler in HBase 0.98.4
    private static final String CALL_QUEUE_HANDLER_FACTOR_CONF_KEY = "ipc.server.callqueue.handler.factor";
    private static final String CALLQUEUE_LENGTH_CONF_KEY = "ipc.server.max.callqueue.length";
    private static final int DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER = 10;

    private RpcScheduler delegate;
    private int indexPriority;
    private int metadataPriority;
    private RpcExecutor indexCallExecutor;
    private RpcExecutor metadataCallExecutor;
    private int port;
    

    public PhoenixRpcScheduler(Configuration conf, RpcScheduler delegate, int indexPriority, int metadataPriority, PriorityFunction priorityFunction, Abortable abortable) {
        // copied from org.apache.hadoop.hbase.ipc.SimpleRpcScheduler in HBase 0.98.4
    	int indexHandlerCount = conf.getInt(QueryServices.INDEX_HANDLER_COUNT_ATTRIB, QueryServicesOptions.DEFAULT_INDEX_HANDLER_COUNT);
    	int metadataHandlerCount = conf.getInt(QueryServices.METADATA_HANDLER_COUNT_ATTRIB, QueryServicesOptions.DEFAULT_INDEX_HANDLER_COUNT);
        int maxIndexQueueLength =  conf.getInt(CALLQUEUE_LENGTH_CONF_KEY, indexHandlerCount*DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);
        int maxMetadataQueueLength =  conf.getInt(CALLQUEUE_LENGTH_CONF_KEY, metadataHandlerCount*DEFAULT_MAX_CALLQUEUE_LENGTH_PER_HANDLER);

        this.indexPriority = indexPriority;
        this.metadataPriority = metadataPriority;
        this.delegate = delegate;
        this.indexCallExecutor = new BalancedQueueRpcExecutor("Index", indexHandlerCount, maxIndexQueueLength, priorityFunction,conf,abortable);
        this.metadataCallExecutor = new BalancedQueueRpcExecutor("Metadata", metadataHandlerCount, maxMetadataQueueLength, priorityFunction,conf,abortable);
    }

    @Override
    public void init(Context context) {
        delegate.init(context);
        this.port = context.getListenerAddress().getPort();
    }

    @Override
    public void start() {
        delegate.start();
        indexCallExecutor.start(port);
        metadataCallExecutor.start(port);
    }

    @Override
    public void stop() {
        delegate.stop();
        indexCallExecutor.stop();
        metadataCallExecutor.stop();
    }

    @Override
    public boolean dispatch(CallRunner callTask) throws InterruptedException, IOException {
        ServerCall call = callTask.getCall();
        int priority = call.getHeader().getPriority();
        if (indexPriority == priority) {
            return indexCallExecutor.dispatch(callTask);
        } else if (metadataPriority == priority) {
            return metadataCallExecutor.dispatch(callTask);
        } else {
            return delegate.dispatch(callTask);
        }
    }

    @Override
    public CallQueueInfo getCallQueueInfo() {
        return delegate.getCallQueueInfo();
    }

    @Override
    public int getGeneralQueueLength() {
        // not the best way to calculate, but don't have a better way to hook
        // into metrics at the moment
        return this.delegate.getGeneralQueueLength() + this.indexCallExecutor.getQueueLength() + this.metadataCallExecutor.getQueueLength();
    }

    @Override
    public int getPriorityQueueLength() {
        return this.delegate.getPriorityQueueLength();
    }

    @Override
    public int getMetaPriorityQueueLength() {
        return this.delegate.getMetaPriorityQueueLength();
    }

    @Override
    public int getReplicationQueueLength() {
        return this.delegate.getReplicationQueueLength();
    }

    @Override
    public int getActiveRpcHandlerCount() {
        return this.delegate.getActiveRpcHandlerCount() + this.indexCallExecutor.getActiveHandlerCount() + this.metadataCallExecutor.getActiveHandlerCount();
    }

    @Override
    public long getNumGeneralCallsDropped() {
        return delegate.getNumGeneralCallsDropped();
    }

    @Override
    public long getNumLifoModeSwitches() {
        return delegate.getNumLifoModeSwitches();
    }

    @VisibleForTesting
    public void setIndexExecutorForTesting(RpcExecutor executor) {
        this.indexCallExecutor = executor;
    }
    
    @VisibleForTesting
    public void setMetadataExecutorForTesting(RpcExecutor executor) {
        this.metadataCallExecutor = executor;
    }

    @Override
    public int getWriteQueueLength() {
        return delegate.getWriteQueueLength();
    }

    @Override
    public int getReadQueueLength() {
        return delegate.getReadQueueLength();
    }

    @Override
    public int getScanQueueLength() {
        return delegate.getScanQueueLength();
    }

    @Override
    public int getActiveWriteRpcHandlerCount() {
        return delegate.getActiveWriteRpcHandlerCount();
    }

    @Override
    public int getActiveReadRpcHandlerCount() {
        return delegate.getActiveReadRpcHandlerCount();
    }

    @Override
    public int getActiveScanRpcHandlerCount() {
        return delegate.getActiveScanRpcHandlerCount();
    }
    
    
}
