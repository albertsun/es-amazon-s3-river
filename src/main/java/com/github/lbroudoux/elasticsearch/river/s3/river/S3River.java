/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.github.lbroudoux.elasticsearch.river.s3.river;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

import java.util.Arrays;
import java.util.Map;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.river.AbstractRiverComponent;
import org.elasticsearch.river.River;
import org.elasticsearch.river.RiverName;
import org.elasticsearch.river.RiverSettings;

import com.github.lbroudoux.elasticsearch.river.s3.connector.S3Changes;
import com.github.lbroudoux.elasticsearch.river.s3.connector.S3Connector;
/**
 * 
 * @author laurent
 */
public class S3River extends AbstractRiverComponent implements River{

   private final Client client;
   
   private final String indexName;

   private final String typeName;

   private final long bulkSize;
   
   private volatile Thread feedThread;
   
   private volatile boolean closed = false;
   
   private final S3RiverFeedDefinition feedDefinition;
   
   private final S3Connector s3;
   
   
   @Inject
   @SuppressWarnings({ "unchecked" })
   protected S3River(RiverName riverName, RiverSettings settings, Client client) throws Exception{
      super(riverName, settings);
      this.client = client;
      
      // Deal with connector settings.
      if (settings.settings().containsKey("amazon-s3")){
         Map<String, Object> feed = (Map<String, Object>)settings.settings().get("amazon-s3");
         
         // Retrieve feed settings.
         String feedname = XContentMapValues.nodeStringValue(feed.get("name"), null);
         String bucket = XContentMapValues.nodeStringValue(feed.get("bucket"), null);
         String pathPrefix = XContentMapValues.nodeStringValue(feed.get("pathPrefix"), null);
         int updateRate = XContentMapValues.nodeIntegerValue(feed.get("update_rate"), 15 * 60 * 1000);
         
         String[] includes = S3RiverUtil.buildArrayFromSettings(settings.settings(), "amazon-s3.includes");
         String[] excludes = S3RiverUtil.buildArrayFromSettings(settings.settings(), "amazon-s3.excludes");
         
         // Retrieve connection settings.
         String accessKey = XContentMapValues.nodeStringValue(feed.get("accessKey"), null);
         String secretKey = XContentMapValues.nodeStringValue(feed.get("secretKey"), null);
         
         feedDefinition = new S3RiverFeedDefinition(feedname, bucket, pathPrefix, updateRate, 
               Arrays.asList(includes), Arrays.asList(excludes), accessKey, secretKey);
      } else {
         logger.error("You didn't define the amazon-s3 settings. Exiting... See https://github.com/lbroudoux/es-amazon-s3-river");
         indexName = null;
         typeName = null;
         bulkSize = 100;
         feedDefinition = null;
         s3 = null;
         return;
      }
      
      // Deal with index settings if provided.
      if (settings.settings().containsKey("index")) {
         Map<String, Object> indexSettings = (Map<String, Object>)settings.settings().get("index");
         
         indexName = XContentMapValues.nodeStringValue(
               indexSettings.get("index"), riverName.name());
         typeName = XContentMapValues.nodeStringValue(
               indexSettings.get("type"), S3RiverUtil.INDEX_TYPE_DOC);
         bulkSize = XContentMapValues.nodeLongValue(
               indexSettings.get("bulk_size"), 100);
      } else {
         indexName = riverName.name();
         typeName = S3RiverUtil.INDEX_TYPE_DOC;
         bulkSize = 100;
      }
      
      // We need to connect to Amazon S3.
      s3 = new S3Connector(feedDefinition.getAccessKey(), feedDefinition.getSecretKey());
      s3.connectUserBucket(feedDefinition.getBucket(), feedDefinition.getPathPrefix());
   }
   
