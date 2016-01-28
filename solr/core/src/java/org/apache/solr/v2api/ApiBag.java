package org.apache.solr.v2api;

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

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.ImmutableSet;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.Lookup;
import org.apache.solr.common.util.Map2;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrRequestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.client.solrj.SolrRequest.SUPPORTED_METHODS;
import static org.apache.solr.common.util.Map2.ENUM_OF;
import static org.apache.solr.common.util.Map2.NOT_NULL;

public class ApiBag {
  private static final Logger log = LoggerFactory.getLogger(ApiBag.class);

  private final Map<String, PathTrie<V2Api>> apis = new ConcurrentHashMap<>();
  private final Lookup<String, Map2> specProvider;


  public ApiBag() {
    this.specProvider = new Lookup<String, Map2>() {
      @Override
      public Map2 get(String key) {
        return getMap(key);
      }
    };
  }

  public static Map2 getResource(String name) {
    InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(name);
//    ByteBuffer buf = webappResourceLoader.get(name);
    if (is == null)
      throw new RuntimeException("invalid API spec :" + name );
    Map2 map1 = null;
    try {
      map1 = Map2.fromJSON(is);
    } catch (Exception e) {
      log.error("Error in JSON : " + name, e);
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      }
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    if (map1 == null) throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Empty value for " + name);

    return Map2.getDeepCopy(map1, 5, false);
  }



  public synchronized void register(V2Api api, Map<String, String> nameSubstitutes) {
    try {
      validateAndRegister(api, nameSubstitutes);
    } catch (Exception e) {
      log.error("Unable to register plugin:" + api.getClass().getName() + "with spec :" + api.getSpec(specProvider), e);
      if (e instanceof RuntimeException) {
        throw (RuntimeException) e;
      } else {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }

    }
  }

  private void validateAndRegister(V2Api api, Map<String, String> nameSubstitutes) {
    Map2 spec = api.getSpec(getSpecLookup());
    V2Api introspect = getIntrospect(this, api);
    List<String> methods = spec.getList("methods", ENUM_OF, SUPPORTED_METHODS);
    for (String method : methods) {
      PathTrie<V2Api> registry = apis.get(method);
      if (registry == null) apis.put(method, registry = new PathTrie<>());
      Map2 url = spec.getMap("url", NOT_NULL);
      Map2 params = url.getMap("params", null);
      if (params != null) {
        for (Object o : params.keySet()) {
          Map2 param = params.getMap(o.toString(), NOT_NULL);
          param.get("type", ENUM_OF, KNOWN_TYPES);
          param.get("description", NOT_NULL);
        }
      }
      List<String> paths = url.getList("paths", NOT_NULL);
      Map2 parts = url.getMap("parts", null);
      if (parts != null) {
        Set<String> wildCardNames = getWildCardNames(paths);
        for (Object o : parts.keySet()) {
          if (!wildCardNames.contains(o.toString()))
            throw new RuntimeException("" + o + " is not a valid part name");
          Map2 pathMeta = parts.getMap(o.toString(), NOT_NULL);
          pathMeta.get("type", ENUM_OF, ImmutableSet.of("enum", "string", "int", "number"));
        }
      }
      verifyCommands(api.getSpec(getSpecLookup()));
      for (String path : paths) {
        registry.insert(path, nameSubstitutes, api);
        registry.insert(path + INTROSPECT, nameSubstitutes, introspect);
      }
    }
  }

  private V2Api getIntrospect(final ApiBag apiBag, final V2Api baseApi) {
    return new V2Api(Map2.EMPTY) {

      @Override
      public Map2 getSpec(Lookup<String, Map2> specLookup) {
        return INTROSPECT_SPEC;
      }

      @Override
      public void call(V2RequestContext ctx) {
        String cmd = ctx.getSolrRequest().getParams().get("command");
        Map2 result = null;
        if (cmd == null) {
          result = baseApi.getSpec(apiBag.getSpecLookup());
        } else {
          Map2 specCopy = Map2.getDeepCopy(baseApi.getSpec(apiBag.getSpecLookup()), 5, true);
          Map2 commands = specCopy.getMap("commands", null);
          if (commands != null) {
            Map2 m = commands.getMap(cmd, null);
            specCopy.put("commands", Collections.singletonMap(cmd, m));
          }
          result = specCopy;
        }
        List l = (List) ctx.getResponse().getValues().get("spec");
        if (l == null) ctx.getResponse().getValues().add("spec", l = new ArrayList());
        l.add(result);
      }
    };
  }

  private void verifyCommands(Map2 spec) {
    Map2 commands = spec.getMap("commands", null);
    if (commands == null) return;
    //TODO do verify

  }

  private Set<String> getWildCardNames(List<String> paths) {
    Set<String> wildCardNames = new HashSet<>();
    for (String path : paths) {
      List<String> p = PathTrie.getParts(path);
      for (String s : p) {
        String wildCard = PathTrie.wildCardName(s);
        if (wildCard != null) wildCardNames.add(wildCard);
      }
    }
    return wildCardNames;
  }


  public V2Api lookup(String path, String httpMethod, Map<String, String> parts) {
    if (httpMethod == null) {
      for (PathTrie<V2Api> trie : apis.values()) {
        V2Api api = trie.lookup(path, parts);
        if (api != null) return api;
      }
      return null;
    } else {
      PathTrie<V2Api> registry = apis.get(httpMethod);
      if (registry == null) return null;
      return registry.lookup(path, parts);
    }
  }

  private static Map2 getMap(String name) {
    Map2 map = getResource(APISPEC_LOCATION + name + ".json");
    Map2 result = map.getMap(name, NOT_NULL);
    Map2 cmds = result.getMap("commands", null);
    if (cmds != null) {
      Map<String, Map2> comands2BReplaced = new Map2<>();
      for (Object o : cmds.keySet()) {
        Object val = cmds.get(o);
        if (val instanceof String) {
          String s = (String) val;
          Map2 cmdSpec = getResource(APISPEC_LOCATION + s + ".json");
          comands2BReplaced.put(o.toString(), cmdSpec);
        }
      }

      if (!comands2BReplaced.isEmpty()) {
        Map2 mapCopy = Map2.getDeepCopy(result, 4, true);
        mapCopy.getMap("commands", NOT_NULL).putAll(comands2BReplaced);
        result = Map2.getDeepCopy(mapCopy, 4, false);
      }
    }

    return result;
  }

  public static V2Api wrapRequestHandler(final RequestHandlerBase rh, final Map2 spec, SpecProvider specProvider) {
    return new V2Api(spec) {
      @Override
      public void call(V2RequestContext ctx) {
        rh.handleRequest(ctx.getSolrRequest(), ctx.getResponse());
      }

      @Override
      public Map2 getSpec(Lookup<String, Map2> specLookup) {
        return specProvider != null ?
            specProvider.getSpec(specLookup) :
            super.getSpec(specLookup);
      }
    };
  }

  public static final String APISPEC_LOCATION = "v2apispec/";
  public static final String INTROSPECT = "/_introspect";


  public Lookup<String, Map2> getSpecLookup() {
    return specProvider;
  }


  public static final Map2 INTROSPECT_SPEC = new Map2(Collections.EMPTY_MAP);
  public static final String HANDLER_NAME = "handlerName";
  public static final Set<String> KNOWN_TYPES = ImmutableSet.of("string", "boolean", "list", "int", "double");

  public PathTrie<V2Api> getRegistry(String method) {
    return apis.get(method);
  }
}
