package glutinous.network.server;

import glutinous.network.client.RpcResponseCallback;
import glutinous.network.client.TransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public abstract class RpcHandler {
    private static final RpcResponseCallback ONE_WAY_CALLBACK = new OneWayRpcCallback();

    public abstract void receive(
            TransportClient client,
            ByteBuffer message,
            RpcResponseCallback callback);

    public void receive(TransportClient client, ByteBuffer message) {
        receive(client, message, ONE_WAY_CALLBACK);
    }

    public void channelActive(TransportClient reverseClient) {
    }

    private static class OneWayRpcCallback implements RpcResponseCallback {

        private static final Logger logger = LoggerFactory.getLogger(OneWayRpcCallback.class);

        @Override
        public void onSuccess(ByteBuffer response) {
            logger.warn("Response provided for one-way RPC.");
        }

        @Override
        public void onFailure(Throwable e) {
            logger.error("Error response provided for one-way RPC.", e);
        }

    }
}
