/*
 * Copyright 2012 NGDATA nv
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.sep.impl;

import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.replication.regionserver.WALEntrySinkFilter;

import com.google.common.base.Preconditions;

/**
 * A callback facility to filter out all replication WAL entries that arrive from irrelevant hbase
 * source tables or that stem from a time before an indexer was created.
 * 
 * This class is called from the slave region server's ReplicationSink.
 */
public final class SepWALEntrySinkFilter implements WALEntrySinkFilter {

    private SepConnectionParams params;
    
    public SepWALEntrySinkFilter() {}
    
    @Override 
    // Called from ReplicationSink.setupWALEntrySinkFilter()
    public void init(Connection connection) {
        this.params = ((SepConnection) connection).getParams();
        Preconditions.checkNotNull(this.params);
    }
  
    @Override 
    // Called from ReplicationSink.replicateEntries()
    public boolean filter(TableName table, long writeTime) {
        if (!params.getTableNamePredicate().apply(table)) {
            return true;
        }
        return writeTime < params.getSubscriptionTimestamp();
    }
}
