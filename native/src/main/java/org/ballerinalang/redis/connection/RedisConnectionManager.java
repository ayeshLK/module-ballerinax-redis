/*
 * Copyright (c) 2023 WSO2 LLC. (http://www.wso2.org)
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.redis.connection;

import io.ballerina.runtime.api.utils.StringUtils;
import io.ballerina.runtime.api.values.BMap;
import io.ballerina.runtime.api.values.BString;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulConnection;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.BaseRedisCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.sync.RedisHashCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisListCommands;
import io.lettuce.core.api.sync.RedisSetCommands;
import io.lettuce.core.api.sync.RedisSortedSetCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.support.ConnectionPoolSupport;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.ballerinalang.redis.exceptions.RedisConnectorException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.ballerinalang.redis.utils.Constants.CONFIG_CLIENT_NAME;
import static org.ballerinalang.redis.utils.Constants.CONFIG_CONNECTION_TIMEOUT;
import static org.ballerinalang.redis.utils.Constants.CONFIG_DATABASE;
import static org.ballerinalang.redis.utils.Constants.CONFIG_SSL_ENABLED;
import static org.ballerinalang.redis.utils.Constants.CONFIG_START_TLS_ENABLED;
import static org.ballerinalang.redis.utils.Constants.CONFIG_VERIFY_PEER_ENABLED;

/**
 * Connection manager implementation for Redis connections.
 *
 * @param <K> Type of the Key
 * @param <V> Type of the Value
 * @since 2.6.0
 */
public class RedisConnectionManager<K, V> {

    // Lettuce connection objects
    private RedisCommands<K, V> redisCommands;
    private RedisAdvancedClusterCommands<K, V> redisClusterCommands;
    private GenericObjectPool<StatefulConnection<K, V>> objectPool;
    private final RedisCodec<K, V> codec;
    private final boolean isClusterConnection;
    private final boolean poolingEnabled;

    // Command executors
    private RedisConnectionCommandExecutor connectionCommandExecutor;
    private RedisStringCommandExecutor stringCommandExecutor;
    private RedisKeyCommandExecutor keyCommandExecutor;
    private RedisHashCommandExecutor hashCommandExecutor;
    private RedisSetCommandExecutor setCommandExecutor;
    private RedisListCommandsExecutor listCommandsExecutor;
    private RedisSortedSetCommandExecutor sortedSetCommandExecutor;

    // Constants
    private static final String HOSTS_SEPARATOR = ",";
    private static final String HOST_PORT_SEPARATOR = ":";
    private static final int DEFAULT_PORT = 6379;

    /**
     * Constructor for {@link RedisConnectionManager}.
     *
     * @param codec               The codec for transcoding keys/values between the application and the Redis DB.
     *                            Instance of {@link RedisCodec}
     * @param isClusterConnection Whether the connection is a cluster connection
     * @param poolingEnabled      Whether connection pooling is enabled
     */
    public RedisConnectionManager(RedisCodec<K, V> codec, boolean isClusterConnection, boolean poolingEnabled) {
        this.codec = codec;
        this.isClusterConnection = isClusterConnection;
        this.poolingEnabled = poolingEnabled;
    }

    /**
     * This method initializes a Redis client with the provided options.
     *
     * @param hosts    The host(s) of the Redis instance/cluster
     * @param password The password required for authentication
     * @param options  The additional options
     */
    public void init(String hosts, String password, BMap<BString, Object> options) throws RedisConnectorException {
        List<ServerAddress> serverAddresses = obtainServerAddresses(hosts);
        if (isClusterConnection) {
            setRedisClusterCommands(serverAddresses, password, options);
        } else {
            if (serverAddresses.size() > 1) {
                throw new RedisConnectorException("Multiple hosts are not supported for standalone connections");
            }
            setRedisStandaloneCommands(serverAddresses.get(0), password, options);
        }

        //TODO: Add support for executing commands in async mode/ reactive mode
    }

    public RedisConnectionCommandExecutor getConnectionCommandExecutor() {
        if (connectionCommandExecutor == null) {
            connectionCommandExecutor = new RedisConnectionCommandExecutor(this);
        }
        return connectionCommandExecutor;
    }