   @Override
   public void start(){
      if (logger.isInfoEnabled()){
         logger.info("Starting amazon s3 river scanning");
      }
      try{
         client.admin().indices().prepareCreate(indexName).execute().actionGet();
      } catch (Exception e) {
         if (ExceptionsHelper.unwrapCause(e) instanceof IndexAlreadyExistsException){
            // that's fine.
         } else if (ExceptionsHelper.unwrapCause(e) instanceof ClusterBlockException){
            // ok, not recovered yet..., lets start indexing and hope we recover by the first bulk.
         } else {
            logger.warn("failed to create index [{}], disabling river...", e, indexName);
            return;
         }
      }
      
      // We create as many Threads as there are feeds.
      feedThread = EsExecutors.daemonThreadFactory(settings.globalSettings(), "fs_slurper")
            .newThread(new S3Scanner(feedDefinition));
      feedThread.start();
   }
   
   @Override
   public void close(){
      if (logger.isInfoEnabled()){
         logger.info("Closing amazon s3 river");
      }
      closed = true;
      
      // We have to close the Thread.
      if (feedThread != null){
         feedThread.interrupt();
      }
   }
   
   /**
    * Check if a mapping already exists in an index
    * @param index Index name
    * @param type Mapping name
    * @return true if mapping exists
    */
   private boolean isMappingExist(String index, String type) {
      ClusterState cs = client.admin().cluster().prepareState()
            .setFilterIndices(index).execute().actionGet()
            .getState();
      // Check index metadata existence.
      IndexMetaData imd = cs.getMetaData().index(index);
      if (imd == null){
         return false;
      }
      // Check mapping metadata existence.
      MappingMetaData mdd = imd.mapping(type);
      if (mdd != null){
         return true;
      }
      return false;
   }
   
   private void pushMapping(String index, String type, XContentBuilder xcontent) throws Exception {
      if (logger.isTraceEnabled()){
         logger.trace("pushMapping(" + index + ", " + type + ")");
      }
      
      // If type does not exist, we create it
      boolean mappingExist = isMappingExist(index, type);
      if (!mappingExist) {
         logger.debug("Mapping [" + index + "]/[" + type + "] doesn't exist. Creating it.");

         // Read the mapping json file if exists and use it.
         if (xcontent != null){
            if (logger.isTraceEnabled()){
               logger.trace("Mapping for [" + index + "]/[" + type + "]=" + xcontent.string());
            }
            // Create type and mapping
            PutMappingResponse response = client.admin().indices()
                  .preparePutMapping(index)
                  .setType(type)
                  .setSource(xcontent)
                  .execute().actionGet();       
            if (!response.isAcknowledged()){
               throw new Exception("Could not define mapping for type [" + index + "]/[" + type + "].");
            } else {
               if (logger.isDebugEnabled()){
                  if (mappingExist){
                     logger.debug("Mapping definition for [" + index + "]/[" + type + "] succesfully merged.");
                  } else {
                     logger.debug("Mapping definition for [" + index + "]/[" + type + "] succesfully created.");
                  }
               }
            }
         } else {
            if (logger.isDebugEnabled()){
               logger.debug("No mapping definition for [" + index + "]/[" + type + "]. Ignoring.");
            }
         }
      } else {
         if (logger.isDebugEnabled()){
            logger.debug("Mapping [" + index + "]/[" + type + "] already exists and mergeMapping is not set.");
         }
      }
      if (logger.isTraceEnabled()){
         logger.trace("/pushMapping(" + index + ", " + type + ")");
      }
   }
   
   /** */
   private class S3Scanner implements Runnable{
      
      private BulkRequestBuilder bulk;
      private S3RiverFeedDefinition feedDefinition;
      
      public S3Scanner(S3RiverFeedDefinition feedDefinition){
         this.feedDefinition = feedDefinition;
      }
      
      @Override
      public void run(){
         while (true){
            if (closed){
               return;
            }
            
            try{
               bulk = client.prepareBulk();
               // Scan folder starting from last changes id, then record the new one.
               Long lastScanTime = getLastScanTimeFromRiver("_lastScanTime");
               lastScanTime = scan(lastScanTime);
               updateRiver("_lastChangesId", lastScanTime);
               
               // If some bulkActions remains, we should commit them
               commitBulk();
            } catch (Exception e){
               logger.warn("Error while indexing content from {}", feedDefinition.getBucket());
               if (logger.isDebugEnabled()){
                  logger.debug("Exception for folder {} is {}", feedDefinition.getBucket(), e);
                  e.printStackTrace();
               }
            }
         }
      }
      
