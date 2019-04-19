/*
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
package com.twitter.presto.gateway.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.log.Logger;

import javax.annotation.PostConstruct;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.airlift.http.client.HttpUriBuilder.uriBuilderFrom;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;

public class QueryInfoTracker
{
    private static final Logger log = Logger.get(QueryInfoTracker.class);

    private final ConcurrentHashMap<String, RemoteQueryInfo> remoteQueryInfos = new ConcurrentHashMap<>();
    private final ClusterManager clusterManager;
    private final HttpClient httpClient;
    private final ScheduledExecutorService queryInfoUpdateExecutor;

    @Inject
    public QueryInfoTracker(
            ClusterManager clusterManager,
            @ForQueryTracker HttpClient httpClient)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.clusterManager = requireNonNull(clusterManager, "clusterManager is null");
        this.queryInfoUpdateExecutor = newSingleThreadScheduledExecutor(threadsNamed("query-info-poller-%s"));
    }

    @PostConstruct
    public void startPollingQueryInfo()
    {
        clusterManager.getAllClusters().stream()
                .forEach(uri -> remoteQueryInfos.put(uri.toASCIIString(), new RemoteQueryInfo(httpClient, uriBuilderFrom(uri).appendPath("/v1/query").build())));

        queryInfoUpdateExecutor.scheduleWithFixedDelay(() -> {
            try {
                pollQueryInfos();
            }
            catch (Exception e) {
                log.error(e, "Error polling list of queries");
            }
        }, 5, 5, TimeUnit.SECONDS);

        pollQueryInfos();
    }

    private void pollQueryInfos()
    {
        remoteQueryInfos.values().forEach(RemoteQueryInfo::asyncRefresh);
    }

    public List<JsonNode> getAllQueryInfos()
    {
        ImmutableList.Builder<JsonNode> builder = ImmutableList.builder();
        remoteQueryInfos.forEach((coordinator, remoteQueryInfo) ->
                builder.addAll(remoteQueryInfo.getQueryList().orElse(ImmutableList.of()).stream()
                        .map(queryInfo -> ((ObjectNode) queryInfo).put("coordinatorUri", coordinator))
                        .collect(toImmutableList())));
        return builder.build();
    }
}
