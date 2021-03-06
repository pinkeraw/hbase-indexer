/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ngdata.hbaseindexer.mr;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.fs.FileStatus;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.common.util.ExecutorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The optional (parallel) GoLive phase merges the output shards of the previous
 * phase into a set of live customer facing Solr servers, typically a SolrCloud.
 */
class GoLive {
  
  static final String INJECT_FOLLOWER_MERGE_FAILURES = "injectGoLiveFollowerFailures"; // for unit tests

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  
  // TODO: handle clusters with replicas
  public boolean goLive(HBaseIndexingOptions options, FileStatus[] outDirs) {
    LOG.info("Live merging of output shards into Solr cluster...");
    boolean success = false;
    long start = System.nanoTime();
    int concurrentMerges = options.goLiveThreads;
    ThreadPoolExecutor executor = new ExecutorUtil.MDCAwareThreadPoolExecutor(concurrentMerges,
        concurrentMerges, 1, TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>());
    
    try {
      CompletionService<Request> completionService = new ExecutorCompletionService<>(executor);
      Set<Future<Request>> pending = new HashSet<>();
      int cnt = -1;
      for (final FileStatus dir : outDirs) {
        
        LOG.debug("processing: " + dir.getPath());

        cnt++;
        final List<String> urls = options.shardUrls.get(cnt);
        final Shard shard = new Shard(urls, options.goLiveMinReplicationFactor);
        LOG.info("Live merging of output shard {} into at least {} out of {} replicas of {}", 
            dir.getPath(), shard.minMergeSuccessesRequired, urls.size(), urls);
        
        for (final String url : urls) {
          
          String baseUrl = url;
          if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
          }
          
          int lastPathIndex = baseUrl.lastIndexOf("/");
          if (lastPathIndex == -1) {
            LOG.error("Found unexpected shardurl, live merge failed: " + baseUrl);
            return false;
          }
          
          final String name = baseUrl.substring(lastPathIndex + 1);
          baseUrl = baseUrl.substring(0, lastPathIndex);
          final String mergeUrl = baseUrl;
          
          Callable<Request> task = new Callable<Request>() {
           @Override
           public Request call() {
            Request req = new Request();
            req.url = url;
            req.isLeader = urls.indexOf(url) == 0;
            req.shard = shard;
            LOG.info("Live merge " + dir.getPath() + " into " + mergeUrl);
            try (final HttpSolrClient client = new HttpSolrClient.Builder(mergeUrl).build()) {
              CoreAdminRequest.MergeIndexes mergeRequest = new CoreAdminRequest.MergeIndexes();
              mergeRequest.setCoreName(name);
              mergeRequest.setIndexDirs(Arrays.asList(dir.getPath().toString() + "/data/index"));
              if (!req.isLeader && System.getProperty(INJECT_FOLLOWER_MERGE_FAILURES) != null) {
                throw new SolrServerException(INJECT_FOLLOWER_MERGE_FAILURES);
              }
              mergeRequest.process(client);
              req.success = true;
            } catch (SolrServerException | IOException e) {
              req.e = e;
            }
            return req;
           }
          };
          pending.add(completionService.submit(task));
        }
      }
      
      while (pending != null && pending.size() > 0) {
        try {
          Future<Request> future = completionService.take();
          if (future == null) break;
          pending.remove(future);
          
          try {
            Request req = future.get();
            req.shard.numMergeRequestsTodo--;
            if (req.success) {
              req.shard.minMergeSuccessesRequired--;
              assert req.shard.numMergeRequestsTodo >= req.shard.minMergeSuccessesRequired;
            } else { // merge request failed
              if (req.isLeader || 
                  req.shard.numMergeRequestsTodo < req.shard.minMergeSuccessesRequired) { 
                String kind = req.isLeader ? "leader" : "follower";
                LOG.error("A required live merge command failed on " + kind + " " + req.url, req.e);
                return false;
              }
              LOG.warn("A live merge command failed on follower " + req.url 
                  + " but it is still possible that a merge command on sufficiently "
                  + "many other followers will succeed so we're happily continuing "
                  + "despite the following exception", req.e);
            }
            
          } catch (ExecutionException e) {
            LOG.error("Error sending live merge command", e);
            return false;
          }
          
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          LOG.error("Live merge process interrupted", e);
          return false;
        }
      }
      
      cnt = -1;
      
      
      try {
        LOG.info("Committing live merge...");
        if (options.zkHost != null) {
          try (CloudSolrClient server = new CloudSolrClient.Builder().withZkHost(options.zkHost).build()) {
            server.setDefaultCollection(options.collection);
            server.commit();
          }
        } else {
          for (List<String> urls : options.shardUrls) {
            for (String url : urls) {
              // TODO: we should do these concurrently
              try (HttpSolrClient server = new HttpSolrClient.Builder(url).build()) {
                server.commit();
              }
            }
          }
        }
        LOG.info("Done committing live merge");
      } catch (Exception e) {
        LOG.error("Error sending commits to live Solr cluster", e);
        return false;
      }

      success = true;
      return true;
    } finally {
      ExecutorUtil.shutdownAndAwaitTermination(executor);
      float secs = (System.nanoTime() - start) / (float)(1.0e9);
      LOG.info("Live merging of index shards into Solr cluster took " + secs + " secs");
      if (success) {
        LOG.info("Live merging completed successfully");
      } else {
        LOG.info("Live merging failed");
      }
    }
    
    // if an output dir does not exist, we should fail and do no merge?
  }
  
  private static final class Request {
    Exception e;
    boolean success = false;
    boolean isLeader = true;
    String url;
    Shard shard;
  }

  
  private static final class Shard {
    
    int numMergeRequestsTodo;
    int minMergeSuccessesRequired;
    
    public Shard(List<String> urls, int minReplicationFactor) {
      numMergeRequestsTodo = urls.size();
      if (minReplicationFactor == -1) {
        minReplicationFactor = urls.size();
      }
      minReplicationFactor = Math.min(minReplicationFactor, urls.size());
      minMergeSuccessesRequired = minReplicationFactor;
    }
  }
}
