package glutinous.network.client;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import glutinous.network.TransportContext;
import glutinous.network.server.TransportChannelHandler;
import glutinous.network.util.IOMode;
import glutinous.network.util.NettyUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public class TransportClientFactory implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(TransportClientFactory.class);
    private static final long connectionTimeoutMs =  12 * 1000;


    /** A simple data structure to track the pool of clients between two peer nodes. */
    private static class ClientPool {
        TransportClient[] clients;
        Object[] locks;

        ClientPool(int size) {
            clients = new TransportClient[size];
            locks = new Object[size];
            for (int i = 0; i < size; i++) {
                locks[i] = new Object();
            }
        }
    }

    private final TransportContext context;
    private final ConcurrentHashMap<SocketAddress, ClientPool> connectionPool;
    private final int numConnectionsPerPeer = 1;
    private final Random rand;
    private final Class<? extends Channel> socketChannelClass;
    private EventLoopGroup workerGroup;
    private PooledByteBufAllocator pooledAllocator;
    private List<TransportClientBootstrap> clientBootstraps;

    public TransportClientFactory(TransportContext context, List<TransportClientBootstrap> bootstraps) {
        this.context = Preconditions.checkNotNull(context);

        this.rand = new Random();
        this.connectionPool = new ConcurrentHashMap<>();
        IOMode ioMode = IOMode.NIO;
        this.socketChannelClass = NettyUtils.getClientChannelClass(ioMode);
        this.workerGroup = NettyUtils.createEventLoop(
                ioMode,
                0,
                "rpc" + "-client");
        this.pooledAllocator = NettyUtils.createPooledByteBufAllocator(
                true, false /* allowCache */, 0);
        this.clientBootstraps = bootstraps;
    }

    public TransportClient createClient(String remoteHost, int remotePort) throws IOException, InterruptedException {
        final InetSocketAddress unresolvedAddress = InetSocketAddress.createUnresolved(remoteHost, remotePort);
        // Create the ClientPool if we don't have it yet.
        ClientPool clientPool = connectionPool.get(unresolvedAddress);
        if (clientPool == null) {
            connectionPool.putIfAbsent(unresolvedAddress, new ClientPool(numConnectionsPerPeer));
            clientPool = connectionPool.get(unresolvedAddress);
        }
        int clientIndex = rand.nextInt(numConnectionsPerPeer);
        TransportClient cachedClient = clientPool.clients[clientIndex];

        if (cachedClient != null && cachedClient.isActive()) {
            // Make sure that the channel will not timeout by updating the last use time of the
            // handler. Then check that the client is still alive, in case it timed out before
            // this code was able to update things.
            TransportChannelHandler handler = cachedClient.getChannel().pipeline()
                    .get(TransportChannelHandler.class);
            synchronized (handler) {
                handler.getResponseHandler().updateTimeOfLastRequest();
            }

            if (cachedClient.isActive()) {
                logger.trace("Returning cached connection to {}: {}",
                        cachedClient.getSocketAddress(), cachedClient);
                return cachedClient;
            }
        }
        final long preResolveHost = System.nanoTime();
        final InetSocketAddress resolvedAddress = new InetSocketAddress(remoteHost, remotePort);
        final long hostResolveTimeMs = (System.nanoTime() - preResolveHost) / 1000000;
        if (hostResolveTimeMs > 2000) {
            logger.warn("DNS resolution for {} took {} ms", resolvedAddress, hostResolveTimeMs);
        } else {
            logger.trace("DNS resolution for {} took {} ms", resolvedAddress, hostResolveTimeMs);
        }

        synchronized (clientPool.locks[clientIndex]) {
            cachedClient = clientPool.clients[clientIndex];

            if (cachedClient != null) {
                if (cachedClient.isActive()) {
                    logger.trace("Returning cached connection to {}: {}", resolvedAddress, cachedClient);
                    return cachedClient;
                } else {
                    logger.info("Found inactive connection to {}, creating a new one.", resolvedAddress);
                }
            }
            clientPool.clients[clientIndex] = createClient(resolvedAddress);
            return clientPool.clients[clientIndex];
        }
    }

    private TransportClient createClient(InetSocketAddress address)
            throws IOException, InterruptedException {
        logger.debug("Creating new connection to {}", address);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(socketChannelClass)
                // Disable Nagle's Algorithm since we don't want packets to wait
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 120 * 1000)
                .option(ChannelOption.ALLOCATOR, pooledAllocator);

        final AtomicReference<TransportClient> clientRef = new AtomicReference<>();
        final AtomicReference<Channel> channelRef = new AtomicReference<>();
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) {
                TransportChannelHandler clientHandler = context.initializePipeline(ch);
                clientRef.set(clientHandler.getClient());
                channelRef.set(ch);
            }
        });

        long preConnect = System.nanoTime();
        ChannelFuture cf = bootstrap.connect(address);
        if (!cf.await(12 * 1000)) {
            throw new IOException( String.format("Connecting to %s timed out (%s ms)", address, connectionTimeoutMs));
        } else if (cf.cause() != null) {
            throw new IOException(String.format("Failed to connect to %s", address), cf.cause());
        }

        TransportClient client = clientRef.get();
        Channel channel = channelRef.get();
        assert client != null : "Channel future completed successfully with null client";

        // Execute any client bootstraps synchronously before marking the Client as successful.
        long preBootstrap = System.nanoTime();
        logger.debug("Connection to {} successful, running bootstraps...", address);
        try {
            for (TransportClientBootstrap clientBootstrap : clientBootstraps) {
                clientBootstrap.doBootstrap(client, channel);
            }
        } catch (Exception e) { // catch non-RuntimeExceptions too as bootstrap may be written in Scala
            long bootstrapTimeMs = (System.nanoTime() - preBootstrap) / 1000000;
            logger.error("Exception while bootstrapping client after " + bootstrapTimeMs + " ms", e);
            client.close();
            throw Throwables.propagate(e);
        }
        long postBootstrap = System.nanoTime();

        logger.info("Successfully created connection to {} after {} ms ({} ms spent in bootstraps)",
                address, (postBootstrap - preConnect) / 1000000, (postBootstrap - preBootstrap) / 1000000);

        return client;
    }

    @Override
    public void close() throws IOException {

    }
}
