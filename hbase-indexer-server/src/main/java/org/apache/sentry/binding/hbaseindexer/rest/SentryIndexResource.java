/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.sentry.binding.hbaseindexer.rest;

import com.ngdata.hbaseindexer.compatrest.CliCompatibleIndexResource;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.impl.IndexerDefinitionJsonSerDeser;
import com.ngdata.hbaseindexer.servlet.HBaseIndexerAuthFilter;
import com.ngdata.hbaseindexer.servlet.IndexerServerException;

import java.io.IOException;
import java.net.URL;
import java.util.Collection;
import java.util.EnumSet;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import org.apache.hadoop.conf.Configuration;
import org.apache.sentry.core.common.Subject;
import org.apache.sentry.binding.hbaseindexer.authz.HBaseIndexerAuthzBinding;
import org.apache.sentry.binding.hbaseindexer.authz.SentryHBaseIndexerAuthorizationException;
import org.apache.sentry.binding.hbaseindexer.conf.HBaseIndexerAuthzConf;
import org.apache.sentry.core.model.indexer.Indexer;
import org.apache.sentry.core.model.indexer.IndexerModelAction;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.server.auth.KerberosName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@Path("indexer")
public class SentryIndexResource extends CliCompatibleIndexResource {
  private static Logger log =
    LoggerFactory.getLogger(SentryIndexResource.class);
  public static final String SENTRY_SITE = "sentry.hbaseindexer.sentry.site";
  public static final String SENTRY_BINDING = "sentry.hbaseindexer.binding";
  protected HBaseIndexerAuthzBinding authzBinding;

  public SentryIndexResource(@Context ServletContext context) {
    if (context.getAttribute(SENTRY_BINDING) != null) {
      authzBinding = (HBaseIndexerAuthzBinding)context.getAttribute(SENTRY_BINDING);
    }
  }

  /**
   * see {@link CliCompatibleIndexerResource#get}
   */
  @GET
  @Produces("application/json")
  @Override
  public Collection<IndexerDefinition> get(@Context UriInfo uriInfo, @Context SecurityContext securityContext) throws IndexerServerException {
    if (authzBinding == null) {
      throwNullBindingException();
    }
    Collection<IndexerDefinition> indexers = super.get(uriInfo, securityContext);
    try {
      return authzBinding.filterIndexers(getSubject(securityContext), indexers);
    } catch (SentryHBaseIndexerAuthorizationException e) {
      throw new IndexerServerException(HttpServletResponse.SC_UNAUTHORIZED, e);
    }
  }

  /**
   * see {@link CliCompatibleIndexerResource#delete}
   */
  @DELETE
  @Produces("application/json")
  @Path("{name}")
  @Override
  public byte[] delete(@Context SecurityContext securityContext, @PathParam("name") String indexerName) throws IndexerServerException, InterruptedException, KeeperException {
    if (authzBinding == null) {
      throwNullBindingException();
    }
    try {
      authzBinding.authorizeIndexerAction(getSubject(securityContext), new Indexer(indexerName), EnumSet.of(IndexerModelAction.WRITE));
    } catch (SentryHBaseIndexerAuthorizationException e) {
      throw new IndexerServerException(HttpServletResponse.SC_UNAUTHORIZED, e);
    }
    return super.delete(securityContext, indexerName);
  }

  /**
   * see {@link CliCompatibleIndexerResource#put}
   */
  @PUT
  @Path("{name}")
  @Consumes("application/json")
  @Produces("application/json")
  @Override
  public byte[] put(@Context SecurityContext securityContext, @PathParam("name") String indexName, byte [] jsonBytes) throws IndexerServerException {
    if (authzBinding == null) {
      throwNullBindingException();
    }
    try {
      authzBinding.authorizeIndexerAction(getSubject(securityContext), new Indexer(indexName), EnumSet.of(IndexerModelAction.WRITE));
    } catch (SentryHBaseIndexerAuthorizationException e) {
      throw new IndexerServerException(HttpServletResponse.SC_UNAUTHORIZED, e);
    }
    return super.put(securityContext, indexName, jsonBytes);
  }

  /**
   * see {@link CliCompatibleIndexerResource#post}
   */
  @POST
  @Consumes("application/json")
  @Produces("application/json")
  @Override
  public byte[] post(@Context SecurityContext securityContext, byte [] jsonBytes) throws IndexerServerException {
    if (authzBinding == null) {
      throwNullBindingException();
    }
    IndexerDefinition def = getIndexerFromJson(jsonBytes);
    try {
      authzBinding.authorizeIndexerAction(getSubject(securityContext), new Indexer(def.getName()), EnumSet.of(IndexerModelAction.WRITE));
    } catch (SentryHBaseIndexerAuthorizationException e) {
      throw new IndexerServerException(HttpServletResponse.SC_UNAUTHORIZED, e);
    }
    return super.post(securityContext, jsonBytes);
  }

  private Subject getSubject(SecurityContext securityContext)
  throws SentryHBaseIndexerAuthorizationException {
    String princ = securityContext.getUserPrincipal() != null ?
      securityContext.getUserPrincipal().getName() : null;
    KerberosName kerbName = new KerberosName(princ);
    try {
      return new Subject(kerbName.getShortName());
    } catch (IOException e) {
      throw new SentryHBaseIndexerAuthorizationException("Unable to get subject", e);
    }
  }

  private IndexerDefinition getIndexerFromJson(byte [] jsonBytes) throws IndexerServerException {
    try {
      return IndexerDefinitionJsonSerDeser.INSTANCE.fromJsonBytes(jsonBytes).build();
    } catch (Exception e) {
      throw new IndexerServerException(HttpServletResponse.SC_UNAUTHORIZED,
        new SentryHBaseIndexerAuthorizationException("Unable to test permissions, "+ e.getMessage(), e));
    }
  }

  private void throwNullBindingException() throws IndexerServerException {
    throw new IndexerServerException(HBaseIndexerAuthzBinding.SC_UNAUTHORIZED,
      new SentryHBaseIndexerAuthorizationException(
       "HBaseIndexer-Sentry binding was not created successfully. Defaulting to no access"));
  }

}