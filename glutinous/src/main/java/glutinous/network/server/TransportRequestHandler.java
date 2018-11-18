package glutinous.network.server;

import glutinous.network.client.TransportClient;
import glutinous.network.protocol.OneWayMessage;
import glutinous.network.protocol.RequestMessage;
import glutinous.network.protocol.RpcRequest;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransportRequestHandler {
    private static final Logger logger = LoggerFactory.getLogger(TransportRequestHandler.class);

    private final Channel channel;
    private final RpcHandler rpcHandler;
    private final TransportClient reverseClient;

    public TransportRequestHandler(Channel channel, TransportClient reverseClient, RpcHandler rpcHandler, long maxValue) {
        this.channel = channel;
        this.reverseClient = reverseClient;
        this.rpcHandler = rpcHandler;
    }

    public void handle(RequestMessage request) {
        if (request instanceof RpcRequest) {
            processRpcRequest((RpcRequest) request);
        } else if (request instanceof OneWayMessage) {
            processOneWayMessage((OneWayMessage) request);
//        }
//        else if (request instanceof StreamRequest) {
//            processStreamRequest((StreamRequest) request);
//        } else if (request instanceof UploadStream) {
//            processStreamUpload((UploadStream) request);
        } else {
            throw new IllegalArgumentException("Unknown request type: " + request);
        }
    }

    private void processOneWayMessage(OneWayMessage req) {
        try {
            rpcHandler.receive(reverseClient, req.body().nioByteBuffer());
        } catch (Exception e) {
            logger.error("Error while invoking RpcHandler#receive() for one-way message.", e);
        } finally {
            req.body().release();
        }
    }

    private void processRpcRequest(RpcRequest request) {
        System.out.println("need to implement to processRpcRequest");
    }

    public void channelActive() {
        rpcHandler.channelActive(reverseClient);
    }
}
