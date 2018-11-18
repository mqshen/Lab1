package glutinous.network.protocol;

import io.netty.buffer.ByteBuf;

public class RpcRequest extends AbstractMessage implements RequestMessage {
    @Override
    public Type type() {
        return null;
    }

    @Override
    public int encodedLength() {
        return 0;
    }

    @Override
    public void encode(ByteBuf buf) {

    }
}
