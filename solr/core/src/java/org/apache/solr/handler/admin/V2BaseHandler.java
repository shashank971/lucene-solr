package org.apache.solr.handler.admin;

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


import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.google.common.collect.ImmutableList;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.Lookup;
import org.apache.solr.common.util.Map2;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.common.util.Utils;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.util.CommandOperation;
import org.apache.solr.v2api.V2Api;
import org.apache.solr.v2api.V2ApiSupport;
import org.apache.solr.v2api.V2RequestContext;

import static org.apache.solr.client.solrj.SolrRequest.METHOD.POST;
import static org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST;
import static org.apache.solr.common.util.StrUtils.splitSmart;

public abstract class V2BaseHandler implements V2ApiSupport {
  protected final Map<SolrRequest.METHOD, Map<V2EndPoint, List<V2Command>>> commandsMapping;

  protected V2BaseHandler() {
    commandsMapping = new HashMap<>();
    for (V2Command cmd : getCommands()) {
      Map<V2EndPoint, List<V2Command>> m = commandsMapping.get(cmd.getHttpMethod());
      if (m == null) commandsMapping.put(cmd.getHttpMethod(), m = new HashMap<>());
      List<V2Command> list = m.get(cmd.getEndPoint());
      if (list == null) m.put(cmd.getEndPoint(), list = new ArrayList<>());
      list.add(cmd);
    }
  }

  @Override
  public synchronized Collection<V2Api> getApis(Lookup<String, Map2> specLookup) {
    ImmutableList.Builder<V2Api> l = ImmutableList.builder();
    for (V2EndPoint op : getEndPoints()) l.add(getApi(op, specLookup));
    return l.build();
  }


  private V2Api getApi(final V2EndPoint op, Lookup<String, Map2> specLookup) {
    final Map2 spec = specLookup.get(op.getSpecName());
    return new V2Api(spec) {
      @Override
      public void call(V2RequestContext ctx) {
        SolrParams params = ctx.getSolrRequest().getParams();
        SolrRequest.METHOD method = SolrRequest.METHOD.valueOf(ctx.getHttpMethod());
        List<V2Command> commands = commandsMapping.get(method).get(op);
        try {
          if (method == POST) {
            List<CommandOperation> cmds = ctx.getCommands(true);
            if (cmds.size() > 1)
              throw new SolrException(BAD_REQUEST, "Only one command is allowed");


            CommandOperation c = cmds.size() == 0 ? null : cmds.get(0);
            V2Command command = null;
            String commandName = c == null ? null : c.name;
            for (V2Command cmd : commands) {
              if (Objects.equals(cmd.getName(), commandName)) {
                command = cmd;
                break;
              }
            }

            if (command == null) {
              throw new SolrException(BAD_REQUEST, " no such command " + c);
            }
            wrapParams(ctx, c, command, false);
            invokeCommand(ctx, command, c);

          } else {
            if (commands == null || commands.isEmpty()) {
              ctx.getResponse().add("error", "No support for : " + method + " at :" + ctx.getPath());
              return;
            }
            wrapParams(ctx, new CommandOperation("", Collections.EMPTY_MAP), commands.get(0), true);
            invokeUrl(commands.get(0), ctx);
          }

        } catch (SolrException e) {
          throw e;
        } catch (Exception e) {
          throw new SolrException(BAD_REQUEST, e);
        } finally {
          ctx.getSolrRequest().setParams(params);
        }

      }
    };

  }

  private static void wrapParams(final V2RequestContext ctx, final CommandOperation co, final V2Command cmd, final boolean useRequestParams) {
    final Map<String, String> pathValues = ctx.getPathValues();
    final Map<String, Object> map = co == null || !(co.getCommandData() instanceof Map) ?
        Collections.emptyMap() : co.getDataMap();
    final SolrParams origParams = ctx.getSolrRequest().getParams();

    ctx.getSolrRequest().setParams(
        new SolrParams() {
          @Override
          public String get(String param) {
            Object vals = getParams0(param);
            if (vals == null) return null;
            if (vals instanceof String) return (String) vals;
            if (vals instanceof String[] && ((String[]) vals).length > 0) return ((String[]) vals)[0];
            return null;
          }

          private Object getParams0(String param) {
            param = cmd.getParamSubstitute(param);
            Object o = param.indexOf('.') > 0 ?
                Utils.getObjectByPath(map, true, splitSmart(param, '.')) :
                map.get(param);
            if (o == null) o = pathValues.get(param);
            if (o == null && useRequestParams) o = origParams.getParams(param);
            if (o instanceof List) {
              List l = (List) o;
              return l.toArray(new String[l.size()]);
            }

            return o;
          }

          @Override
          public String[] getParams(String param) {
            Object vals = getParams0(param);
            return vals == null || vals instanceof String[] ?
                (String[]) vals :
                new String[]{vals.toString()};
          }

          @Override
          public Iterator<String> getParameterNamesIterator() {
            return cmd.getParamNames(co).iterator();

          }


        });

  }


  public static Collection<String> getParamNames(CommandOperation op, V2Command command) {
    List<String> result = new ArrayList<>();
    Object o = op.getCommandData();
    if (o instanceof Map) {
      Map map = (Map) o;
      collectKeyNames(map, result, "");
    }
    return result;

  }

  public static void collectKeyNames(Map<String, Object> map, List<String> result, String prefix) {
    for (Map.Entry<String, Object> e : map.entrySet()) {
      if (e.getValue() instanceof Map) {
        collectKeyNames((Map) e.getValue(), result, prefix + e.getKey() + ".");
      } else {
        result.add(prefix + e.getKey());
      }
    }
  }


  protected abstract void invokeCommand(V2RequestContext ctx, V2Command command, CommandOperation c) throws Exception;

  protected abstract void invokeUrl(V2Command command, V2RequestContext ctx) throws Exception;

  protected abstract List<V2Command> getCommands();

  protected abstract List<V2EndPoint> getEndPoints();


}