    public RedisStringCommandExecutor getStringCommandExecutor() {
        if (stringCommandExecutor == null) {
            stringCommandExecutor = new RedisStringCommandExecutor(this);
        }
        return stringCommandExecutor;
    }

    public RedisKeyCommandExecutor getKeyCommandExecutor() {
        if (keyCommandExecutor == null) {
            keyCommandExecutor = new RedisKeyCommandExecutor(this);
        }
        return keyCommandExecutor;
    }

    public RedisHashCommandExecutor getHashCommandExecutor() {
        if (hashCommandExecutor == null) {
            hashCommandExecutor = new RedisHashCommandExecutor(this);
        }
        return hashCommandExecutor;
    }

    public RedisSetCommandExecutor getSetCommandExecutor() {
        if (setCommandExecutor == null) {
            setCommandExecutor = new RedisSetCommandExecutor(this);
        }
        return setCommandExecutor;
    }

    public RedisListCommandsExecutor getListCommandExecutor() {
        if (listCommandsExecutor == null) {
            listCommandsExecutor = new RedisListCommandsExecutor(this);
        }
        return listCommandsExecutor;
    }

    public RedisSortedSetCommandExecutor getSortedSetCommandExecutor() {
        if (sortedSetCommandExecutor == null) {
            sortedSetCommandExecutor = new RedisSortedSetCommandExecutor(this);
        }
        return sortedSetCommandExecutor;
    }

    public BaseRedisCommands<K, V> getConnectionCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisStringCommands<K, V> getStringCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisListCommands<K, V> getListCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisSetCommands<K, V> getSetCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisSortedSetCommands<K, V> getSortedSetCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisHashCommands<K, V> getHashCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    public RedisKeyCommands<K, V> getKeyCommandConnection() throws RedisConnectorException {
        return isClusterConnection() ? getRedisClusterCommands() : getRedisCommands();
    }

    /**
     * Returns {@link RedisCommands}, an interface for Redis commands available on a Redis instance.
     *
     * @return a {@link RedisCommands} instance
     */
    public RedisCommands<K, V> getRedisCommands() throws RedisConnectorException {
        if (poolingEnabled) {
            return ((StatefulRedisConnection<K, V>) getStatefulRedisConnectionFromPool()).sync();
        }
        return redisCommands;
    }

    /**
     * Returns {@link RedisAdvancedClusterCommands}, an interface for Redis commands available on a Redis Cluster.
     *
     * @return a {@link RedisAdvancedClusterCommands} instance
     */
    public RedisAdvancedClusterCommands<K, V> getRedisClusterCommands() throws RedisConnectorException {
        if (poolingEnabled) {
            return ((StatefulRedisClusterConnection<K, V>) getStatefulRedisConnectionFromPool()).sync();
        }
        return redisClusterCommands;
    }

    /**
     * Returns whether the connection made by the connection manager is a cluster connection.
     *
     * @return boolean true/false
     */
    public boolean isClusterConnection() {
        return isClusterConnection;
    }

    /**
     * Returns whether the connection made by the connection manager is a pooled connection.
     *
     * @return boolean true/false
     */
    public boolean isPoolingEnabled() {
        return poolingEnabled;
    }

    public void closeConnectionPool() {
        objectPool.close();
    }

    private void setRedisStandaloneCommands(ServerAddress address, String password, BMap<BString, Object> options) {
        RedisURI redisURI = constructRedisUri(address.host(), address.port(), password, options);
        RedisClient redisClient = RedisClient.create(redisURI);

        if (poolingEnabled) {
            Supplier<StatefulConnection<K, V>> supplier = () -> redisClient.connect(codec);
            objectPool = ConnectionPoolSupport.createGenericObjectPool(supplier, new GenericObjectPoolConfig<>());
        } else {
            StatefulRedisConnection<K, V> statefulRedisConnection = redisClient.connect(codec);
            redisCommands = statefulRedisConnection.sync();
        }
    }

