package glutinous.network.server;

import io.netty.channel.Channel;

public interface TransportServerBootstrap {
    RpcHandler doBootstrap(Channel channel, RpcHandler rpcHandler);
}
