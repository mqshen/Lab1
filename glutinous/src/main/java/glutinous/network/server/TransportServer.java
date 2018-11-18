package glutinous.network.server;

import glutinous.network.TransportContext;
import glutinous.network.util.IOMode;
import glutinous.network.util.JavaUtils;
import glutinous.network.util.NettyUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import org.apache.commons.lang3.SystemUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

public class TransportServer implements Closeable {
    private static final Logger logger = LoggerFactory.getLogger(TransportServer.class);
    private int port = -1;
    private ServerBootstrap bootstrap;
    private List<TransportServerBootstrap> bootstraps;
    private ChannelFuture channelFuture;
    private final TransportContext context;
    private final RpcHandler appRpcHandler;

    public TransportServer(TransportContext context,
                           String hostToBind,
                           int portToBind,
                           RpcHandler rpcHandler,
                           List<TransportServerBootstrap> bootstraps) {
        this.context = context;
        this.appRpcHandler = rpcHandler;
        this.bootstraps = bootstraps;
        boolean shouldClose = true;
        try {
            init(hostToBind, portToBind);
            shouldClose = false;
        } finally {
            if (shouldClose) {
                JavaUtils.closeQuietly(this);
            }
        }
    }

    private void init(String hostToBind, int portToBind) {
        IOMode ioMode = IOMode.NIO;
        EventLoopGroup bossGroup =
                NettyUtils.createEventLoop(ioMode, 0, "rpc" + "-server");
        EventLoopGroup workerGroup = bossGroup;

        PooledByteBufAllocator allocator = NettyUtils.createPooledByteBufAllocator(
                true, true /* allowCache */, 0);
        bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NettyUtils.getServerChannelClass(ioMode))
                .option(ChannelOption.ALLOCATOR, allocator)
                .option(ChannelOption.SO_REUSEADDR, !SystemUtils.IS_OS_WINDOWS)
                .childOption(ChannelOption.ALLOCATOR, allocator);

        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                RpcHandler rpcHandler = appRpcHandler;
                for (TransportServerBootstrap bootstrap : bootstraps) {
                    rpcHandler = bootstrap.doBootstrap(ch, rpcHandler);
                }
                context.initializePipeline(ch, rpcHandler);
            }
        });

        InetSocketAddress address = hostToBind == null ?
                new InetSocketAddress(portToBind): new InetSocketAddress(hostToBind, portToBind);
        channelFuture = bootstrap.bind(address);
        channelFuture.syncUninterruptibly();

        port = ((InetSocketAddress) channelFuture.channel().localAddress()).getPort();
        logger.debug("Shuffle server started on port: {}", port);
    }

    @Override
    public void close() throws IOException {

    }

    public int getPort() {
        if (port == -1) {
            throw new IllegalStateException("Server not initialized");
        }
        return port;
    }
}
