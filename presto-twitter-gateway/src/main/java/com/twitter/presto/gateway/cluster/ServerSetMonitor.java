package com.twitter.presto.gateway.cluster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.HostAndPort;
import io.airlift.log.Logger;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.ZKPaths;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

// TODO: remove redundant code, extract ServerSetMonitor as a common util class
public class ServerSetMonitor
        implements PathChildrenCacheListener
{
    private static final Logger log = Logger.get(ServerSetMonitor.class);
    private static final ObjectMapper jsonObjectMapper = new ObjectMapper();
    private static final int ZK_RETRY_SLEEP_TIME = 500;
    private static final int ZK_MAX_RETRY = 3;

    private CuratorFramework client;
    private PathChildrenCache cache;
    private ConcurrentMap<String, HostAndPort> servers;  // (Node_Name->HostAndPort)

    public ServerSetMonitor(String zkServer, String watchPath)
    {
        client = CuratorFrameworkFactory.newClient(zkServer, new ExponentialBackoffRetry(ZK_RETRY_SLEEP_TIME, ZK_MAX_RETRY));
        client.start();

        cache = new PathChildrenCache(client, watchPath, true); // true indicating cache node contents in addition to the stat
        try {
            cache.start();
        }
        catch (Exception ex) {
            throw new RuntimeException("Curator PathCache Creation failed: " + ex.getMessage());
        }

        cache.getListenable().addListener(this);
        servers = new ConcurrentHashMap<>();
    }

    public void close()
    {
        client.close();

        try {
            cache.close();
        }
        catch (IOException ex) {
            // do nothing
        }
    }

    public List<HostAndPort> getServers()
    {
        return servers.values().stream().collect(toList());
    }

    private HostAndPort getHostAndPath(byte[] bytes)
    {
        try {
            JsonNode hostPortMap = jsonObjectMapper.readTree(new String(bytes)).get("serviceEndpoint");
            String host = hostPortMap.get("host").asText();
            int port = Integer.parseInt(hostPortMap.get("port").asText());
            return HostAndPort.fromParts(host, port);
        }
        catch (IOException e) {
            log.warn("failed to deserialize child node data");
            throw new IllegalArgumentException("No host:port found");
        }
    }

    @Override
    public void childEvent(CuratorFramework client, PathChildrenCacheEvent event)
            throws Exception
    {
        switch (event.getType()) {
            case CHILD_ADDED:
            case CHILD_UPDATED: {
                HostAndPort hostPort = getHostAndPath(event.getData().getData());
                String node = ZKPaths.getNodeFromPath(event.getData().getPath());
                log.info("child updated: " + node + ": " + hostPort);
                servers.put(node, hostPort);
                break;
            }

            case CHILD_REMOVED: {
                String node = ZKPaths.getNodeFromPath(event.getData().getPath());
                log.info("child removed: " + node);
                servers.remove(node);
                break;
            }

            default:
                log.info("connection state changed: " + event.getType());
                break;
        }
    }
}