    private void setRedisClusterCommands(List<ServerAddress> serverAddresses, String password,
                                         BMap<BString, Object> options) {
        List<RedisURI> redisURIS;
        redisURIS = serverAddresses.stream()
                .map(serverAddress -> constructRedisUri(serverAddress.host(), serverAddress.port(), password, options))
                .collect(Collectors.toList());

        RedisClusterClient redisClusterClient = RedisClusterClient.create(redisURIS);
        if (poolingEnabled) {
            Supplier<StatefulConnection<K, V>> supplier = () -> redisClusterClient.connect(codec);
            objectPool = ConnectionPoolSupport.createGenericObjectPool(supplier, new GenericObjectPoolConfig<>());
        } else {
            redisClusterCommands = redisClusterClient.connect(codec).sync();
        }
    }

    private RedisURI constructRedisUri(String host, int port, String password, BMap<BString, Object> options) {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port);

        boolean sslEnabled = options.getBooleanValue(StringUtils.fromString(CONFIG_SSL_ENABLED));
        boolean startTlsEnabled = options.getBooleanValue(StringUtils.fromString(CONFIG_START_TLS_ENABLED));
        boolean verifyPeerEnabled = options.getBooleanValue(StringUtils.fromString(CONFIG_VERIFY_PEER_ENABLED));
        builder.withSsl(sslEnabled);
        builder.withStartTls(startTlsEnabled);
        builder.withVerifyPeer(verifyPeerEnabled);

        int database = options.getIntValue(StringUtils.fromString(CONFIG_DATABASE)).intValue();
        if (database != -1) {
            builder.withDatabase(database);
        }

        int connectionTimeout = options.getIntValue(StringUtils.fromString(CONFIG_CONNECTION_TIMEOUT)).intValue();
        if (connectionTimeout != -1) {
            builder.withTimeout(Duration.ofMillis(connectionTimeout));
        }

        BString clientName = options.getStringValue(StringUtils.fromString(CONFIG_CLIENT_NAME));
        if (clientName != null && !clientName.getValue().isBlank()) {
            builder.withClientName(clientName.toString());
        }

        if (password != null && !password.isBlank()) {
            builder.withPassword(password);
        }

        return builder.build();
    }

    private List<ServerAddress> obtainServerAddresses(String hostStr) throws RedisConnectorException {
        String[] hosts = hostStr.split(HOSTS_SEPARATOR);
        List<ServerAddress> result = new ArrayList<>(hosts.length);
        for (String host : hosts) {
            result.add(createServerAddress(host));
        }
        return result;
    }

    private ServerAddress createServerAddress(String hostStr) throws RedisConnectorException {
        String[] hostPort = hostStr.split(HOST_PORT_SEPARATOR);
        String host = hostPort[0];
        int port;
        if (hostPort.length > 1) {
            try {
                port = Integer.parseInt(hostPort[1]);
            } catch (NumberFormatException e) {
                throw new RedisConnectorException("port of the host string must be an integer: " + hostStr, e);
            }
        } else {
            port = DEFAULT_PORT;
        }
        return new ServerAddress(host, port);
    }

    public void releaseResources(Object redisCommands) {
        if (!isPoolingEnabled() || Objects.isNull(redisCommands)) {
            return;
        }

        if (isClusterConnection) {
            objectPool.returnObject(((RedisAdvancedClusterCommands<K, V>) redisCommands).getStatefulConnection());
        } else {
            objectPool.returnObject(((RedisCommands<K, V>) redisCommands).getStatefulConnection());
        }
    }

    private StatefulConnection<K, V> getStatefulRedisConnectionFromPool() throws RedisConnectorException {
        try {
            return objectPool.borrowObject();
        } catch (Exception e) {
            throw new RedisConnectorException("Error occurred while obtaining connection from the pool: " + e);
        }
    }

    private BaseRedisCommands<K, V> getCommandConnection() throws RedisConnectorException {
        if (isClusterConnection()) {
            return getRedisClusterCommands();
        } else {
            return getRedisCommands();
        }
    }

    /**
     * Represents a Redis server address.
     *
     * @param host redis server host
     * @param port redis server port
     */
    private record ServerAddress(String host, int port) {

    }
}