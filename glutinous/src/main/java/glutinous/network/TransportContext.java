package glutinous.network;

import glutinous.network.client.TransportClient;
import glutinous.network.client.TransportClientBootstrap;
import glutinous.network.client.TransportClientFactory;
import glutinous.network.client.TransportResponseHandler;
import glutinous.network.protocol.MessageDecoder;
import glutinous.network.protocol.MessageEncoder;
import glutinous.network.server.*;
import glutinous.network.util.NettyUtils;
import glutinous.network.util.TransportConf;
import glutinous.network.util.TransportFrameDecoder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class TransportContext {
    private static final Logger logger = LoggerFactory.getLogger(TransportContext.class);
    private final TransportConf conf;
    private final RpcHandler rpcHandler;
    private final boolean closeIdleConnections;
    private final boolean isClientOnly;

    private static final MessageEncoder ENCODER = MessageEncoder.INSTANCE;
    private static final MessageDecoder DECODER = MessageDecoder.INSTANCE;

    public TransportContext(TransportConf conf, RpcHandler rpcHandler) {
        this(conf, rpcHandler, false, false);
    }

    public TransportContext(
            TransportConf conf,
            RpcHandler rpcHandler,
            boolean closeIdleConnections,
            boolean isClientOnly) {
        this.conf = conf;
        this.rpcHandler = rpcHandler;
        this.closeIdleConnections = closeIdleConnections;
        this.isClientOnly = isClientOnly;

//        synchronized(TransportContext.class) {
//            if (chunkFetchWorkers == null &&
//                    conf.getModuleName() != null &&
//                    conf.getModuleName().equalsIgnoreCase("shuffle") &&
//                    !isClientOnly) {
//                chunkFetchWorkers = NettyUtils.createEventLoop(
//                        IOMode.valueOf(conf.ioMode()),
//                        conf.chunkFetchHandlerThreads(),
//                        "shuffle-chunk-fetch-handler");
//            }
//        }
    }


    /** Create a server which will attempt to bind to a specific host and port. */
    public TransportServer createServer(
            String host, int port, List<TransportServerBootstrap> bootstraps) {
        return new TransportServer(this, host, port, rpcHandler, bootstraps);
    }

    public TransportClientFactory createClientFactory(List<TransportClientBootstrap> bootstraps) {
        return new TransportClientFactory(this, bootstraps);
    }

    public TransportChannelHandler initializePipeline(SocketChannel channel) {
        return initializePipeline(channel, rpcHandler);
    }

    public TransportChannelHandler initializePipeline(
            SocketChannel channel,
            RpcHandler channelRpcHandler) {
        try {
            TransportChannelHandler channelHandler = createChannelHandler(channel, channelRpcHandler);
//            ChunkFetchRequestHandler chunkFetchHandler = createChunkFetchHandler(channelHandler, channelRpcHandler);
            ChannelPipeline pipeline = channel.pipeline()
                    .addLast("encoder", ENCODER)
                    .addLast(TransportFrameDecoder.HANDLER_NAME, NettyUtils.createFrameDecoder())
                    .addLast("decoder", DECODER)
                    .addLast("idleStateHandler",
                            new IdleStateHandler(0, 0, 12))
                    // NOTE: Chunks are currently guaranteed to be returned in the order of request, but this
                    // would require more logic to guarantee if this were not part of the same event loop.
                    .addLast("handler", channelHandler);
            // Use a separate EventLoopGroup to handle ChunkFetchRequest messages for shuffle rpcs.
//            if (conf.getModuleName() != null &&
//                    conf.getModuleName().equalsIgnoreCase("shuffle")
//                    && !isClientOnly) {
//                pipeline.addLast(chunkFetchWorkers, "chunkFetchHandler", chunkFetchHandler);
//            }
            return channelHandler;
        } catch (RuntimeException e) {
            logger.error("Error while initializing Netty pipeline", e);
            throw e;
        }
    }

    private TransportChannelHandler createChannelHandler(Channel channel, RpcHandler rpcHandler) {
        TransportResponseHandler responseHandler = new TransportResponseHandler(channel);
        TransportClient client = new TransportClient(channel, responseHandler);
        TransportRequestHandler requestHandler = new TransportRequestHandler(channel, client,
                rpcHandler, Long.MAX_VALUE);
        return new TransportChannelHandler(client, responseHandler, requestHandler, 120 * 1000, closeIdleConnections);
    }
}
