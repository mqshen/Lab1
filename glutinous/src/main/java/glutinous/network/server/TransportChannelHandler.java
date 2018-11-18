package glutinous.network.server;

import glutinous.network.client.TransportClient;
import glutinous.network.client.TransportResponseHandler;
import glutinous.network.protocol.Message;
import glutinous.network.protocol.RequestMessage;
import glutinous.network.protocol.ResponseMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransportChannelHandler extends SimpleChannelInboundHandler<Message> {
    private static final Logger logger = LoggerFactory.getLogger(TransportChannelHandler.class);
    private final TransportRequestHandler requestHandler;
    private final TransportResponseHandler responseHandler;
    private final TransportClient client;
    private final long requestTimeoutNs;
    private final boolean closeIdleConnections;

    public TransportChannelHandler(
            TransportClient client,
            TransportResponseHandler responseHandler,
            TransportRequestHandler requestHandler,
            long requestTimeoutMs,
            boolean closeIdleConnections) {
        this.client = client;
        this.responseHandler = responseHandler;
        this.requestHandler = requestHandler;
        this.requestTimeoutNs = requestTimeoutMs * 1000L * 1000;
        this.closeIdleConnections = closeIdleConnections;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            requestHandler.channelActive();
        } catch (RuntimeException e) {
            logger.error("Exception from request handler while channel is inactive", e);
        }
        try {
            responseHandler.channelActive();
        } catch (RuntimeException e) {
            logger.error("Exception from response handler while channel is inactive", e);
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message request) throws Exception {
        if (request instanceof RequestMessage) {
            requestHandler.handle((RequestMessage) request);
        } else if (request instanceof ResponseMessage) {
            responseHandler.handle((ResponseMessage) request);
        } else {
            ctx.fireChannelRead(request);
        }
    }

    public TransportResponseHandler getResponseHandler() {
        return responseHandler;
    }

    public TransportClient getClient() {
        return client;
    }
}