      @SuppressWarnings("unchecked")
      private Long getLastScanTimeFromRiver(String lastScanTimeField){
         Long result = null;
         try {
            // Do something.
            client.admin().indices().prepareRefresh("_river").execute().actionGet();
            GetResponse lastSeqGetResponse = client.prepareGet("_river", riverName().name(),
                  lastScanTimeField).execute().actionGet();
            if (lastSeqGetResponse.isExists()) {
               Map<String, Object> fsState = (Map<String, Object>) lastSeqGetResponse.getSourceAsMap().get("amazon-s3");

               if (fsState != null){
                  Object lastScanTime= fsState.get(lastScanTimeField);
                  if (lastScanTime != null){
                     try{
                        result = Long.parseLong(lastScanTime.toString());
                     } catch (NumberFormatException nfe){
                        logger.warn("Last recorded lastScanTime is not a Long {}", lastScanTime.toString());
                     }
                  }
               }
            } else {
               // This is first call, just log in debug mode.
               if (logger.isDebugEnabled()){
                  logger.debug("{} doesn't exist", lastScanTimeField);
               }
            }
         } catch (Exception e) {
            logger.warn("failed to get _lastScanTimeField, throttling....", e);
         }

         if (logger.isDebugEnabled()){
            logger.debug("lastScanTimeField: {}", result);
         }
         return result;
      }
      
      /** Scan the Amazon S3 bucket for last changes. */
      private Long scan(Long lastScanTime) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Starting scanning of bucket {} since {}", feedDefinition.getBucket(), lastScanTime);
         }
         S3Changes changes = s3.getChanges(lastScanTime);
         
         return changes.getLastScanTime();
      }
      
      /** Update river last changes id value.*/
      private void updateRiver(String lastScanTimeField, Long lastScanTime) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Updating lastScanTimeField: {}", lastScanTime);
         }

         // We store the lastupdate date and some stats
         XContentBuilder xb = jsonBuilder()
            .startObject()
               .startObject("amazon-s3")
                  .field("feedname", feedDefinition.getFeedname())
                  .field(lastScanTimeField, lastScanTime)
               .endObject()
            .endObject();
         esIndex("_river", riverName.name(), lastScanTimeField, xb);
      }
      
      /**
       * Commit to ES if something is in queue
       * @throws Exception
       */
      private void commitBulk() throws Exception{
         if (bulk != null && bulk.numberOfActions() > 0){
            if (logger.isDebugEnabled()){
               logger.debug("ES Bulk Commit is needed");
            }
            BulkResponse response = bulk.execute().actionGet();
            if (response.hasFailures()){
               logger.warn("Failed to execute " + response.buildFailureMessage());
            }
         }
      }
      
      /**
       * Commit to ES if we have too much in bulk 
       * @throws Exception
       */
      private void commitBulkIfNeeded() throws Exception {
         if (bulk != null && bulk.numberOfActions() > 0 && bulk.numberOfActions() >= bulkSize){
            if (logger.isDebugEnabled()){
               logger.debug("ES Bulk Commit is needed");
            }
            
            BulkResponse response = bulk.execute().actionGet();
            if (response.hasFailures()){
               logger.warn("Failed to execute " + response.buildFailureMessage());
            }
            
            // Reinit a new bulk.
            bulk = client.prepareBulk();
         }
      }
      
      /** Add to bulk an IndexRequest. */
      private void esIndex(String index, String type, String id, XContentBuilder xb) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Indexing in ES " + index + ", " + type + ", " + id);
         }
         if (logger.isTraceEnabled()){
            logger.trace("Json indexed : {}", xb.string());
         }
         
         bulk.add(client.prepareIndex(index, type, id).setSource(xb));
         commitBulkIfNeeded();
      }

      /** Add to bulk a DeleteRequest. */
      private void esDelete(String index, String type, String id) throws Exception{
         if (logger.isDebugEnabled()){
            logger.debug("Deleting from ES " + index + ", " + type + ", " + id);
         }
         bulk.add(client.prepareDelete(index, type, id));
         commitBulkIfNeeded();
      }
   }
}